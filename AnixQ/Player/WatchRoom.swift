import SwiftUI
import UIKit

// MARK: - Minimal MQTT 3.1.1 over WebSocket (QoS 0)

/// Just enough MQTT to relay small JSON messages through a public broker: connect, subscribe,
/// publish, keep-alive. No account or own server needed.
final class MQTTClient: NSObject, URLSessionWebSocketDelegate {
    struct Broker {
        let url: URL
        let user: String?
        let password: String?
    }

    static let brokers = [
        Broker(url: URL(string: "wss://public.cloud.shiftr.io")!, user: "public", password: "public"),
        Broker(url: URL(string: "wss://broker.hivemq.com:8884/mqtt")!, user: nil, password: nil),
        Broker(url: URL(string: "wss://broker.emqx.io:8084/mqtt")!, user: nil, password: nil)
    ]

    var onMessage: ((String, Data) -> Void)?
    var onState: ((Bool) -> Void)?

    private var task: URLSessionWebSocketTask?
    private var session: URLSession?
    private var pingTimer: Timer?
    private var packetId: UInt16 = 1
    private let clientId = "anixq-" + UUID().uuidString.prefix(12)
    private var topics: [String] = []
    private(set) var connected = false

    func connect(_ broker: Broker) {
        disconnect()
        let s = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
        session = s
        var req = URLRequest(url: broker.url)
        req.setValue("mqtt", forHTTPHeaderField: "Sec-WebSocket-Protocol")
        let t = s.webSocketTask(with: req)
        task = t
        t.resume()
        send(connectPacket(user: broker.user, password: broker.password))
        receive()
    }

    func disconnect() {
        pingTimer?.invalidate()
        pingTimer = nil
        if connected { send(Data([0xE0, 0x00])) }
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        session?.invalidateAndCancel()
        session = nil
        if connected { connected = false; onState?(false) }
    }

    func clearSubscriptions() { topics = [] }

    func subscribe(_ topic: String) {
        if !topics.contains(topic) { topics.append(topic) }
        guard connected else { return }
        var body = Data()
        body.append(contentsOf: [UInt8(packetId >> 8), UInt8(packetId & 0xFF)])
        packetId &+= 1
        body.append(Self.string(topic))
        body.append(0)   // QoS 0
        send(Self.packet(0x82, body))
    }

    func publish(_ topic: String, _ payload: Data) {
        guard connected else { return }
        var body = Self.string(topic)
        body.append(payload)
        send(Self.packet(0x30, body))
    }

    // MARK: Packets

    private func connectPacket(user: String?, password: String?) -> Data {
        var vh = Self.string("MQTT")
        var flags: UInt8 = 0x02                     // clean session
        if user != nil { flags |= 0x80 }
        if password != nil { flags |= 0x40 }
        vh.append(contentsOf: [0x04, flags, 0x00, 30])   // level 4, keep-alive 30 s
        var payload = Self.string(clientId)
        if let user { payload.append(Self.string(user)) }
        if let password { payload.append(Self.string(password)) }
        return Self.packet(0x10, vh + payload)
    }

    private static func string(_ s: String) -> Data {
        let b = Data(s.utf8)
        return Data([UInt8(b.count >> 8), UInt8(b.count & 0xFF)]) + b
    }

    private static func packet(_ header: UInt8, _ body: Data) -> Data {
        var out = Data([header])
        var n = body.count
        repeat {
            var d = UInt8(n % 128)
            n /= 128
            if n > 0 { d |= 0x80 }
            out.append(d)
        } while n > 0
        return out + body
    }

    private func send(_ data: Data) {
        task?.send(.data(data)) { _ in }
    }

    private func receive() {
        task?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let msg):
                if case .data(let d) = msg { self.handle(d) }
                self.receive()
            case .failure:
                if self.connected { self.connected = false; self.onState?(false) }
            }
        }
    }

    /// One WebSocket frame may carry several MQTT packets.
    private func handle(_ data: Data) {
        var i = data.startIndex
        while i < data.endIndex {
            let header = data[i]
            var mult = 1, len = 0, j = i + 1
            while j < data.endIndex {
                let b = Int(data[j])
                len += (b & 0x7F) * mult
                mult *= 128
                j += 1
                if b & 0x80 == 0 { break }
            }
            guard j + len <= data.endIndex else { return }
            let body = data[j..<(j + len)]
            switch header >> 4 {
            case 2:   // CONNACK
                let ok = body.count >= 2 && body[body.startIndex + 1] == 0
                connected = ok
                onState?(ok)
                if ok {
                    topics.forEach(subscribe)
                    pingTimer?.invalidate()
                    pingTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
                        self?.send(Data([0xC0, 0x00]))
                    }
                }
            case 3:   // PUBLISH (QoS 0)
                guard body.count >= 2 else { break }
                let tl = Int(body[body.startIndex]) << 8 | Int(body[body.startIndex + 1])
                let ts = body.startIndex + 2
                guard ts + tl <= body.endIndex else { break }
                let topic = String(decoding: body[ts..<(ts + tl)], as: UTF8.self)
                onMessage?(topic, Data(body[(ts + tl)...]))
            default:
                break
            }
            i = j + len
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        if connected { connected = false; onState?(false) }
    }
}

