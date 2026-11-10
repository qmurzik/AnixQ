import SwiftUI
import CryptoKit

/// Phone → Android TV remote over the internet (works from anywhere, not only at home).
/// The TV creates a private channel + key and stores them in the synced GitHub Gist; we read them
/// from there, so there is nothing to pair. Messages are AES-GCM sealed JSON relayed by MQTT:
///   anixq/rc/<channel>/cmd    phone → TV
///   anixq/rc/<channel>/state  TV → phone (retained)
@MainActor
final class RemoteTV: ObservableObject {
    static let shared = RemoteTV()

    struct TVState: Equatable {
        var state: String           // playing, paused, idle, error
        var title: String
        var slug: String
        var cover: URL?
        var ep: String
        var epName: String
        var team: String
        var pos: Double
        var dur: Double
        var hasNext: Bool
        var hasPrev: Bool
        var inOpening: Bool
        var device: String
        var at: Double
        var isActive: Bool { state == "playing" || state == "paused" }
    }

    @Published private(set) var tv: TVState?
    @Published private(set) var connected = false
    @Published var showRemote = false

    private var client: MQTTClient?
    private var pending: [Data] = []

    // MARK: Channel (from the gist)

    static var channel: String? { UserDefaults.standard.string(forKey: "rc.channel").flatMap { $0.isEmpty ? nil : $0 } }
    static var keyB64: String? { UserDefaults.standard.string(forKey: "rc.key") }
    static var at: Double { UserDefaults.standard.double(forKey: "rc.at") }
    var isAvailable: Bool { Self.channel != nil && key != nil }

    static func adopt(channel: String, key: String, at: Double) {
        guard channel != Self.channel || key != keyB64 else { return }
        UserDefaults.standard.set(channel, forKey: "rc.channel")
        UserDefaults.standard.set(key, forKey: "rc.key")
        UserDefaults.standard.set(at, forKey: "rc.at")
        Task { @MainActor in shared.reconnect() }
    }

    private var key: SymmetricKey? {
        guard var b = Self.keyB64 else { return nil }
        b = b.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        guard let d = Data(base64Encoded: b), d.count == 32 else { return nil }
        return SymmetricKey(data: d)
    }

    // MARK: Connection

