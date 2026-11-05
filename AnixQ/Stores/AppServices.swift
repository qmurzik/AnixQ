import SwiftUI
import UIKit

// MARK: - Favourite dubs

/// Ordered list of favourite dub / subtitle teams. The first one available is picked automatically.
enum DubPreferences {
    static let key = "dubs.favorites"
    static let autoLearnKey = "dubs.autoLearn"

    static var favorites: [String] {
        get { UserDefaults.standard.stringArray(forKey: key) ?? [] }
        set { UserDefaults.standard.set(Array(newValue.prefix(12)), forKey: key) }
    }

    static var autoLearn: Bool {
        get { UserDefaults.standard.object(forKey: autoLearnKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: autoLearnKey) }
    }

    /// Remembers a team the user picked by hand (appended, so manual order stays on top).
    static func use(_ name: String) {
        guard autoLearn, !name.isEmpty, !favorites.contains(name) else { return }
        favorites.append(name)
    }

    /// Best team for a title: favourites in order, ad-free player first.
    static func pick(_ teams: [TeamOption]) -> TeamOption? {
        for name in favorites {
            if let t = teams.first(where: { $0.native && $0.team.name == name }) { return t }
        }
        for name in favorites {
            if let t = teams.first(where: { $0.team.name == name }) { return t }
        }
        return nil
    }
}

// MARK: - Updates

/// Compares the installed version with the GBox source published with every release.
@MainActor
final class UpdateChecker: ObservableObject {
    static let shared = UpdateChecker()
    static let sourceURL = URL(string: "https://raw.githubusercontent.com/qmurzik/AnixQ/gbox-source/gbox.json")!
    static let siteURL = URL(string: "https://qmurzik.github.io/AnixQ/")!
    static let releasesURL = URL(string: "https://github.com/qmurzik/AnixQ/releases/latest")!
    static let ipaURL = URL(string: "https://github.com/qmurzik/AnixQ/releases/latest/download/AnixQ.ipa")!

    struct Update: Equatable {
        let version: String
        let notes: String
    }

    @Published private(set) var available: Update?
    @Published private(set) var isChecking = false
    @Published private(set) var lastChecked: Date?

    static var installedVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    private var dismissedVersion: String? {
        get { UserDefaults.standard.string(forKey: "update.dismissed") }
        set { UserDefaults.standard.set(newValue, forKey: "update.dismissed") }
    }

    /// Banner on the home screen (not shown again for a version the user closed).
    var banner: Update? {
        guard let available, available.version != dismissedVersion else { return nil }
        return available
    }

    func dismissBanner() {
        dismissedVersion = available?.version
        objectWillChange.send()
    }

    func check(force: Bool = false) async {
        if !force, let lastChecked, Date().timeIntervalSince(lastChecked) < 3 * 3600 { return }
        isChecking = true
        defer { isChecking = false }
        var req = URLRequest(url: Self.sourceURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        guard let (data, _) = try? await URLSession.shared.data(for: req),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let app = (json["appRepositories"] as? [[String: Any]])?.first,
              let version = app["appVersion"] as? String else { return }
        lastChecked = Date()
        let notes = (app["appUpdateDescription"] as? String) ?? (app["appDescription"] as? String) ?? ""
        available = Self.isNewer(version, than: Self.installedVersion) ? Update(version: version, notes: notes) : nil
        DebugLog.log("update: installed \(Self.installedVersion), latest \(version)")
    }

    static func isNewer(_ a: String, than b: String) -> Bool {
        let x = a.split(separator: ".").map { Int($0) ?? 0 }
        let y = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(x.count, y.count) {
            let l = i < x.count ? x[i] : 0, r = i < y.count ? y[i] : 0
            if l != r { return l > r }
        }
        return false
    }
}

struct UpdateBanner: View {
    let update: UpdateChecker.Update
    @ObservedObject private var checker = UpdateChecker.shared
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                Image(systemName: "arrow.down.app.fill").font(.title2).foregroundStyle(Theme.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Доступна версия \(update.version)").font(.headline)
                    Text("У вас \(UpdateChecker.installedVersion). Обновите через GBox или скачайте IPA.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Button { withAnimation { checker.dismissBanner() } } label: {
                    Image(systemName: "xmark").font(.footnote.weight(.bold)).foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 10) {
                Button { openURL(UpdateChecker.ipaURL) } label: { Text("Скачать IPA").frame(maxWidth: .infinity) }
                    .buttonStyle(PrimaryButtonStyle())
                Button { openURL(UpdateChecker.releasesURL) } label: {
                    Text("Что нового").padding(.horizontal, 14)
                }
                .buttonStyle(GlassButtonStyle())
            }
        }
        .padding(16)
        .cardStyle()
        .padding(.horizontal)
    }
}