// MARK: - Rooms

struct RoomMessage: Codable {
    var type: String            // join, state, leave
    var from: String
    var name: String
    var slug: String?
    var titleName: String?
    var episodeId: Int?
    var episodeNumber: String?
    var teamId: Int?
    var playing: Bool?
    var position: Double?
    var rate: Float?
    var sentAt: Double
}

/// "Комната по ссылке": everyone with the link watches the same episode; play, pause, seek and
/// episode changes are mirrored through a public MQTT broker. Works on any iPhone / iPad, no FaceTime.
@MainActor
final class WatchRoom: ObservableObject {
    static let shared = WatchRoom()

    @Published private(set) var roomId: String?
    @Published private(set) var connected = false
    @Published private(set) var participants: [String: (name: String, seen: Date)] = [:]
    @Published var message: String?
    /// A room state for an episode no player shows yet: RootView opens it.
    @Published var incoming: WatchTogether?

    private let client = MQTTClient()
    private let me = UUID().uuidString
    private weak var controller: PlayerController?
    private var brokerIndex = 0
    private var heartbeat: Timer?
    private var applyingRemote = false
    /// Joined by link: wait for the others' state instead of announcing our own position.
    private var needsSync = false
    private var lastSent = Date.distantPast

    var isActive: Bool { roomId != nil }
    var others: Int { participants.filter { Date().timeIntervalSince($0.value.seen) < 45 }.count }

    static var displayName: String {
        ShikiSession.nickname ?? LibSession.username ?? UIDevice.current.name
    }

    private var topic: String? { roomId.map { "anixq/rooms/\($0)" } }

    static func link(_ id: String) -> URL { URL(string: "https://qmurzik.github.io/AnixQ/room/#\(id)")! }

    init() {
        client.onState = { [weak self] ok in
            Task { @MainActor in self?.stateChanged(ok) }
        }
        client.onMessage = { [weak self] _, data in
            guard let m = try? JSONDecoder().decode(RoomMessage.self, from: data) else { return }
            Task { @MainActor in self?.received(m) }
        }
    }

    // MARK: Lifecycle

    /// New room around the episode that is playing now.
    func create(with c: PlayerController) -> URL {
        let alphabet = Array("abcdefghjkmnpqrstuvwxyz23456789")
        let id = String((0..<10).map { _ in alphabet.randomElement()! })
        controller = c
        open(id)
        needsSync = false
        return Self.link(id)
    }

    func join(_ id: String) {
        guard id != roomId else { return }
        open(id)
        needsSync = true
    }