    func connect() {
        guard client == nil, let ch = Self.channel else { return }
        let c = MQTTClient()
        client = c
        let stateTopic = "anixq/rc/\(ch)/state"
        let livePrefix = "anixq/rc/\(ch)/live/"
        let mine = livePrefix + Self.deviceId
        c.subscribe(stateTopic)
        c.subscribe(livePrefix + "#")
        c.onState = { [weak self] ok in
            Task { @MainActor in
                guard let self else { return }
                self.connected = ok
                if ok {
                    let queued = self.pending; self.pending = []
                    queued.forEach { c.publish("anixq/rc/\(ch)/cmd", $0) }
                    if let live = self.pendingLive { self.pendingLive = nil; c.publish(mine, live, retain: true) }
                } else if self.client === c {
                    // Reconnect quietly while the app is open (live sync) or the remote is in use.
                    self.client = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3) { if self.liveWanted || self.showRemote { self.connect() } }
                }
            }
        }
        c.onMessage = { [weak self] topic, data in
            Task { @MainActor in
                if topic == stateTopic { self?.received(data) }
                else if topic.hasPrefix(livePrefix), topic != mine { self?.receivedLive(data) }
            }
        }
        c.connect(MQTTClient.brokers[0])
    }

    func disconnect() { client?.disconnect(); client = nil; connected = false }

    // MARK: Live sync (instant progress between phone and TV, no GitHub)

    static var deviceId: String {
        if let id = UserDefaults.standard.string(forKey: "rc.device") { return id }
        let id = "ios-" + UUID().uuidString.prefix(8).lowercased()
        UserDefaults.standard.set(id, forKey: "rc.device")
        return id
    }

    weak var library: LibraryStore?
    private(set) var liveWanted = false
    private var pendingLive: Data?
    private var lastLiveSent = Date.distantPast

    /// App active: keep the channel open so the TV's progress arrives instantly.
    func startLive(library: LibraryStore) {
        self.library = library
        liveWanted = true
        if isAvailable { connect() }
    }

    func stopLive() { liveWanted = false; if !showRemote { disconnect() } }

    func publishLive(_ doc: SyncDoc) {
        guard let key, Self.channel != nil, let json = try? JSONEncoder().encode(doc),
              let sealed = try? AES.GCM.seal(json, using: key).combined else { return }
        let msg = Data(sealed.base64EncodedString().utf8)
        if connected, let c = client, let ch = Self.channel {
            c.publish("anixq/rc/\(ch)/live/\(Self.deviceId)", msg, retain: true)
        } else { pendingLive = msg; if liveWanted { connect() } }
    }

    private func receivedLive(_ data: Data) {
        guard let key, let b64 = String(data: data, encoding: .utf8), let sealed = Data(base64Encoded: b64),
              let box = try? AES.GCM.SealedBox(combined: sealed), let plain = try? AES.GCM.open(box, using: key),
              let doc = try? JSONDecoder().decode(SyncDoc.self, from: plain) else { return }
        if library?.importSync(doc) == true { DebugLog.log("live: merged changes from \(doc.device)") }
    }
    func reconnect() { let was = client != nil; disconnect(); if was { connect() } }

    private func received(_ data: Data) {
        guard let key, let b64 = String(data: data, encoding: .utf8), let sealed = Data(base64Encoded: b64.trimmingCharacters(in: .whitespaces)),
              let box = try? AES.GCM.SealedBox(combined: sealed), let plain = try? AES.GCM.open(box, using: key),
              let j = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return }
        tv = TVState(state: j["state"] as? String ?? "idle", title: j["title"] as? String ?? "", slug: j["slug"] as? String ?? "",
                     cover: (j["cover"] as? String).flatMap(URL.init(string:)), ep: j["ep"] as? String ?? "",
                     epName: j["epName"] as? String ?? "", team: j["team"] as? String ?? "",
                     pos: j["pos"] as? Double ?? 0, dur: j["dur"] as? Double ?? 0, hasNext: j["hasNext"] as? Bool ?? false,
                     hasPrev: j["hasPrev"] as? Bool ?? false, inOpening: j["inOpening"] as? Bool ?? false,
                     device: j["device"] as? String ?? "ТВ", at: j["at"] as? Double ?? 0)
    }

    // MARK: Commands

    func send(_ cmd: String, _ extra: [String: Any] = [:]) {
        guard let ch = Self.channel, let key else { return }
        var m = extra
        m["cmd"] = cmd
        m["id"] = UUID().uuidString
        m["at"] = Date().timeIntervalSince1970
        guard let json = try? JSONSerialization.data(withJSONObject: m),
              let sealed = try? AES.GCM.seal(json, using: key).combined else { return }
        let msg = Data(sealed.base64EncodedString().utf8)
        if connected, let c = client { c.publish("anixq/rc/\(ch)/cmd", msg) } else { pending.append(msg); connect() }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    /// Start an episode on the TV (optionally from an exact moment) and open the remote.
    func play(title: Title, episodeId: Int, teamId: Int?, position: Double?) {
        let t = (try? JSONEncoder().encode(title)).flatMap { try? JSONSerialization.jsonObject(with: $0) } ?? [:]
        var m: [String: Any] = ["title": t, "episodeId": episodeId]
        if let teamId { m["teamId"] = teamId }
        if let position { m["pos"] = position }
        send("play", m)
        showRemote = true
    }
}

// MARK: - Remote screen

struct RemoteTVView: View {
    @ObservedObject private var rc = RemoteTV.shared
    @Environment(\.dismiss) private var dismiss
    @State private var now = Date()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            VStack(spacing: 22) {
                if !rc.isAvailable {
                    ContentUnavailableHint()
                } else if let s = rc.tv, s.isActive {
                    nowPlaying(s)
                    controls(s)
                } else {
                    VStack(spacing: 10) {
                        Image(systemName: "tv").font(.system(size: 54)).foregroundStyle(.secondary)
                        Text("На телевизоре сейчас ничего не играет").font(.headline)
                        Text("Выберите серию и нажмите «Смотреть на ТВ» — она включится, где бы вы ни были.")
                            .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }.padding(.top, 40)
                }
                if rc.isAvailable { TVSearchField() }
                Spacer()
                HStack(spacing: 6) {
                    Circle().fill(rc.connected ? Color.green : Color.orange).frame(width: 8, height: 8)
                    Text(rc.connected ? "Связь с ТВ через интернет" : "Подключаемся…").font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding()
            .navigationTitle(rc.tv?.device.isEmpty == false ? rc.tv!.device : "Пульт ТВ")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
        }
        .onAppear { rc.connect(); rc.send("ping") }
        .onReceive(timer) { now = $0 }
    }

