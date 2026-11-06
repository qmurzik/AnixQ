import SwiftUI
import CryptoKit

// MARK: - Shared document (see docs/sync-format.md)

struct SyncDoc: Codable {
    struct Prog: Codable, Equatable {
        var pos: Double
        var dur: Double
        var at: Double
        var title: Int?
        var ep: String?
        var epName: String?
        var team: Int?
        var teamName: String?
    }
    struct Watched: Codable, Equatable {
        var eps: [String]
        var at: Double
    }
    struct Bookmark: Codable, Equatable {
        var title: Int?
        var status: Int?
        var at: Double
    }
    struct Prefs: Codable, Equatable {
        var dubs: [String]
        var at: Double
    }

    var v = 1
    var device = ""
    var updated: Double = 0
    var titles: [String: Title] = [:]
    var progress: [String: Prog] = [:]
    var watched: [String: Watched] = [:]
    var bookmarks: [String: Bookmark] = [:]
    var prefs: Prefs?
    /// Phone-remote channel created by the TV (see RemoteTV).
    struct Remote: Codable, Equatable { var channel: String; var key: String; var at: Double }
    var remote: Remote?
}

// MARK: - Library <-> document

extension LibraryStore {
    /// Removed bookmarks, kept as tombstones so a removal on one device wins on the others.
    static var removedBookmarks: [String: Double] {
        get { UserDefaults.standard.dictionary(forKey: "sync.removedBookmarks") as? [String: Double] ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: "sync.removedBookmarks") }
    }
}

// MARK: - GitHub Gist sync

/// "Идеальная синхронизация" between the user's devices (iPhone, iPad, Android TV) through a private
/// Gist in their own GitHub account: no server of ours, newest data wins per episode / title.
@MainActor
final class GistSync: ObservableObject {
    static let shared = GistSync()
    static let clientId = "Ov23liCzZBzFN0H5bIuO"
    private static let fileName = "anixq-sync.json"
    private static let description = "AnixQ sync"

    struct DeviceLogin: Equatable {
        let userCode: String
        let verificationURL: URL
    }

    @Published private(set) var status = "Выключено"
    @Published private(set) var login: DeviceLogin?
    @Published private(set) var lastSync: Date?
    @Published private(set) var busy = false

    private weak var library: LibraryStore?
    private var timer: Timer?
    private var pushTask: Task<Void, Never>?
    private var etag: String?
    private var lastPushedHash = UserDefaults.standard.string(forKey: "github.lastHash") ?? ""
    private var loginTask: Task<Void, Never>?

    var token: String? { SecureStore.string("github.token") }
    var gistId: String? { UserDefaults.standard.string(forKey: "github.gist") }
    var account: String? { UserDefaults.standard.string(forKey: "github.login") }
    var isOn: Bool { token != nil && gistId != nil }

    // MARK: Lifecycle

    func start(library: LibraryStore) {
        self.library = library
        guard isOn else { status = "Выключено"; return }
        if timer == nil {
            timer = Timer.scheduledTimer(withTimeInterval: 12, repeats: true) { [weak self] _ in
                Task { @MainActor in await self?.sync() }
            }
        }
        Task { await sync() }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// Local data changed: push within ~4 s. Further changes don't postpone it (the player saves every 5 s,
    /// so a reset-on-every-change debounce never fired while watching).
    func changed() {
        guard isOn, pushTask == nil else { return }
        pushTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            pushTask = nil
            await sync()
        }
    }

    /// Push now — leaving the player or the app. Keeps running briefly in the background so the TV gets it.
    func flush() {
        guard isOn else { return }
        pushTask?.cancel(); pushTask = nil
        var bg = UIBackgroundTaskIdentifier.invalid
        bg = UIApplication.shared.beginBackgroundTask(withName: "anixq.sync") { UIApplication.shared.endBackgroundTask(bg) }
        Task {
            // If a sync is already running, wait for it and push again with the newest position.
            for _ in 0..<20 where busy { try? await Task.sleep(nanoseconds: 150_000_000) }
            await sync()
            UIApplication.shared.endBackgroundTask(bg)
        }
    }

    /// Before starting an episode: get the TV's latest position (waits at most ~3 s).
    func pullFresh() async {
        guard isOn, Date().timeIntervalSince(lastSync ?? .distantPast) > 4 else { return }
        await withTaskGroup(of: Void.self) { g in
            g.addTask { await self.sync() }
            g.addTask { try? await Task.sleep(nanoseconds: 3_000_000_000) }
            _ = await g.next()
            g.cancelAll()
        }
    }