// MARK: - Problem reports

enum ProblemReporter {
    static let newIssueURL = "https://github.com/qmurzik/AnixQ/issues/new"

    static func deviceInfo() -> String {
        var sys = utsname()
        uname(&sys)
        let model = withUnsafeBytes(of: &sys.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        return """
        - Версия AnixQ: \(UpdateChecker.installedVersion) (\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"))
        - Устройство: \(model), \(UIDevice.current.systemName) \(UIDevice.current.systemVersion)
        - Стиль: \(AppStyle.current.title), AnimeLib: \(LibSession.isLoggedIn ? "вход" : "нет"), Shikimori: \(ShikiSession.isLoggedIn ? "вход" : "нет")
        """
    }

    static func report(description: String, includeLog: Bool) -> String {
        var parts = ["## Что случилось", description.isEmpty ? "_(опишите проблему)_" : description,
                     "## Устройство", deviceInfo()]
        if includeLog {
            if let crash = CrashReporter.lastReport {
                parts += ["## Последний сбой", "```", String(crash.prefix(2500)), "```"]
            }
            let log = DebugLog.read().split(separator: "\n").suffix(80).joined(separator: "\n")
            if !log.isEmpty { parts += ["## Журнал", "```", log, "```"] }
        }
        return parts.joined(separator: "\n\n")
    }

    /// GitHub issue form with everything pre-filled (trimmed to fit into a URL).
    static func issueURL(title: String, body: String) -> URL? {
        var body = body
        if body.count > 6000 { body = String(body.prefix(6000)) + "\n…\n```" }
        var c = URLComponents(string: newIssueURL)
        c?.queryItems = [URLQueryItem(name: "title", value: title.isEmpty ? "Проблема в AnixQ" : title),
                         URLQueryItem(name: "body", value: body)]
        return c?.url
    }
}

struct ReportProblemView: View {
    @State private var title = ""
    @State private var text = ""
    @State private var includeLog = true
    @Environment(\.openURL) private var openURL

    private var report: String { ProblemReporter.report(description: text, includeLog: includeLog) }