    /// Position extrapolated between the TV's 5-second updates.
    private func position(_ s: RemoteTV.TVState) -> Double {
        guard s.state == "playing" else { return s.pos }
        return min(s.dur, s.pos + max(0, now.timeIntervalSince1970 - s.at))
    }

    private func nowPlaying(_ s: RemoteTV.TVState) -> some View {
        VStack(spacing: 14) {
            AsyncImage(url: s.cover) { $0.resizable().scaledToFill() } placeholder: { Color.gray.opacity(0.2) }
                .frame(width: 150, height: 225).clipShape(RoundedRectangle(cornerRadius: 14))
                .shadow(radius: 18, y: 8)
            Text(s.title).font(.title3.bold()).multilineTextAlignment(.center).lineLimit(2)
            Text(["Серия \(s.ep)", s.epName.hasPrefix("Серия") ? nil : s.epName, s.team].compactMap { $0 }.filter { !$0.isEmpty }
                .joined(separator: " · ")).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            let p = position(s)
            VStack(spacing: 4) {
                ProgressView(value: s.dur > 0 ? p / s.dur : 0).tint(Theme.accent)
                HStack { Text(fmt(p)); Spacer(); Text(fmt(s.dur)) }.font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
        }
    }

    private func controls(_ s: RemoteTV.TVState) -> some View {
        VStack(spacing: 18) {
            HStack(spacing: 26) {
                circle("backward.end.fill", enabled: s.hasPrev) { rc.send("prev") }
                circle("gobackward.10") { rc.send("seek", ["by": -10]) }
                Button { rc.send("toggle") } label: {
                    Image(systemName: s.state == "playing" ? "pause.fill" : "play.fill").font(.system(size: 30, weight: .bold))
                        .frame(width: 76, height: 76).background(Circle().fill(Theme.accent)).foregroundStyle(.white)
                }
                circle("goforward.30") { rc.send("seek", ["by": 30]) }
                circle("forward.end.fill", enabled: s.hasNext) { rc.send("next") }
            }
            HStack(spacing: 12) {
                Button { rc.send("skip") } label: {
                    Label(s.inOpening ? "Пропустить опенинг" : "+85 с", systemImage: "forward.frame.fill").frame(maxWidth: .infinity)
                }.buttonStyle(.bordered)
                Button { rc.send("volume", ["dir": -1]) } label: { Image(systemName: "speaker.minus.fill").frame(width: 34) }.buttonStyle(.bordered)
                Button { rc.send("volume", ["dir": 1]) } label: { Image(systemName: "speaker.plus.fill").frame(width: 34) }.buttonStyle(.bordered)
            }
            Button(role: .destructive) { rc.send("stop") } label: { Label("Выключить на ТВ", systemImage: "stop.fill") }
                .font(.subheadline)
        }
    }

    private func circle(_ icon: String, enabled: Bool = true, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 20, weight: .semibold)).frame(width: 52, height: 52)
                .background(Circle().fill(Color.primary.opacity(0.08)))
        }.disabled(!enabled).opacity(enabled ? 1 : 0.35)
    }

    private func fmt(_ s: Double) -> String {
        let t = Int(max(0, s)); return t >= 3600 ? String(format: "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) : String(format: "%d:%02d", t / 60, t % 60)
    }
}

private struct ContentUnavailableHint: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "tv.and.mediabox").font(.system(size: 54)).foregroundStyle(.secondary)
            Text("Телевизор ещё не подключён").font(.headline)
            Text("Включите синхронизацию на iPhone (Настройки → Синхронизация устройств) и на ТВ (Настройки → Связать с iPhone). Через минуту пульт заработает — из любой сети и любого города.")
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }.padding(.top, 40)
    }
}


/// Type (or dictate with the keyboard's mic) what to find — the TV opens its search with it.
struct TVSearchField: View {
    @State private var text = ""
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("Найти на ТВ…", text: $text)
                .submitLabel(.search)
                .onSubmit {
                    let q = text.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !q.isEmpty else { return }
                    RemoteTV.shared.send("search", ["q": q])
                    text = ""
                }
        }
        .padding(12)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}