    // MARK: Sign in (GitHub device flow)

    func signIn() {
        loginTask?.cancel()
        loginTask = Task {
            do {
                let code = try await post("https://github.com/login/device/code", ["client_id": Self.clientId, "scope": "gist"])
                guard let device = code["device_code"] as? String, let user = code["user_code"] as? String,
                      let uri = (code["verification_uri"] as? String).flatMap(URL.init(string:)) else {
                    status = "GitHub не ответил"; return
                }
                login = DeviceLogin(userCode: user, verificationURL: uri)
                status = "Введите код на GitHub"
                var interval = (code["interval"] as? Int) ?? 5
                let deadline = Date().addingTimeInterval(Double((code["expires_in"] as? Int) ?? 900))
                while Date() < deadline, !Task.isCancelled {
                    try await Task.sleep(nanoseconds: UInt64(interval) * 1_000_000_000)
                    let r = try await post("https://github.com/login/oauth/access_token",
                                           ["client_id": Self.clientId, "device_code": device,
                                            "grant_type": "urn:ietf:params:oauth:grant-type:device_code"])
                    if let t = r["access_token"] as? String {
                        try await finishLogin(token: t)
                        return
                    }
                    switch r["error"] as? String {
                    case "slow_down": interval += 5
                    case "authorization_pending": continue
                    case "expired_token", "access_denied":
                        login = nil; status = "Вход отменён"; return
                    default: continue
                    }
                }
                login = nil
                status = "Код истёк — попробуйте снова"
            } catch {
                login = nil
                status = "Ошибка входа: \(error.localizedDescription)"
            }
        }
    }

    /// Token received (device flow or from another device while pairing).
    func finishLogin(token: String, gist: String? = nil) async throws {
        SecureStore.set("github.token", token)
        login = nil
        if let me = try? await api("GET", "/user") as? [String: Any], let name = me["login"] as? String {
            UserDefaults.standard.set(name, forKey: "github.login")
        }
        if let gist {
            UserDefaults.standard.set(gist, forKey: "github.gist")
        } else {
            try await findOrCreateGist()
        }
        etag = nil
        status = "Подключено"
        if let library { start(library: library) }
    }

    func signOut() {
        stop()
        SecureStore.set("github.token", nil)
        UserDefaults.standard.removeObject(forKey: "github.gist")
        UserDefaults.standard.removeObject(forKey: "github.login")
        lastPushedHash = ""
        status = "Выключено"
    }

    private func findOrCreateGist() async throws {
        // An existing AnixQ gist (another device created it first) is reused.
        if let list = try await api("GET", "/gists?per_page=100") as? [[String: Any]],
           let found = list.first(where: { ($0["description"] as? String) == Self.description }),
           let id = found["id"] as? String {
            UserDefaults.standard.set(id, forKey: "github.gist")
            return
        }
        let doc = SyncDoc(device: UIDevice.current.model)
        let body: [String: Any] = ["description": Self.description, "public": false,
                                   "files": [Self.fileName: ["content": String(decoding: try encode(doc), as: UTF8.self)]]]
        guard let created = try await api("POST", "/gists", body: body) as? [String: Any],
              let id = created["id"] as? String else { throw LibError.server("Не удалось создать Gist") }
        UserDefaults.standard.set(id, forKey: "github.gist")
    }

    // MARK: Sync

    /// Pull → merge into the library → push the merged result if anything is new.
    func sync() async {
        guard isOn, !busy, let library, let gist = gistId else { return }
        busy = true
        defer { busy = false }
        do {
            var remote: SyncDoc?
            let (data, code, newTag) = try await raw("GET", "/gists/\(gist)", etag: etag)
            if code == 404 {
                // Gist deleted on GitHub: start a new one.
                UserDefaults.standard.removeObject(forKey: "github.gist")
                try await findOrCreateGist()
                return
            }
            if code == 200, let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
               let file = (json["files"] as? [String: Any])?[Self.fileName] as? [String: Any] {
                var content = file["content"] as? String ?? ""
                if file["truncated"] as? Bool == true, let rawURL = (file["raw_url"] as? String).flatMap(URL.init(string:)) {
                    content = String(decoding: try await URLSession.shared.data(from: rawURL).0, as: UTF8.self)
                }
                remote = try? JSONDecoder().decode(SyncDoc.self, from: Data(content.utf8))
                etag = newTag
            }
            if let remote, library.importSync(remote) {
                DebugLog.log("sync: merged changes from \(remote.device)")
            }
            var doc = library.exportSync()
            doc.device = UIDevice.current.model
            let hash = SHA256.hash(data: try encode(doc)).map { String(format: "%02x", $0) }.joined()
            if hash != lastPushedHash {
                doc.updated = Date().timeIntervalSince1970
                let body: [String: Any] = ["files": [Self.fileName: ["content": String(decoding: try encode(doc), as: UTF8.self)]]]
                _ = try await api("PATCH", "/gists/\(gist)", body: body)
                lastPushedHash = hash
                UserDefaults.standard.set(hash, forKey: "github.lastHash")
                etag = nil
            }
            lastSync = Date()
            status = "Синхронизировано"
        } catch {
            status = "Нет связи с GitHub"
            DebugLog.log("sync: \(error.localizedDescription)")
        }
    }

