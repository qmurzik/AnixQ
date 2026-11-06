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

    func publish(_ topic: String, _ payload: Data, retain: Bool = false) {
        guard connected else { return }
        var body = Self.string(topic)
        body.append(payload)
        send(Self.packet(retain ? 0x31 : 0x30, body))
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
    /// For browser viewers: direct stream, Kodik fallback, dub name.
    var stream: String?
    var kodik: String?
    var team: String?
    var web: Bool?
    /// chat / react / poll / vote
    var text: String?
    var emoji: String?
    var pollId: String?
    var question: String?
    var options: [String]?
    var option: Int?
}

struct RoomChatLine: Identifiable, Hashable {
    let id = UUID()
    let name: String
    let text: String
    let mine: Bool
    let date = Date()
}

struct RoomReaction: Identifiable, Hashable {
    let id = UUID()
    let emoji: String
    let x: CGFloat
}

struct RoomPoll: Identifiable {
    let id: String
    let question: String
    let options: [String]
    let creator: String
    var votes: [String: Int] = [:]
    var closed = false
    let started = Date()

    func count(_ i: Int) -> Int { votes.values.filter { $0 == i }.count }
    var winner: Int? {
        guard !votes.isEmpty else { return nil }
        return options.indices.max { count($0) < count($1) }
    }
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
    @Published private(set) var chat: [RoomChatLine] = []
    @Published private(set) var reactions: [RoomReaction] = []
    @Published private(set) var poll: RoomPoll?
    @Published var unread = 0

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
        chat = []
        poll = nil
        unread = 0
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

    // MARK: Chat, reactions, polls

    func sendChat(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        send(type: "chat") { $0.text = String(t.prefix(300)) }
        addChat(RoomChatLine(name: Self.displayName, text: t, mine: true))
    }

    func react(_ emoji: String) {
        send(type: "react") { $0.emoji = emoji }
        flyReaction(emoji)
    }

    static let nextEpisodePoll = ("Смотрим следующую серию?", ["Да, дальше", "Ещё 5 минут", "Хватит на сегодня"])

    func startPoll(question: String, options: [String]) {
        let id = UUID().uuidString.prefix(8).lowercased()
        poll = RoomPoll(id: String(id), question: question, options: options, creator: me)
        send(type: "poll") { $0.pollId = String(id); $0.question = question; $0.options = options }
        // Close after 30 s even if someone did not vote.
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in self?.closePoll(String(id)) }
    }

    func vote(_ option: Int) {
        guard var p = poll, !p.closed else { return }
        p.votes[me] = option
        poll = p
        send(type: "vote") { $0.pollId = p.id; $0.option = option }
        checkPoll()
    }

    func dismissPoll() { poll = nil }

    private func checkPoll() {
        guard let p = poll, !p.closed, p.creator == me else { return }
        // Everyone who is around has voted.
        if p.votes.count >= others + 1 { closePoll(p.id) }
    }

    private func closePoll(_ id: String) {
        guard var p = poll, p.id == id, !p.closed else { return }
        p.closed = true
        poll = p
        guard p.creator == me else { return }
        send(type: "poll") { $0.pollId = p.id; $0.question = p.question; $0.options = p.options; $0.option = p.winner ?? -1 }
        // The creator's player acts on "next episode".
        if p.question == Self.nextEpisodePoll.0, p.winner == 0, let c = controller, c.hasNext {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { c.next() }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
            if self?.poll?.id == id { self?.poll = nil }
        }
    }

    private func addChat(_ line: RoomChatLine) {
        chat.append(line)
        if chat.count > 60 { chat.removeFirst(chat.count - 60) }
    }

    private func flyReaction(_ emoji: String) {
        let r = RoomReaction(emoji: emoji, x: CGFloat.random(in: 0.55...0.92))
        reactions.append(r)
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in self?.reactions.removeAll { $0.id == r.id } }
    }

    private func send(type: String, _ extra: (inout RoomMessage) -> Void) {
        guard let topic, connected else { return }
        var m = RoomMessage(type: type, from: me, name: Self.displayName, sentAt: Date().timeIntervalSince1970)
        extra(&m)
        guard let data = try? JSONEncoder().encode(m) else { return }
        client.publish(topic, data)
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
            m.stream = c.webStreamURL?.absoluteString
            m.kodik = c.webKodikURL?.absoluteString
            m.team = c.teamName
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
        switch m.type {
        case "chat":
            if let t = m.text { addChat(RoomChatLine(name: m.name, text: t, mine: false)); unread += 1 }
            return
        case "react":
            if let e = m.emoji { flyReaction(String(e.prefix(4))) }
            return
        case "poll":
            guard let id = m.pollId, let q = m.question, let opts = m.options else { return }
            if let result = m.option, var p = poll, p.id == id {
                // Final result from the creator.
                p.closed = true
                poll = p
                _ = result
                DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in if self?.poll?.id == id { self?.poll = nil } }
            } else if poll?.id != id {
                poll = RoomPoll(id: id, question: q, options: opts, creator: m.from)
            }
            return
        case "vote":
            if var p = poll, p.id == m.pollId, let o = m.option {
                p.votes[m.from] = o
                poll = p
                checkPoll()
            }
            return
        default:
            break
        }
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
                    Button {
                        room.startPoll(question: WatchRoom.nextEpisodePoll.0, options: WatchRoom.nextEpisodePoll.1)
                    } label: { Label("Голосование: следующая серия?", systemImage: "checklist") }
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
            Text("Отправьте ссылку друзьям. Пауза, перемотка и смена серии будут общими — в AnixQ на iPhone и iPad или прямо в браузере, без FaceTime и установки.")
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