    private func open(_ id: String) {
        leave(silently: true)
        roomId = id
        participants = [:]
        brokerIndex = 0
        connect()
        heartbeat = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.beat() }
        }
        DebugLog.log("room: open \(id)")
    }

    private func connect() {
        guard let topic else { return }
        client.subscribe(topic)
        client.connect(MQTTClient.brokers[brokerIndex % MQTTClient.brokers.count])
        // No answer in a few seconds: try the next broker.
        let attempt = brokerIndex
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in
            guard let self, self.roomId != nil, !self.connected, self.brokerIndex == attempt else { return }
            self.brokerIndex += 1
            if self.brokerIndex < MQTTClient.brokers.count * 2 { self.connect() } else {
                self.message = "Не удалось подключиться к серверу комнат. Проверьте интернет."
            }
        }
    }

    func leave(silently: Bool = false) {
        guard roomId != nil else { return }
        if !silently { send(type: "leave") }
        heartbeat?.invalidate()
        heartbeat = nil
        client.disconnect()
        client.clearSubscriptions()
        roomId = nil
        connected = false
        participants = [:]
        DebugLog.log("room: left")
    }

    private func stateChanged(_ ok: Bool) {
        connected = ok
        if ok {
            DebugLog.log("room: connected via broker \(brokerIndex % MQTTClient.brokers.count)")
            send(type: controller == nil || needsSync ? "join" : "state")
        } else if roomId != nil {
            // Dropped: reconnect shortly.
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                guard let self, self.roomId != nil, !self.connected else { return }
                self.connect()
            }
        }
    }

    // MARK: Player

    func attach(_ c: PlayerController) {
        controller = c
        if isActive { send(type: needsSync ? "join" : "state") }
    }

    func detach(_ c: PlayerController) {
        if controller === c { controller = nil }
    }

    /// Called by the player after a local play / pause / seek / episode change.
    func localAction(_ c: PlayerController) {
        guard isActive, controller === c, !applyingRemote, !needsSync else { return }
        send(type: "state")
    }

    private func beat() {
        guard isActive else { return }
        if let c = controller, c.isPlaying || Date().timeIntervalSince(lastSent) > 25 {
            send(type: "state")
        } else if controller == nil, Date().timeIntervalSince(lastSent) > 25 {
            send(type: "join")
        }
    }

    private func send(type: String) {
        guard let topic, connected else { return }
        var m = RoomMessage(type: type, from: me, name: Self.displayName, sentAt: Date().timeIntervalSince1970)
        if let c = controller, type == "state" {
            m.slug = c.title.slugURL
            m.titleName = c.title.title
            m.episodeId = c.episode.id
            m.episodeNumber = c.episode.number
            m.teamId = c.team.id
            m.playing = c.isPlaying || c.isBuffering
            m.position = c.currentTime
            m.rate = c.rate
        }
        guard let data = try? JSONEncoder().encode(m) else { return }
        lastSent = Date()
        client.publish(topic, data)
    }

    private func received(_ m: RoomMessage) {
        guard m.from != me else { return }
        if m.type == "leave" {
            participants[m.from] = nil
            return
        }
        participants[m.from] = (m.name, Date())
        if m.type == "join" {
            // Someone new: tell them what we are watching.
            if controller != nil && !needsSync { send(type: "state") }
            return
        }
        guard m.type == "state", let slug = m.slug, let eid = m.episodeId, let tid = m.teamId else { return }
        guard let c = controller, c.title.slugURL == slug else {
            incoming = WatchTogether(slug: slug, titleName: m.titleName ?? "", episodeId: eid,
                                     episodeNumber: m.episodeNumber ?? "", teamId: tid)
            return
        }
        needsSync = false
        applyingRemote = true
        defer { applyingRemote = false }
        if c.episode.id != eid || c.team.id != tid {
            c.apply(sharedEpisodeId: eid, teamId: tid)
            return
        }
        if let r = m.rate, r != c.rate { c.rate = r }
        let playing = m.playing ?? false
        var target = m.position ?? c.currentTime
        if playing { target += max(0, Date().timeIntervalSince1970 - m.sentAt) * Double(m.rate ?? 1) }
        if abs(c.currentTime - target) > 2 { c.seek(to: target) }
        if playing && !c.isPlaying && !c.isBuffering { c.togglePlay() }
        if !playing && (c.isPlaying || c.isBuffering) { c.togglePlay() }
    }
}

/// Player top-bar menu for rooms.
struct RoomMenu: View {
    @ObservedObject var c: PlayerController
    @ObservedObject private var room = WatchRoom.shared
    @State private var shareURL: URL?

    var body: some View {
        Menu {
            if room.isActive, let id = room.roomId {
                Section(room.connected ? "В комнате ещё: \(room.others)" : "Подключаемся…") {
                    ShareLink(item: WatchRoom.link(id)) { Label("Пригласить по ссылке", systemImage: "link") }
                    Button(role: .destructive) { room.leave() } label: {
                        Label("Выйти из комнаты", systemImage: "rectangle.portrait.and.arrow.right")
                    }
                }
            } else {
                Button { shareURL = room.create(with: c) } label: {
                    Label("Создать комнату", systemImage: "person.2.badge.plus")
                }
            }
        } label: {
            Image(systemName: room.isActive ? "person.2.fill" : "person.2")
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 43, height: 43)
                .foregroundStyle(room.isActive && room.connected ? Color.green : Color.white)
        }
        .sheet(item: Binding(get: { shareURL.map(IdentifiedURL.init) }, set: { shareURL = $0?.url })) { item in
            RoomInviteSheet(url: item.url)
        }
    }
}

struct IdentifiedURL: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

struct RoomInviteSheet: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "person.2.wave.2.fill").font(.system(size: 44)).foregroundStyle(Theme.accent)
            Text("Комната создана").font(.title2.bold())
            Text("Отправьте ссылку друзьям. Пауза, перемотка и смена серии будут общими — на iPhone и iPad, без FaceTime.")
                .multilineTextAlignment(.center).foregroundStyle(.secondary)
            Text(url.absoluteString).font(.footnote.monospaced()).padding(10).frame(maxWidth: .infinity)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 10))
            ShareLink(item: url) { Label("Отправить ссылку", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity) }
                .buttonStyle(PrimaryButtonStyle())
            Button("Готово") { dismiss() }
        }
        .padding(24)
        .presentationDetents([.medium])
        .preferredColorScheme(.dark)
    }
}