    // MARK: HTTP

    private func encode(_ doc: SyncDoc) throws -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return try e.encode(doc)
    }

    private func post(_ url: String, _ body: [String: String]) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await URLSession.shared.data(for: req)
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private func raw(_ method: String, _ path: String, body: Any? = nil, etag: String? = nil) async throws -> (Data, Int, String?) {
        guard let token else { throw LibError.unauthorized }
        var req = URLRequest(url: URL(string: "https://api.github.com" + path)!)
        req.httpMethod = method
        req.timeoutInterval = 20
        req.cachePolicy = .reloadIgnoringLocalCacheData
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        req.setValue("AnixQ", forHTTPHeaderField: "User-Agent")
        if let etag { req.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        if let body {
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        let http = resp as? HTTPURLResponse
        let code = http?.statusCode ?? 0
        if code == 401 {
            signOut()
            status = "Вход в GitHub истёк — войдите снова"
            throw LibError.unauthorized
        }
        guard (200..<300).contains(code) || code == 304 || code == 404 else { throw LibError.badStatus(code) }
        return (data, code, http?.value(forHTTPHeaderField: "ETag"))
    }

    private func api(_ method: String, _ path: String, body: Any? = nil) async throws -> Any {
        let (data, code, _) = try await raw(method, path, body: body)
        if code == 404 { throw LibError.notFound }
        return data.isEmpty ? [:] : try JSONSerialization.jsonObject(with: data)
    }
}

// MARK: - Pairing a TV (QR from the TV → phone sends logins encrypted)

struct PairRequest: Identifiable {
    let channel: String
    let key: SymmetricKey
    var id: String { channel }

    /// anixq://pair/<channel>?k=<base64url key>
    init?(url: URL) {
        guard url.host == "pair", let channel = url.pathComponents.dropFirst().first,
              let k = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "k" })?.value
        else { return nil }
        var b64 = k.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64), data.count == 32, channel.count >= 8 else { return nil }
        self.channel = channel
        self.key = SymmetricKey(data: data)
    }
}

