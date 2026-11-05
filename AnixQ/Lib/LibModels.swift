import Foundation

// MARK: - JSON helpers

typealias JSON = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func str(_ k: String) -> String? {
        if let s = self[k] as? String, !s.isEmpty { return s }
        if let i = self[k] as? Int { return String(i) }
        return nil
    }
    func int(_ k: String) -> Int? {
        if let i = self[k] as? Int { return i }
        if let s = self[k] as? String { return Int(s) }
        if let d = self[k] as? Double { return Int(d) }
        return nil
    }
    func double(_ k: String) -> Double? {
        if let d = self[k] as? Double { return d }
        if let i = self[k] as? Int { return Double(i) }
        if let s = self[k] as? String { return Double(s) }
        return nil
    }
    func obj(_ k: String) -> JSON? { self[k] as? JSON }
    func arr(_ k: String) -> [JSON] { self[k] as? [JSON] ?? [] }
}

// MARK: - Title (catalog item)

struct Title: Identifiable, Hashable, Codable {
    let id: Int
    let slugURL: String
    let rusName: String?
    let name: String?
    let engName: String?
    let cover: String?
    let coverThumb: String?
    let rating: Double?
    let votes: Int?
    let shikiRate: Double?
    let shikiId: Int?
    let typeLabel: String?
    let statusId: Int?
    let statusLabel: String?
    let year: Int?
    let age: String?
    /// Optional so libraries saved by older versions still decode.
    var anilistId: Int?

    init?(json x: JSON) {
        guard let id = x.int("id"), let slug = x.str("slug_url") else { return nil }
        self.id = id
        slugURL = slug
        rusName = x.str("rus_name")
        name = x.str("name")
        engName = x.str("eng_name")
        cover = x.obj("cover")?.str("default")
        coverThumb = x.obj("cover")?.str("thumbnail")
        rating = x.obj("rating")?.double("average")
        votes = x.obj("rating")?.int("votes")
        shikiRate = x.double("shiki_rate")
        shikiId = x.str("shikimori_href").flatMap { href in
            href.split(separator: "/").last.flatMap { Int($0.prefix { $0.isNumber }) }
        }
        typeLabel = x.obj("type")?.str("label")
        statusId = x.obj("status")?.int("id")
        statusLabel = x.obj("status")?.str("label")
        year = x.str("releaseDateString").flatMap { s in
            s.split(separator: " ").compactMap { Int($0) }.first { $0 > 1900 }
        }
        age = x.obj("ageRestriction")?.str("label")
        anilistId = x.int("anilist_id")
    }

    var title: String { rusName ?? name ?? engName ?? "Без названия" }
    var originalTitle: String? { name ?? engName }
    var coverURL: URL? { (cover ?? coverThumb).flatMap(URL.init(string:)) }
    var thumbURL: URL? { (coverThumb ?? cover).flatMap(URL.init(string:)) }
    var isOngoing: Bool { statusId == 1 }

    /// Rating on a 10-point scale (AnimeLib users, then Shikimori).
    var score: Double? {
        if let r = rating, r > 0 { return r }
        if let s = shikiRate, s > 0 { return s }
        return nil
    }

    var metaLine: String {
        [typeLabel, year.map(String.init), statusLabel].compactMap { $0 }.joined(separator: " · ")
    }
}

// MARK: - Title details

struct Genre: Identifiable, Hashable, Codable {
    let id: Int
    let name: String
}

struct TitleDetail {
    let title: Title
    let summary: String?
    let background: String?
    let genres: [Genre]
    let tags: [String]
    let otherNames: [String]
    let duration: String?
    let studios: [String]
    let authors: [String]
    let views: String?
    let releaseDate: String?

    var backgroundURL: URL? { background.flatMap(URL.init(string:)) }

