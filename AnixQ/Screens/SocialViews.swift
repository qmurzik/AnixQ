import SwiftUI
import UniformTypeIdentifiers

// MARK: - Shikimori: friends, history, season

struct ShikiFriend: Identifiable, Hashable {
    let id: Int
    let nickname: String
    let avatar: URL?
}

struct ShikiHistoryEntry: Identifiable, Hashable {
    let id: Int
    let date: Date
    let text: String
    let shikiId: Int?
    let name: String
    let russian: String?
    let image: URL?
    var user: ShikiFriend?

    var titleName: String { russian?.nilIfEmpty ?? name }
}

struct SeasonAnime: Identifiable, Hashable {
    let id: Int
    let name: String
    let russian: String?
    let kind: String
    let score: Double?
    let poster: URL?
    let studios: [String]
    let genres: [String]
    let episodes: Int
    let episodesAired: Int
    let nextEpisodeAt: Date?
    let airedOn: Date?

    var title: String { russian?.nilIfEmpty ?? name }
    /// 1 = Monday … 7 = Sunday: from the next episode for ongoings, otherwise from the premiere date.
    var weekday: Int? {
        guard let d = nextEpisodeAt ?? airedOn else { return nil }
        let w = Calendar(identifier: .gregorian).component(.weekday, from: d)
        return w == 1 ? 7 : w - 1
    }
}

extension ShikiAPI {
    static func absolute(_ path: String?) -> URL? {
        guard let path, !path.isEmpty else { return nil }
        return path.hasPrefix("http") ? URL(string: path) : URL(string: host + path)
    }

    static func friends(of userId: Int) async throws -> [ShikiFriend] {
        let json = try await request("/api/users/\(userId)/friends?limit=100", auth: false)
        return (json as? [JSON] ?? []).compactMap { x in
            guard let id = x.int("id") else { return nil }
            return ShikiFriend(id: id, nickname: x.str("nickname") ?? "—",
                               avatar: absolute(x.obj("image")?.str("x80") ?? x.str("avatar")))
        }
    }

    static func history(of user: ShikiFriend, limit: Int = 15) async -> [ShikiHistoryEntry] {
        guard let json = try? await request("/api/users/\(user.id)/history?limit=\(limit)", auth: false) else { return [] }
        return (json as? [JSON] ?? []).compactMap { x in
            guard let id = x.int("id"), let date = parseDate(x.str("created_at")) else { return nil }
            let t = x.obj("target")
            // Only anime entries (manga and ranobe share this feed).
            if let url = t?.str("url"), !url.contains("/animes/") { return nil }
            return ShikiHistoryEntry(id: id, date: date, text: plainText(x.str("description") ?? ""),
                                     shikiId: t?.int("id"), name: t?.str("name") ?? "", russian: t?.str("russian"),
                                     image: absolute(t?.obj("image")?.str("preview")), user: user)
        }
    }

    static func watchingList(of userId: Int) async -> [(shikiId: Int, name: String, russian: String?, episodes: Int, image: URL?)] {
        guard let json = try? await request("/api/users/\(userId)/anime_rates?status=watching&limit=60", auth: false) else { return [] }
        return (json as? [JSON] ?? []).compactMap { x in
            guard let a = x.obj("anime"), let id = a.int("id") else { return nil }
            return (id, a.str("name") ?? "", a.str("russian"), x.int("episodes") ?? 0,
                    absolute(a.obj("image")?.str("preview")))
        }
    }

    /// One GraphQL request returns everything the season page needs, studios included.
    static func season(_ season: String) async throws -> [SeasonAnime] {
        let query = """
        { animes(season: "\(season)", limit: 50, order: popularity, kind: "tv,ona,movie,ova") {
            id name russian kind score status episodes episodesAired nextEpisodeAt
            airedOn { date } poster { mainUrl } studios { name } genres { russian } } }
        """
        let json = try await request("/api/graphql", method: "POST", json: ["query": query], auth: false) as? JSON
        let items = json?.obj("data")?.arr("animes") ?? []
        let day = DateFormatter()
        day.dateFormat = "yyyy-MM-dd"
        return items.compactMap { x in
            guard let id = x.int("id") ?? x.str("id").flatMap(Int.init) else { return nil }
            return SeasonAnime(id: id, name: x.str("name") ?? "", russian: x.str("russian"), kind: x.str("kind") ?? "",
                               score: x.double("score"), poster: absolute(x.obj("poster")?.str("mainUrl")),
                               studios: x.arr("studios").compactMap { $0.str("name") },
                               genres: x.arr("genres").compactMap { $0.str("russian") },
                               episodes: x.int("episodes") ?? 0, episodesAired: x.int("episodesAired") ?? 0,
                               nextEpisodeAt: parseDate(x.str("nextEpisodeAt")),
                               airedOn: x.obj("airedOn")?.str("date").flatMap { day.date(from: $0) })
        }
    }