// MARK: - Room overlay in the player

struct RoomOverlay: View {
    @ObservedObject private var room = WatchRoom.shared
    @State private var open = false
    @State private var text = ""
    @FocusState private var focused: Bool
    static let emojis = ["😂", "😱", "😭", "🔥", "❤️", "👏", "🤯", "💀"]

    var body: some View {
        GeometryReader { geo in
            ZStack {
                // Flying reactions.
                ForEach(room.reactions) { r in
                    FloatingEmoji(emoji: r.emoji)
                        .position(x: geo.size.width * r.x, y: geo.size.height - 90)
                }
                // Recent chat lines, bottom-left.
                VStack(alignment: .leading, spacing: 4) {
                    Spacer()
                    ForEach(room.chat.suffix(open ? 6 : 3)) { line in
                        if open || Date().timeIntervalSince(line.date) < 12 {
                            (Text(line.name + ": ").bold() + Text(line.text))
                                .font(.footnote).foregroundStyle(.white)
                                .padding(.horizontal, 10).padding(.vertical, 5)
                                .background(.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
                                .transition(.opacity)
                        }
                    }
                    if open { inputBar }
                }
                .frame(maxWidth: min(geo.size.width * 0.55, 420), maxHeight: .infinity, alignment: .bottomLeading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 16).padding(.bottom, open ? 12 : 100)

                if let p = room.poll { pollCard(p).frame(maxHeight: .infinity, alignment: .top).padding(.top, 70) }

                // Chat button.
                if !open {
                    Button { open = true; room.unread = 0 } label: {
                        Image(systemName: room.unread > 0 ? "bubble.left.fill" : "bubble.left")
                            .font(.system(size: 18, weight: .semibold)).foregroundStyle(.white)
                            .frame(width: 44, height: 44).background(.black.opacity(0.4), in: Circle())
                            .overlay(alignment: .topTrailing) {
                                if room.unread > 0 {
                                    Text("\(room.unread)").font(.caption2.bold()).padding(4).background(.red, in: Circle()).foregroundStyle(.white)
                                }
                            }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .padding(.trailing, 16).padding(.bottom, 100)
                }
            }
        }
        .animation(.easeInOut(duration: 0.25), value: room.chat.count)
        .animation(.spring(response: 0.35), value: open)
    }

    private var inputBar: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                ForEach(Self.emojis, id: \.self) { e in
                    Button { room.react(e) } label: { Text(e).font(.title3) }
                }
            }
            HStack(spacing: 6) {
                TextField("Сообщение", text: $text)
                    .textFieldStyle(.plain).focused($focused)
                    .padding(.horizontal, 10).padding(.vertical, 7)
                    .background(.white.opacity(0.15), in: Capsule())
                    .foregroundStyle(.white)
                    .submitLabel(.send)
                    .onSubmit { room.sendChat(text); text = "" }
                Button { room.sendChat(text); text = "" } label: { Image(systemName: "paperplane.fill") }
                    .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
                Button { open = false; focused = false } label: { Image(systemName: "xmark.circle.fill") }
            }
            .foregroundStyle(.white)
        }
        .padding(10)
        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 14))
    }

    private func pollCard(_ p: RoomPoll) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label(p.question, systemImage: "checklist").font(.subheadline.bold())
                Spacer()
                Button { room.dismissPoll() } label: { Image(systemName: "xmark").font(.caption) }
            }
            ForEach(Array(p.options.enumerated()), id: \.offset) { i, o in
                Button { room.vote(i) } label: {
                    HStack {
                        Text(o).font(.subheadline)
                        Spacer()
                        Text("\(p.count(i))").font(.subheadline.monospacedDigit().bold())
                    }
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background((p.closed && p.winner == i ? Color.green : Color.white).opacity(p.closed && p.winner == i ? 0.5 : 0.15),
                                in: RoundedRectangle(cornerRadius: 10))
                }
                .disabled(p.closed)
            }
            if p.closed, let w = p.winner { Text("Решено: \(p.options[w])").font(.caption.bold()) }
        }
        .foregroundStyle(.white)
        .padding(14)
        .frame(maxWidth: 340)
        .background(.ultraThinMaterial.opacity(0.9), in: RoundedRectangle(cornerRadius: 16))
        .environment(\.colorScheme, .dark)
    }
}

private struct FloatingEmoji: View {
    let emoji: String
    @State private var up = false

    var body: some View {
        Text(emoji).font(.system(size: 40))
            .offset(y: up ? -220 : 0)
            .opacity(up ? 0 : 1)
            .scaleEffect(up ? 1.3 : 0.8)
            .onAppear { withAnimation(.easeOut(duration: 2.8)) { up = true } }
    }
}