struct PairTVView: View {
    let request: PairRequest
    @ObservedObject private var sync = GistSync.shared
    @Environment(\.dismiss) private var dismiss
    @State private var sendSync = true
    @State private var sendLib = LibSession.isLoggedIn
    @State private var sendShiki = ShikiSession.isLoggedIn
    @State private var state: String?
    @State private var done = false
    @State private var client: MQTTClient?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 10) {
                        Image(systemName: "tv.and.mediabox.fill").font(.system(size: 44)).foregroundStyle(Theme.accent)
                        Text("Подключить телевизор").font(.title3.bold())
                        Text("Телевизор получит ваши входы и сразу начнёт синхронизироваться с этим iPhone.")
                            .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 8)
                }
                Section {
                    Toggle("Синхронизация (GitHub)", isOn: $sendSync).disabled(!sync.isOn)
                    if !sync.isOn {
                        Text("Сначала включите синхронизацию в Настройки → Синхронизация устройств.").font(.caption).foregroundStyle(.secondary)
                    }
                    Toggle("AnimeLib — плеер без рекламы", isOn: $sendLib).disabled(!LibSession.isLoggedIn)
                    Toggle("Shikimori", isOn: $sendShiki).disabled(!ShikiSession.isLoggedIn)
                } footer: {
                    Text("Данные шифруются ключом из QR-кода: прочитать их может только этот телевизор.")
                }
                Section {
                    Button {
                        send()
                    } label: {
                        Label(done ? "Готово — можно закрыть" : "Передать на телевизор", systemImage: done ? "checkmark.circle.fill" : "paperplane.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(done || (!sendSync && !sendLib && !sendShiki))
                    if let state { Text(state).font(.footnote).foregroundStyle(.secondary) }
                }
            }
            .navigationTitle("Телевизор")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { client?.disconnect(); dismiss() } } }
        }
    }

    private func payload() -> Data {
        var p: [String: Any] = [:]
        if sendSync, sync.isOn, let t = sync.token, let g = sync.gistId { p["github"] = ["token": t, "gist": g] }
        if sendLib, let t = LibSession.token {
            p["animelib"] = ["token": t, "refresh": LibSession.refreshToken ?? "", "username": LibSession.username ?? "",
                             "userId": LibSession.userId ?? 0] as [String: Any]
        }
        if sendShiki {
            p["shiki"] = ["token": ShikiSession.token ?? "", "refresh": ShikiSession.refreshToken ?? "",
                          "cookies": ShikiSession.cookieHeader ?? "", "csrf": ShikiSession.csrf ?? "",
                          "userId": ShikiSession.userId ?? 0, "nickname": ShikiSession.nickname ?? "",
                          // So the TV can renew the 1-day token by itself.
                          "clientId": ShikiSession.clientId ?? "", "clientSecret": ShikiSession.clientSecret ?? ""] as [String: Any]
        }
        p["from"] = UIDevice.current.name
        return (try? JSONSerialization.data(withJSONObject: p)) ?? Data()
    }

    private func send() {
        guard let sealed = try? AES.GCM.seal(payload(), using: request.key).combined else { state = "Не удалось зашифровать"; return }
        let message = Data(sealed.base64EncodedString().utf8)
        state = "Подключаемся…"
        let c = MQTTClient()
        client = c
        let topic = "anixq/pair/\(request.channel)"
        c.onState = { ok in
            Task { @MainActor in
                guard ok else { return }
                c.publish(topic, message, retain: true)
                state = "Отправлено. Телевизор подхватит вход через пару секунд."
                done = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { c.disconnect() }
            }
        }
        c.connect(MQTTClient.brokers[0])
    }
}

// MARK: - Settings screen

struct DeviceSyncView: View {
    @ObservedObject private var sync = GistSync.shared
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.openURL) private var openURL

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Label("Синхронизация устройств", systemImage: "arrow.triangle.2.circlepath.circle.fill")
                        .font(.headline).foregroundStyle(Theme.accent)
                    Text("Остановились на телевизоре — продолжите на iPhone с той же секунды, и наоборот. История, «Продолжить», закладки, отмеченные серии и любимые озвучки хранятся в скрытом Gist вашего аккаунта GitHub.")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }
            if sync.isOn {
                Section {
                    LabeledContent("GitHub", value: sync.account ?? "подключено")
                    LabeledContent("Состояние", value: sync.status)
                    if let d = sync.lastSync { LabeledContent("Последняя", value: d.formatted(date: .omitted, time: .standard)) }
                    Button { Task { await sync.sync() } } label: {
                        HStack { Text("Синхронизировать сейчас"); Spacer(); if sync.busy { ProgressView() } }
                    }
                }
                Section {
                    Label("Откройте AnixQ на Android TV → «Войти с телефона» и наведите камеру iPhone на QR-код на экране.", systemImage: "qrcode.viewfinder")
                        .font(.subheadline)
                } header: { Text("Телевизор") }
                Section { Button("Отключить синхронизацию", role: .destructive) { sync.signOut() } }
            } else if let login = sync.login {
                Section {
                    VStack(spacing: 12) {
                        Text("Введите код на GitHub").font(.headline)
                        Text(login.userCode).font(.system(size: 34, weight: .heavy, design: .monospaced)).textSelection(.enabled)
                        Button {
                            UIPasteboard.general.string = login.userCode
                            openURL(login.verificationURL)
                        } label: {
                            Label("Скопировать и открыть GitHub", systemImage: "safari").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        Text("Вставьте код, нажмите Continue → Authorize. Затем вернитесь сюда — всё подключится само.")
                            .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 6)
                }
            } else {
                Section {
                    Button { sync.signIn() } label: { Label("Войти через GitHub", systemImage: "person.badge.key.fill") }
                } footer: {
                    Text("Нужен бесплатный аккаунт GitHub. AnixQ получит доступ только к Gist (заметкам), не к вашим репозиториям.")
                }
            }
        }
        .styledList()
        .navigationTitle("Синхронизация")
        .onAppear { if sync.isOn { sync.start(library: library) } }
    }
}