    static func plainText(_ html: String) -> String {
        html.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&#39;", with: "'").replacingOccurrences(of: "&nbsp;", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: Friends

struct FriendsView: View {
    @State private var friends: [ShikiFriend] = []
    @State private var feed: [ShikiHistoryEntry] = []
    @State private var tab = 0
    @State private var loading = false
    @State private var error: String?

    var body: some View {
        List {
            if !ShikiSession.isLoggedIn || ShikiSession.userId == nil {
                EmptyState(icon: "person.2", title: "Войдите в Shikimori",
                           subtitle: "Настройки → Аккаунты → Подключить Shikimori. Здесь появится, что смотрят ваши друзья.")
            } else {
                Picker("", selection: $tab) {
                    Text("Лента").tag(0)
                    Text("Друзья (\(friends.count))").tag(1)
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())

                if loading && feed.isEmpty { HStack { Spacer(); ProgressView(); Spacer() } }
                if let error { Text(error).foregroundStyle(.secondary) }

                if tab == 0 {
                    if !loading && feed.isEmpty && error == nil {
                        Text(friends.isEmpty ? "У вас пока нет друзей на Shikimori." : "Друзья давно ничего не отмечали.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(feed) { e in HistoryRow(entry: e, showUser: true) }
                } else {
                    ForEach(friends) { f in
                        NavigationLink(value: f) {
                            HStack(spacing: 12) {
                                Avatar(url: f.avatar, size: 40)
                                Text(f.nickname).font(.body.weight(.medium))
                            }
                        }
                    }
                }
            }
        }
        .styledList()
        .navigationTitle("Друзья")
        .navigationDestination(for: ShikiFriend.self) { FriendProfileView(friend: $0) }
        .task { if feed.isEmpty { await load() } }
        .refreshable { await load() }
    }

    private func load() async {
        guard let uid = ShikiSession.userId else { return }
        loading = true
        defer { loading = false }
        do {
            friends = try await ShikiAPI.friends(of: uid)
            error = nil
        } catch {
            self.error = "Не удалось загрузить друзей: \(error.localizedDescription)"
            return
        }
        let all = await withTaskGroup(of: [ShikiHistoryEntry].self) { group in
            for f in friends.prefix(25) { group.addTask { await ShikiAPI.history(of: f, limit: 10) } }
            var out: [ShikiHistoryEntry] = []
            for await part in group { out += part }
            return out
        }
        feed = Array(all.sorted { $0.date > $1.date }.prefix(120))
    }
}

struct FriendProfileView: View {
    let friend: ShikiFriend
    @State private var history: [ShikiHistoryEntry] = []
    @State private var watching: [(shikiId: Int, name: String, russian: String?, episodes: Int, image: URL?)] = []

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    Avatar(url: friend.avatar, size: 64)
                    VStack(alignment: .leading) {
                        Text(friend.nickname).font(.title3.bold())
                        Link("Профиль на Shikimori", destination: URL(string: "\(ShikiAPI.host)/\(friend.nickname.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? "")")!)
                            .font(.footnote)
                    }
                }
            }
            if !watching.isEmpty {
                Section("Смотрит сейчас") {
                    ForEach(watching, id: \.shikiId) { w in
                        NavigationLink(value: ShikiRoute(shikiId: w.shikiId, names: [w.name, w.russian ?? ""])) {
                            HStack(spacing: 12) {
                                PosterImage(url: w.image, radius: 6).frame(width: 40)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(w.russian?.nilIfEmpty ?? w.name).font(.subheadline.weight(.semibold)).lineLimit(2)
                                    Text("Просмотрено серий: \(w.episodes)").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            Section("Недавно") {
                if history.isEmpty { ProgressView() }
                ForEach(history) { HistoryRow(entry: $0, showUser: false) }
            }
        }
        .styledList()
        .navigationTitle(friend.nickname)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            async let h = ShikiAPI.history(of: friend, limit: 30)
            async let w = ShikiAPI.watchingList(of: friend.id)
            history = await h
            watching = await w
        }
    }
}

private struct HistoryRow: View {
    let entry: ShikiHistoryEntry
    let showUser: Bool

    var body: some View {
        let content = HStack(alignment: .top, spacing: 12) {
            PosterImage(url: entry.image, radius: 6).frame(width: 44)
            VStack(alignment: .leading, spacing: 3) {
                if showUser, let u = entry.user {
                    HStack(spacing: 6) {
                        Avatar(url: u.avatar, size: 18)
                        Text(u.nickname).font(.caption.weight(.semibold))
                        Text(entry.date, style: .relative).font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Text(entry.titleName).font(.subheadline.weight(.semibold)).lineLimit(2)
                Text(entry.text).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
        }
        if let id = entry.shikiId {
            NavigationLink(value: ShikiRoute(shikiId: id, names: [entry.name, entry.russian ?? ""])) { content }
        } else {
            content
        }
    }
}

struct Avatar: View {
    let url: URL?
    let size: CGFloat

    var body: some View {
        Circle().fill(Theme.card)
            .overlay { RemoteImage(url: url) }
            .clipShape(Circle())
            .frame(width: size, height: size)
    }
}

// MARK: Season

struct SeasonView: View {
    @State private var season = SeasonView.current
    @State private var items: [SeasonAnime] = []
    @State private var loading = false
    @State private var error: String?
    @State private var day = 0          // 0 = all, 1…7 = Mon…Sun
    @State private var studio: String?
    @State private var kind: String?

    private let columns = [GridItem(.adaptive(minimum: 105, maximum: 170), spacing: 12, alignment: .top)]
    private static let days = ["Все", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"]

    static var current: String {
        let c = Calendar.current.dateComponents([.year, .month], from: Date())
        let names = ["winter", "winter", "spring", "spring", "spring", "summer", "summer", "summer", "fall", "fall", "fall", "winter"]
        let month = c.month ?? 1
        // December belongs to the next year's winter season.
        return "\(names[month - 1])_\((c.year ?? 2026) + (month == 12 ? 1 : 0))"
    }

    private static func shift(_ s: String, by delta: Int) -> String {
        let order = ["winter", "spring", "summer", "fall"]
        let parts = s.split(separator: "_")
        guard parts.count == 2, let i = order.firstIndex(of: String(parts[0])), let y = Int(parts[1]) else { return s }
        let n = i + delta
        let year = y + Int(floor(Double(n) / 4))
        return "\(order[((n % 4) + 4) % 4])_\(year)"
    }

    static func title(_ s: String) -> String {
        let names = ["winter": "Зима", "spring": "Весна", "summer": "Лето", "fall": "Осень"]
        let parts = s.split(separator: "_")
        return "\(names[String(parts.first ?? "")] ?? "") \(parts.last ?? "")"
    }

    private var filtered: [SeasonAnime] {
        items.filter { a in
            (day == 0 || a.weekday == day) && (studio == nil || a.studios.contains(studio!)) && (kind == nil || a.kind == kind)
        }
    }

    private var studios: [String] {
        let counts = items.flatMap(\.studios).reduce(into: [String: Int]()) { $0[$1, default: 0] += 1 }
        return counts.sorted { $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value }.map(\.key)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Button { change(-1) } label: { Image(systemName: "chevron.left") }
                    Spacer()
                    Text(Self.title(season)).font(.title3.bold())
                    Spacer()
                    Button { change(1) } label: { Image(systemName: "chevron.right") }
                }
                .padding(.horizontal)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(0..<8) { i in
                            chip(Self.days[i], on: day == i) { day = i }
                        }
                        Divider().frame(height: 22)
                        Menu {
                            Button("Все студии") { studio = nil }
                            ForEach(studios, id: \.self) { s in Button(s) { studio = s } }
                        } label: {
                            chipLabel(studio ?? "Студия", on: studio != nil, icon: "building.2")
                        }
                        Menu {
                            Button("Все типы") { kind = nil }
                            ForEach([("tv", "TV"), ("movie", "Фильмы"), ("ona", "ONA"), ("ova", "OVA")], id: \.0) { k in
                                Button(k.1) { kind = k.0 }
                            }
                        } label: {
                            chipLabel(kind.map { $0.uppercased() } ?? "Тип", on: kind != nil, icon: "film")
                        }
                    }
                    .padding(.horizontal)
                }

                if loading && items.isEmpty {
                    ProgressView().frame(maxWidth: .infinity).padding(40)
                } else if let error {
                    ErrorState(message: error) { Task { await load() } }
                } else if filtered.isEmpty {
                    EmptyState(icon: "calendar", title: "Ничего не нашлось", subtitle: "Попробуйте другой день или студию")
                }

                LazyVGrid(columns: columns, spacing: 18) {
                    ForEach(filtered) { a in
                        NavigationLink(value: ShikiRoute(shikiId: a.id, names: [a.name, a.russian ?? ""])) {
                            VStack(alignment: .leading, spacing: 5) {
                                PosterImage(url: a.poster)
                                    .overlay(alignment: .topTrailing) {
                                        if let s = a.score, s > 0 { ScoreBadge(score: s).padding(6) }
                                    }
                                Text(a.title).font(.footnote.weight(.semibold)).lineLimit(2, reservesSpace: true)
                                Text(([a.studios.first, a.weekday.map { Self.days[$0] }].compactMap { $0 }).joined(separator: " · "))
                                    .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                        .buttonStyle(PressableStyle())
                    }
                }
                .padding(.horizontal)
            }
            .padding(.vertical)
        }
        .styledBackground()
        .navigationTitle("Сезон")
        .navigationBarTitleDisplayMode(.inline)
        .task { if items.isEmpty { await load() } }
        .refreshable { await load() }
    }

    private func change(_ d: Int) {
        season = Self.shift(season, by: d)
        studio = nil
        Task { await load() }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            items = try await ShikiAPI.season(season)
            error = nil
        } catch {
            self.error = "Shikimori не ответил: \(error.localizedDescription)"
        }
    }

    private func chip(_ text: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { chipLabel(text, on: on, icon: nil) }.buttonStyle(.plain)
    }

    private func chipLabel(_ text: String, on: Bool, icon: String?) -> some View {
        HStack(spacing: 4) {
            if let icon { Image(systemName: icon).font(.caption) }
            Text(text).font(.subheadline.weight(.medium))
        }
        .padding(.horizontal, 12).padding(.vertical, 7)
        .background(on ? Theme.accent.opacity(0.9) : Theme.card, in: Capsule())
        .foregroundStyle(on ? Color.black.opacity(0.85) : .primary)
    }
}

// MARK: - AnimeLib comments

struct LibComment: Identifiable, Hashable {
    let id: Int
    let rootId: Int?
    let text: String
    let date: Date
    let user: String
    let avatar: URL?
    let up: Int
    let down: Int
    let spoiler: Bool
}

extension LibAPI {
    /// `type`: "episodes" for one episode, "anime" for the title page.
    static func comments(postId: Int, type: String, page: Int) async throws -> (root: [LibComment], replies: [LibComment], hasMore: Bool) {
        let json = try await request("/comments", query: [
            URLQueryItem(name: "post_id", value: String(postId)),
            URLQueryItem(name: "post_type", value: type),
            URLQueryItem(name: "page", value: String(page))
        ], auth: false) as? JSON ?? [:]
        let data = json.obj("data") ?? [:]
        func parse(_ x: JSON) -> LibComment? {
            guard let id = x.int("id") else { return nil }
            let html = x.str("comment") ?? ""
            let user = x.obj("user")
            let ts = (x["created_at_ts"] as? Double).map { Date(timeIntervalSince1970: $0 / 1000) } ?? Date()
            return LibComment(id: id, rootId: x.int("root_id"), text: ShikiAPI.plainText(html.replacingOccurrences(of: "</p><p>", with: "\n")),
                              date: ts, user: user?.str("username") ?? "—",
                              avatar: user?.obj("avatar")?.str("url").flatMap(URL.init(string:)),
                              up: x.obj("votes")?.int("up") ?? 0, down: x.obj("votes")?.int("down") ?? 0,
                              spoiler: html.contains("spoiler"))
        }
        let more = (json.obj("meta")?["has_next_page"] as? Bool) ?? false
        return (data.arr("root").compactMap(parse), data.arr("replies").compactMap(parse), more)
    }
}

struct CommentsView: View {
    let postId: Int
    let type: String
    let title: String
    @State private var root: [LibComment] = []
    @State private var replies: [Int: [LibComment]] = [:]
    @State private var page = 1
    @State private var hasMore = true
    @State private var loading = false
    @State private var error: String?
    @State private var revealed: Set<Int> = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if let error { Text(error).foregroundStyle(.secondary) }
                if !loading && root.isEmpty && error == nil {
                    EmptyState(icon: "bubble.left.and.bubble.right", title: "Комментариев пока нет")
                }
                ForEach(root) { c in
                    VStack(alignment: .leading, spacing: 10) {
                        row(c)
                        ForEach(replies[c.id] ?? []) { r in
                            row(r).padding(.leading, 26)
                        }
                    }
                    .onAppear { if c.id == root.last?.id { Task { await loadMore() } } }
                }
                if loading { HStack { Spacer(); ProgressView(); Spacer() } }
            }
            .listStyle(.plain)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
            .task { if root.isEmpty { await loadMore() } }
            .refreshable {
                root = []; replies = [:]; page = 1; hasMore = true
                await loadMore()
            }
        }
    }

    private func row(_ c: LibComment) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Avatar(url: c.avatar, size: 30)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(c.user).font(.caption.weight(.semibold))
                    Text(c.date, style: .relative).font(.caption2).foregroundStyle(.secondary)
                }
                if c.spoiler && !revealed.contains(c.id) {
                    Button { revealed.insert(c.id) } label: {
                        Label("Спойлер — показать", systemImage: "eye.slash").font(.subheadline)
                    }
                    .buttonStyle(.borderless)
                } else {
                    Text(c.text).font(.subheadline).fixedSize(horizontal: false, vertical: true)
                }
                if c.up + c.down > 0 {
                    HStack(spacing: 10) {
                        Label("\(c.up)", systemImage: "hand.thumbsup").foregroundStyle(.green)
                        Label("\(c.down)", systemImage: "hand.thumbsdown").foregroundStyle(.secondary)
                    }
                    .font(.caption2)
                }
            }
        }
    }

    private func loadMore() async {
        guard hasMore, !loading else { return }
        loading = true
        defer { loading = false }
        do {
            let r = try await LibAPI.comments(postId: postId, type: type, page: page)
            let known = Set(root.map(\.id))
            root += r.root.filter { !known.contains($0.id) }
            for reply in r.replies {
                guard let rid = reply.rootId else { continue }
                if !(replies[rid] ?? []).contains(where: { $0.id == reply.id }) { replies[rid, default: []].append(reply) }
            }
            hasMore = r.hasMore
            page += 1
            error = nil
        } catch {
            self.error = "Не удалось загрузить комментарии: \(error.localizedDescription)"
            hasMore = false
        }
    }
}