    var body: some View {
        Form {
            Section {
                TextField("Коротко: что не так", text: $title)
                TextField("Подробнее: что делали, что ожидали, что получилось", text: $text, axis: .vertical)
                    .lineLimit(4...10)
            } footer: {
                Text("Название тайтла, номер серии и озвучка очень помогают найти причину.")
            }
            Section {
                Toggle("Приложить журнал и отчёт о сбое", isOn: $includeLog)
            } footer: {
                Text("В журнале нет паролей и токенов — только адреса запросов, ошибки и действия в плеере.")
            }
            Section {
                Button {
                    if let url = ProblemReporter.issueURL(title: title, body: report) { openURL(url) }
                } label: {
                    Label("Открыть задачу на GitHub", systemImage: "exclamationmark.bubble")
                }
                ShareLink(item: (title.isEmpty ? "" : title + "\n\n") + report) {
                    Label("Отправить текстом (Telegram, почта…)", systemImage: "square.and.arrow.up")
                }
            } footer: {
                Text("Для GitHub нужен аккаунт. Без него — отправьте отчёт текстом.")
            }
            Section("Что попадёт в отчёт") {
                Text(report).font(.system(size: 10, design: .monospaced)).foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
        }
        .styledList()
        .navigationTitle("Сообщить о проблеме")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Favourite dubs editor

struct DubPreferencesView: View {
    @State private var items = DubPreferences.favorites
    @State private var autoLearn = DubPreferences.autoLearn
    @State private var newName = ""

    var body: some View {
        List {
            Section {
                ForEach(items, id: \.self) { name in
                    Label(name, systemImage: "waveform")
                }
                .onDelete { items.remove(atOffsets: $0) }
                .onMove { items.move(fromOffsets: $0, toOffset: $1) }
                HStack {
                    TextField("Добавить озвучку, например AniLibria", text: $newName)
                        .autocorrectionDisabled()
                        .onSubmit(add)
                    Button("Добавить", action: add).disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            } header: {
                Text("По порядку приоритета")
            } footer: {
                Text("При открытии тайтла выбирается первая доступная озвучка из списка — сначала в плеере без рекламы. Перетащите, чтобы изменить порядок.")
            }
            Section {
                Toggle("Запоминать выбранные вручную", isOn: $autoLearn)
            } footer: {
                Text("Озвучка, которую вы выбрали в тайтле или плеере, добавится в конец списка.")
            }
        }
        .styledList()
        .navigationTitle("Любимые озвучки")
        .toolbar { EditButton() }
        .onChange(of: items) { DubPreferences.favorites = $0 }
        .onChange(of: autoLearn) { DubPreferences.autoLearn = $0 }
    }

    private func add() {
        let n = newName.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty, !items.contains(n) else { return }
        items.append(n)
        newName = ""
    }
}

// MARK: - iCloud sync

/// Mirrors the library and settings through iCloud key-value storage (no account or server needed).
@MainActor
final class CloudSync: ObservableObject {
    static let shared = CloudSync()
    static let enabledKey = "icloud.enabled"
    private static let libraryKey = "library.v1"
    private static let settingsKey = "settings.v1"

    /// Settings that follow the user between devices.
    private static let settingKeys = [
        "accent", AppStyle.storageKey, "adblock", "subs.alwaysOn", "subs.size", "subs.background",
        DubPreferences.key, DubPreferences.autoLearnKey, "autodl.enabled", "autodl.count", "download.quality",
        "gesture.brightness", "gesture.seek", "gesture.volume", "player.autoNext", "player.autoSkip",
        "player.opLength", "player.quality", "player.rate", "player.rememberSpeed", "player.tapStep"
    ]

    @Published private(set) var status = "—"
    private let store = NSUbiquitousKeyValueStore.default
    private weak var library: LibraryStore?
    private var pushTask: Task<Void, Never>?
    private var started = false
    private var applying = false

    static var isEnabled: Bool { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true }

    func start(library: LibraryStore) {
        self.library = library
        guard Self.isEnabled else { status = "выключено"; return }
        guard FileManager.default.ubiquityIdentityToken != nil else {
            status = "войдите в iCloud в Настройках iOS"
            return
        }
        if !started {
            started = true
            NotificationCenter.default.addObserver(forName: NSUbiquitousKeyValueStore.didChangeExternallyNotification,
                                                   object: store, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.pull() }
            }
            NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification,
                                                   object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.settingsChanged() }
            }
        }
        let ok = store.synchronize()
        status = ok ? "включено" : "недоступно для этой подписи"
        pull()
        libraryChanged()
    }

    private func pull() {
        guard Self.isEnabled, let library else { return }
        applying = true
        defer { applying = false }
        if let data = store.data(forKey: Self.libraryKey),
           let raw = try? (data as NSData).decompressed(using: .lzfse) as Data,
           let remote = try? JSONDecoder().decode(CloudLibrary.self, from: raw),
           library.mergeCloud(remote) {
            DebugLog.log("icloud: merged library")
        }
        if let s = store.dictionary(forKey: Self.settingsKey),
           let stamp = s["_date"] as? Double, stamp > UserDefaults.standard.double(forKey: "icloud.settingsDate") {
            for key in Self.settingKeys {
                if let v = s[key] { UserDefaults.standard.set(v, forKey: key) }
            }
            UserDefaults.standard.set(stamp, forKey: "icloud.settingsDate")
            Theme.configureAppearance()
            DebugLog.log("icloud: applied settings")
        }
        status = "синхронизировано \(Date().formatted(date: .omitted, time: .shortened))"
    }

    func libraryChanged() {
        guard Self.isEnabled, started, let library else { return }
        pushTask?.cancel()
        pushTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled, let self else { return }
            guard let json = try? JSONEncoder().encode(library.cloudSnapshot()),
                  let packed = try? (json as NSData).compressed(using: .lzfse) as Data else { return }
            guard packed.count < 900_000 else {
                DebugLog.log("icloud: library too big (\(packed.count) bytes)")
                return
            }
            self.store.set(packed, forKey: Self.libraryKey)
            self.store.synchronize()
        }
    }

    private var lastSettings: [String: String] = [:]

    private func settingsChanged() {
        guard Self.isEnabled, started, !applying else { return }
        var dict: [String: Any] = [:]
        var signature: [String: String] = [:]
        for key in Self.settingKeys {
            if let v = UserDefaults.standard.object(forKey: key) {
                dict[key] = v
                signature[key] = "\(v)"
            }
        }
        guard signature != lastSettings else { return }
        lastSettings = signature
        let now = Date().timeIntervalSince1970
        dict["_date"] = now
        UserDefaults.standard.set(now, forKey: "icloud.settingsDate")
        store.set(dict, forKey: Self.settingsKey)
    }
}