    init?(json x: JSON) {
        guard let t = Title(json: x) else { return nil }
        title = t
        summary = TitleDetail.flatten(x["summary"]).trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        background = LibAPI.realImage(x.obj("background")?.str("url"))
        genres = x.arr("genres").compactMap { g in
            guard let id = g.int("id"), let n = g.str("name") else { return nil }
            return Genre(id: id, name: n)
        }
        tags = x.arr("tags").compactMap { $0.str("name") }
        otherNames = x["otherNames"] as? [String] ?? []
        duration = x.obj("time")?.str("formated")
        studios = x.arr("publisher").compactMap { $0.str("rus_name") ?? $0.str("name") }
        authors = x.arr("authors").compactMap { $0.str("rus_name") ?? $0.str("name") }
        views = x.obj("views")?.str("short")
        releaseDate = x.str("releaseDate")
    }

    /// AnimeLib stores descriptions as a ProseMirror-like document tree (or plain text).
    static func flatten(_ node: Any?) -> String {
        if let s = node as? String { return s }
        guard let n = node as? JSON else { return "" }
        if let text = n["text"] as? String { return text }
        let children: [String] = (n["content"] as? [Any] ?? []).map { TitleDetail.flatten($0) }
        let joined = children.joined(separator: "")
        return (n["type"] as? String) == "paragraph" ? joined + "\n\n" : joined
    }
}

struct RelatedTitle: Identifiable, Hashable {
    let title: Title
    let relation: String
    var id: Int { title.id }
}

// MARK: - Episodes & players

struct LibEpisode: Identifiable, Hashable, Codable {
    let id: Int
    let number: String
    let season: String?
    let name: String?
    let index: Int

    var displayTitle: String {
        if let n = name, !n.isEmpty, n != number { return n }
        return "Серия \(number)"
    }
    var numberValue: Double { Double(number) ?? Double(index + 1) }
}

struct LibTeam: Hashable, Codable, Identifiable {
    let id: Int
    let name: String
    let isSubtitles: Bool
}

struct LibSubtitle: Hashable {
    let url: URL
    let format: String
    let label: String
}

/// Opening / ending segment from AnimeLib's `timecode` (seconds).
struct SkipSegment: Hashable {
    let kind: String      // "opening" / "ending" / other label
    let from: Double
    let to: Double
    var isOpening: Bool { kind.lowercased().contains("open") || kind.lowercased().contains("опен") }
    var isEnding: Bool { kind.lowercased().contains("end") || kind.lowercased().contains("энд") }
}

struct LibPlayer: Hashable {
    var playerId: Int? = nil
    /// Position saved on the account (seconds) and whether AnimeLib marks the episode as viewed.
    var serverTimecode: Double? = nil
    var isViewed = false
    let team: LibTeam
    let player: String               // "Animelib" | "Kodik" | ...
    let kodikSrc: String?
    let qualities: [StreamQuality]   // relative hrefs as URL paths, resolved against a mirror
    let hrefs: [String: String]      // label -> href
    let subtitles: [LibSubtitle]
    let segments: [SkipSegment]

    var isNative: Bool { player.lowercased() == "animelib" && !hrefs.isEmpty }
    var isKodik: Bool { player.lowercased() == "kodik" && kodikSrc != nil }

    static func == (l: LibPlayer, r: LibPlayer) -> Bool {
        l.team == r.team && l.player == r.player && l.kodikSrc == r.kodikSrc
    }
    func hash(into h: inout Hasher) { h.combine(team); h.combine(player); h.combine(kodikSrc) }
}

struct StreamQuality: Identifiable, Hashable {
    let label: String      // "1080p"
    let url: URL
    var id: String { label }
    var height: Int { Int(label.filter(\.isNumber)) ?? 0 }
}

// MARK: - Bookmarks

/// AnimeLib anime bookmark folders (ids are the site's own, see /api/bookmarks/folder/1).
enum BookmarkStatus: Int, CaseIterable, Identifiable, Codable {
    case watching = 21, planned = 22, dropped = 23, completed = 24, favorite = 25, rewatching = 26, onHold = 27