// MARK: - MyAnimeList XML export / import

enum MALExport {
    private static func malStatus(_ s: BookmarkStatus) -> String {
        switch s {
        case .watching, .rewatching, .favorite: return "Watching"
        case .planned: return "Plan to Watch"
        case .completed: return "Completed"
        case .dropped: return "Dropped"
        case .onHold: return "On-Hold"
        }
    }

    /// Standard MAL export format, importable by MyAnimeList, Shikimori, AniList and others.
    @MainActor
    static func file(library: LibraryStore) throws -> URL {
        var rows: [String] = []
        for b in library.bookmarks.values.sorted(by: { $0.date > $1.date }) {
            guard let sid = b.title.shikiId else { continue }
            let watched = library.watched[b.title.id]?.count ?? 0
            rows.append("""
              <anime>
                <series_animedb_id>\(sid)</series_animedb_id>
                <series_title><![CDATA[\(b.title.originalTitle ?? b.title.title)]]></series_title>
                <my_watched_episodes>\(watched)</my_watched_episodes>
                <my_score>0</my_score>
                <my_status>\(malStatus(b.status))</my_status>
                <update_on_import>1</update_on_import>
              </anime>
            """)
        }
        let xml = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <myanimelist>
          <myinfo>
            <user_export_type>1</user_export_type>
            <user_total_anime>\(rows.count)</user_total_anime>
          </myinfo>
        \(rows.joined(separator: "\n"))
        </myanimelist>
        """
        let date = Date().formatted(.iso8601.year().month().day())
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("AnixQ-animelist-\(date).xml")
        try xml.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    /// Parses a MAL (or Shikimori) XML export into rates for LibraryStore.importRates.
    static func parse(_ data: Data) -> [ShikiAPI.RemoteRate] {
        final class P: NSObject, XMLParserDelegate {
            var rates: [ShikiAPI.RemoteRate] = []
            var cur: [String: String] = [:]
            var text = ""
            func parser(_ p: XMLParser, didStartElement e: String, namespaceURI: String?, qualifiedName: String?,
                        attributes: [String: String] = [:]) {
                if e == "anime" { cur = [:] }
                text = ""
            }
            func parser(_ p: XMLParser, foundCharacters s: String) { text += s }
            func parser(_ p: XMLParser, foundCDATA d: Data) { text += String(decoding: d, as: UTF8.self) }
            func parser(_ p: XMLParser, didEndElement e: String, namespaceURI: String?, qualifiedName: String?) {
                if e == "anime" {
                    if let id = cur["series_animedb_id"].flatMap({ Int($0) }) {
                        let status: String
                        switch (cur["my_status"] ?? "").lowercased() {
                        case "watching", "1": status = "watching"
                        case "completed", "2": status = "completed"
                        case "on-hold", "3": status = "on_hold"
                        case "dropped", "4": status = "dropped"
                        case "rewatching": status = "rewatching"
                        default: status = "planned"
                        }
                        rates.append(.init(shikiId: id, name: cur["series_title"] ?? "", russian: nil, status: status,
                                           episodes: Int(cur["my_watched_episodes"] ?? "") ?? 0,
                                           score: Int(cur["my_score"] ?? "") ?? 0))
                    }
                } else {
                    cur[e] = text.trimmingCharacters(in: .whitespacesAndNewlines)
                }
            }
        }
        let p = P()
        let parser = XMLParser(data: data)
        parser.delegate = p
        parser.parse()
        return p.rates
    }
}

struct ListTransferView: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var exportURL: URL?
    @State private var importing = false
    @State private var message: String?

    var body: some View {
        Form {
            Section {
                if let exportURL {
                    ShareLink(item: exportURL) { Label("Поделиться файлом", systemImage: "square.and.arrow.up") }
                } else {
                    Button { makeExport() } label: { Label("Создать файл экспорта", systemImage: "doc.badge.arrow.up") }
                }
            } header: {
                Text("Экспорт")
            } footer: {
                Text("Формат MyAnimeList XML — его принимают MyAnimeList, Shikimori (Настройки → Импорт), AniList и другие.")
            }
            Section {
                Button { importing = true } label: { Label("Выбрать XML-файл", systemImage: "doc.badge.arrow.down") }
                    .disabled(library.shikiImport != nil)
                if let p = library.shikiImport {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Импорт: \(p.done) из \(p.total)").font(.subheadline)
                        ProgressView(value: Double(p.done), total: Double(max(p.total, 1)))
                    }
                } else if let summary = library.shikiImportSummary {
                    Text(summary).font(.footnote).foregroundStyle(.secondary)
                }
                if let message { Text(message).font(.footnote).foregroundStyle(.secondary) }
            } header: {
                Text("Импорт")
            } footer: {
                Text("Экспорт из MyAnimeList или Shikimori (XML; .xml.gz сначала распакуйте в «Файлах»). Тайтлы ищутся на AnimeLib и добавляются в закладки, просмотренные серии отмечаются.")
            }
        }
        .styledList()
        .navigationTitle("Экспорт и импорт")
        .fileImporter(isPresented: $importing, allowedContentTypes: [.xml, .data]) { result in
            guard case .success(let url) = result else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else { message = "Не удалось прочитать файл"; return }
            let rates = MALExport.parse(data)
            guard !rates.isEmpty else { message = "В файле не найдено аниме"; return }
            message = nil
            Task { await library.importRates(rates, source: "Импорт XML") }
        }
    }

    private func makeExport() {
        do { exportURL = try MALExport.file(library: library) } catch { message = error.localizedDescription }
    }
}