    var id: Int { rawValue }
    var title: String {
        switch self {
        case .watching: return "Смотрю"
        case .planned: return "Запланировано"
        case .dropped: return "Брошено"
        case .completed: return "Просмотрено"
        case .favorite: return "Любимые"
        case .rewatching: return "Пересматриваю"
        case .onHold: return "Отложено"
        }
    }
    var icon: String {
        switch self {
        case .watching: return "play.circle"
        case .planned: return "calendar.badge.plus"
        case .dropped: return "xmark.circle"
        case .completed: return "checkmark.circle"
        case .favorite: return "heart"
        case .rewatching: return "arrow.counterclockwise.circle"
        case .onHold: return "pause.circle"
        }
    }
    init?(shikiStatus: String) {
        switch shikiStatus {
        case "watching": self = .watching
        case "planned": self = .planned
        case "completed": self = .completed
        case "dropped": self = .dropped
        case "on_hold": self = .onHold
        case "rewatching": self = .rewatching
        default: return nil
        }
    }

    /// Matching Shikimori user_rate status.
    var shikiStatus: String {
        switch self {
        case .watching: return "watching"
        case .planned: return "planned"
        case .dropped: return "dropped"
        case .completed: return "completed"
        case .favorite: return "watching"
        case .rewatching: return "rewatching"
        case .onHold: return "on_hold"
        }
    }
}

// MARK: - Catalog query

enum CatalogSort: String, CaseIterable, Identifiable {
    case popular = "views", rating = "rating_score", updated = "last_episode_at", newest = "created_at"
    var id: String { rawValue }
    var title: String {
        switch self {
        case .popular: return "Популярные"
        case .rating: return "Рейтинг"
        case .updated: return "Новые серии"
        case .newest: return "Новинки"
        }
    }
}

enum CatalogType: Int, CaseIterable, Identifiable {
    case tv = 16, movie = 17, short = 18, special = 19, ova = 20, ona = 21, clip = 22
    var id: Int { rawValue }
    var title: String {
        switch self {
        case .tv: return "Сериал"
        case .movie: return "Фильм"
        case .short: return "Короткометражка"
        case .special: return "Спешл"
        case .ova: return "OVA"
        case .ona: return "ONA"
        case .clip: return "Клип"
        }
    }
}

enum CatalogStatus: Int, CaseIterable, Identifiable {
    case ongoing = 1, finished = 2, announce = 3, paused = 4, cancelled = 5
    var id: Int { rawValue }
    var title: String {
        switch self {
        case .ongoing: return "Онгоинг"
        case .finished: return "Завершён"
        case .announce: return "Анонс"
        case .paused: return "Приостановлен"
        case .cancelled: return "Выпуск прекращён"
        }
    }
}

struct CatalogQuery: Equatable, Hashable {
    var sort: CatalogSort = .popular
    var genres: Set<Int> = []
    var types: Set<CatalogType> = []
    var statuses: Set<CatalogStatus> = []
    var yearFrom: Int?
    var yearTo: Int?
    var text: String = ""

    init() {}

    init(sort: CatalogSort) {
        self.sort = sort
    }

    init(genre: Int) {
        self.genres = [genre]
    }

    init(status: CatalogStatus, sort: CatalogSort) {
        self.statuses = [status]
        self.sort = sort
    }

    var activeFilterCount: Int {
        genres.count + types.count + statuses.count + ((yearFrom != nil || yearTo != nil) ? 1 : 0)
    }

    func items(page: Int) -> [URLQueryItem] {
        var q: [URLQueryItem] = [
            URLQueryItem(name: "site_id[]", value: "5"),
            URLQueryItem(name: "page", value: String(page))
        ]
        if text.isEmpty {
            q.append(URLQueryItem(name: "sort_by", value: sort.rawValue))
            q.append(URLQueryItem(name: "sort_type", value: "desc"))
        } else {
            q.append(URLQueryItem(name: "q", value: text))
        }
        genres.sorted().forEach { q.append(URLQueryItem(name: "genres[]", value: String($0))) }
        types.map(\.rawValue).sorted().forEach { q.append(URLQueryItem(name: "types[]", value: String($0))) }
        statuses.map(\.rawValue).sorted().forEach { q.append(URLQueryItem(name: "status[]", value: String($0))) }
        if let yearFrom { q.append(URLQueryItem(name: "year_min", value: String(yearFrom))) }
        if let yearTo { q.append(URLQueryItem(name: "year_max", value: String(yearTo))) }
        return q
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
