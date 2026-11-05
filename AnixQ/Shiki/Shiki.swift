import Foundation
import SwiftUI
import UserNotifications

// MARK: - Session

/// Two ways in:
/// - **site session** (default): the user signs in on shikimori.io in a web view and the app reuses
///   that session (cookies + CSRF token), exactly like the site's own frontend calls the API;
/// - **OAuth app**: the user's own registered application (client id/secret), kept as a fallback.
enum ShikiSession {
    static let redirectURI = "urn:ietf:wg:oauth:2.0:oob"
    private static let d = UserDefaults.standard

    static var clientId: String? {
        get { d.string(forKey: "shiki.clientId") }
        set { d.set(newValue, forKey: "shiki.clientId") }
    }
    static var clientSecret: String? {
        get { d.string(forKey: "shiki.clientSecret") }
        set { d.set(newValue, forKey: "shiki.clientSecret") }
    }
    static var appName: String {
        get { d.string(forKey: "shiki.appName") ?? "AnixQ" }
        set { d.set(newValue, forKey: "shiki.appName") }
    }
    static var token: String? {
        get { d.string(forKey: "shiki.token") }
        set { d.set(newValue, forKey: "shiki.token") }
    }
    static var refreshToken: String? {
        get { d.string(forKey: "shiki.refresh") }
        set { d.set(newValue, forKey: "shiki.refresh") }
    }
    static var userId: Int? {
        get { d.object(forKey: "shiki.userId") as? Int }
        set { d.set(newValue, forKey: "shiki.userId") }
    }
    static var nickname: String? {
        get { d.string(forKey: "shiki.nickname") }
        set { d.set(newValue, forKey: "shiki.nickname") }
    }
    /// "name=value; name2=value2" for the site session.
    static var cookieHeader: String? {
        get { d.string(forKey: "shiki.cookies") }
        set { d.set(newValue, forKey: "shiki.cookies") }
    }
    static var csrf: String? {
        get { d.string(forKey: "shiki.csrf") }
        set { d.set(newValue, forKey: "shiki.csrf") }
    }
    static var isLoggedIn: Bool { (token != nil || cookieHeader != nil) && userId != nil }

    static func clear() {
        token = nil
        refreshToken = nil
        userId = nil
        nickname = nil
        cookieHeader = nil
        csrf = nil
    }
}

// MARK: - Models

struct ShikiCalendarItem: Identifiable, Hashable {
    let shikiId: Int
    let name: String
    let russian: String?
    let image: URL?
    let kind: String?
    let score: Double?
    let episodesAired: Int?
    let episodes: Int?
    let nextEpisode: Int
    let nextEpisodeAt: Date

    var id: String { "\(shikiId)-\(nextEpisode)" }
    var title: String { russian?.nilIfEmpty ?? name }
}

struct ShikiPerson: Identifiable, Hashable {
    let id: Int
    let name: String
    let image: URL?
    let role: String
    let isMain: Bool
    var isCharacter = true
}

struct ShikiVideo: Identifiable, Hashable {
    let id: Int
    let name: String
    let kind: String
    let url: URL
    let image: URL?

    var isMusic: Bool { ["op", "ed", "op_ed_clip", "clip"].contains(kind) }
    var kindLabel: String {
        switch kind {
        case "op": return "Опенинг"
        case "ed": return "Эндинг"
        case "op_ed_clip", "clip": return "Клип"
        case "pv": return "Трейлер"
        case "cm": return "Реклама"
        case "character_trailer": return "Персонаж"
        case "episode_preview": return "Превью серии"
        default: return "Видео"
        }
    }
}

struct FranchiseNode: Identifiable, Hashable {
    let shikiId: Int
    let name: String
    let kind: String?
    let year: Int?
    let date: Date
    /// Shikimori's importance: main seasons/films are heavy, recaps/shorts are light.
    let weight: Int
    var id: Int { shikiId }
    var isMain: Bool { weight >= 3 || ["TV Сериал", "Фильм"].contains(kind ?? "") }
}

struct ShikiAnimeInfo {
    let score: Double?
    let episodes: Int?
    let episodesAired: Int?
    let nextEpisodeAt: Date?
    let status: String?
}

// MARK: - API

enum ShikiAPI {
    static let host = "https://shikimori.io"

    static let dateParser: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    static func parseDate(_ s: String?) -> Date? {
        guard let s else { return nil }
        return dateParser.date(from: s) ?? ISO8601DateFormatter().date(from: s)
    }

    static func request(_ path: String, method: String = "GET", json: Any? = nil, form: [String: String]? = nil,
                        auth: Bool = true) async throws -> Any {
        var req = URLRequest(url: URL(string: host + path)!)
        req.httpMethod = method
        req.timeoutInterval = 20
        req.setValue(ShikiSession.appName, forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let json {
            req.httpBody = try JSONSerialization.data(withJSONObject: json)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        } else if let form {
            req.httpBody = form.map { key, value in "\(key)=\(value.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? value)" }
                .joined(separator: "&").data(using: .utf8)
            req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        }
        if auth, let token = ShikiSession.token {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        } else if auth, let cookies = ShikiSession.cookieHeader {
            req.httpShouldHandleCookies = false
            req.setValue(cookies, forHTTPHeaderField: "Cookie")
            if let csrf = ShikiSession.csrf { req.setValue(csrf, forHTTPHeaderField: "X-CSRF-Token") }
            req.setValue("XMLHttpRequest", forHTTPHeaderField: "X-Requested-With")
            req.setValue(host + "/", forHTTPHeaderField: "Referer")
        }
        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401, auth, ShikiSession.token != nil, await refresh() {
            return try await request(path, method: method, json: json, form: form, auth: auth)
        }
        // Site session: an expired CSRF token gives 422/401 — fetch a fresh one once and retry.
        if (status == 422 || status == 401), auth, ShikiSession.token == nil, ShikiSession.cookieHeader != nil,
           method != "GET", !retriedCSRF, await refreshCSRF() {
            retriedCSRF = true
            defer { retriedCSRF = false }
            return try await request(path, method: method, json: json, form: form, auth: auth)
        }
        guard (200..<300).contains(status) else {
            DebugLog.log("shiki: \(method) \(path) -> \(status) \(String(data: data.prefix(200), encoding: .utf8) ?? "")")
            throw LibError.badStatus(status)
        }
        if data.isEmpty { return JSON() }
        return try JSONSerialization.jsonObject(with: data)
    }

    private static var retriedCSRF = false

    /// Reads a fresh CSRF token from the site using the stored session cookies.
    private static func refreshCSRF() async -> Bool {
        guard let cookies = ShikiSession.cookieHeader else { return false }
        var req = URLRequest(url: URL(string: host + "/")!)
        req.httpShouldHandleCookies = false
        req.setValue(cookies, forHTTPHeaderField: "Cookie")
        req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
                     forHTTPHeaderField: "User-Agent")
        guard let (data, _) = try? await URLSession.shared.data(for: req),
              let html = String(data: data, encoding: .utf8),
              let range = html.range(of: #"name="csrf-token" content="([^"]+)""#, options: .regularExpression) else {
            return false
        }
        let match = String(html[range])
        guard let token = match.split(separator: "\"").dropFirst(3).first else { return false }
        ShikiSession.csrf = String(token)
        DebugLog.log("shiki: csrf refreshed")
        return true
    }

    // MARK: Public data

    static func calendar() async throws -> [ShikiCalendarItem] {
        let json = try await request("/api/calendar", auth: false)
        return (json as? [JSON] ?? []).compactMap { x -> ShikiCalendarItem? in
            guard let a = x.obj("anime"), let id = a.int("id"),
                  let at = parseDate(x.str("next_episode_at")) else { return nil }
            // The API hides posters behind "missing" placeholders; the direct path still works.
            let img = a.obj("image")?.str("original").flatMap { path -> URL? in
                path.contains("missing") ? URL(string: "\(host)/system/animes/preview/\(id).jpg") : URL(string: host + path)
            }
            return ShikiCalendarItem(shikiId: id, name: a.str("name") ?? "", russian: a.str("russian"), image: img,
                                     kind: a.str("kind"), score: a.double("score"),
                                     episodesAired: a.int("episodes_aired"), episodes: a.int("episodes"),
                                     nextEpisode: x.int("next_episode") ?? 0, nextEpisodeAt: at)
        }
    }

    static func anime(_ id: Int) async throws -> ShikiAnimeInfo {
        let x = try await request("/api/animes/\(id)", auth: false) as? JSON ?? [:]
        return ShikiAnimeInfo(score: x.double("score"), episodes: x.int("episodes"),
                              episodesAired: x.int("episodes_aired"),
                              nextEpisodeAt: parseDate(x.str("next_episode_at")), status: x.str("status"))
    }

    struct PersonPage {
        let name: String
        let altName: String?
        let image: URL?
        let description: String?
        let related: [ShikiPerson]          // seyu of a character / characters voiced by a person
        let works: [(id: Int, name: String, russian: String, year: Int?, role: String)]
    }

    /// Character (`/api/characters`) or person (`/api/people`) page.
    static func personPage(id: Int, isCharacter: Bool) async -> PersonPage? {
        let path = isCharacter ? "/api/characters/\(id)" : "/api/people/\(id)"
        guard let x = try? await request(path, auth: false) as? JSON else { return nil }
        func img(_ o: JSON?) -> URL? {
            o?.obj("image")?.str("original").flatMap { $0.contains("missing") ? nil : URL(string: host + $0) }
        }
        // Shikimori BBCode: [character=1]Name[/character] -> Name, drop other tags.
        let rawDesc = x.str("description") ?? ""
        let desc = rawDesc
            .replacingOccurrences(of: "\\[(character|anime|person|manga|ranobe)=\\d+\\]", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\\[/?[a-z_]+(=[^\\]]*)?\\]", with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        var related: [ShikiPerson] = []
        var works: [(Int, String, String, Int?, String)] = []
        if isCharacter {
            related = x.arr("seyu").compactMap { s -> ShikiPerson? in
                guard let sid = s.int("id") else { return nil }
                return ShikiPerson(id: sid, name: s.str("russian") ?? s.str("name") ?? "", image: img(s),
                                   role: "Сейю", isMain: false, isCharacter: false)
            }
            works = x.arr("animes").compactMap { a -> (Int, String, String, Int?, String)? in
                guard let aid = a.int("id") else { return nil }
                return (aid, a.str("name") ?? "", a.str("russian") ?? "", a.str("aired_on").flatMap { Int($0.prefix(4)) },
                        a.str("role") ?? "")
            }
        } else {
            for r in x.arr("roles").prefix(30) {
                for c in r.arr("characters") {
                    if let cid = c.int("id"), !related.contains(where: { $0.id == cid }) {
                        related.append(ShikiPerson(id: cid, name: c.str("russian") ?? c.str("name") ?? "", image: img(c),
                                                   role: "Персонаж", isMain: false, isCharacter: true))
                    }
                }
            }
            works = x.arr("works").compactMap { w -> (Int, String, String, Int?, String)? in
                guard let a = w.obj("anime"), let aid = a.int("id") else { return nil }
                return (aid, a.str("name") ?? "", a.str("russian") ?? "", a.str("aired_on").flatMap { Int($0.prefix(4)) },
                        w.str("role") ?? "")
            }
        }
        return PersonPage(name: x.str("russian") ?? x.str("name") ?? "", altName: x.str("japanese") ?? x.str("name"),
                          image: img(x), description: desc.isEmpty ? nil : desc,
                          related: Array(related.prefix(30)),
                          works: works.map { (id: $0.0, name: $0.1, russian: $0.2, year: $0.3, role: $0.4) })
    }

    /// Title search (Shikimori matches partial Russian names far better than AnimeLib).
    static func search(_ query: String, limit: Int = 5) async -> [(id: Int, russian: String, name: String)] {
        var c = URLComponents(string: "/api/animes")!
        c.queryItems = [URLQueryItem(name: "search", value: query), URLQueryItem(name: "limit", value: String(limit)),
                        URLQueryItem(name: "order", value: "popularity")]
        guard let path = c.string, let json = try? await request(path, auth: false) as? [JSON] else { return [] }
        return json.compactMap { x -> (id: Int, russian: String, name: String)? in
            guard let id = x.int("id") else { return nil }
            return (id, x.str("russian") ?? "", x.str("name") ?? "")
        }
    }

    /// The whole franchise in release order (Shikimori keeps franchises curated, no crossovers).
    static func franchise(_ id: Int) async -> [FranchiseNode] {
        guard let json = try? await request("/api/animes/\(id)/franchise", auth: false) as? JSON else { return [] }
        return json.arr("nodes").compactMap { n -> FranchiseNode? in
            guard let sid = n.int("id"), let ts = n.double("date") else { return nil }
            return FranchiseNode(shikiId: sid, name: n.str("name") ?? "", kind: n.str("kind"), year: n.int("year"),
                                 date: Date(timeIntervalSince1970: ts), weight: n.int("weight") ?? 1)
        }
        .sorted { $0.date < $1.date }
    }

    static func videos(_ id: Int) async -> [ShikiVideo] {
        guard let json = try? await request("/api/animes/\(id)/videos", auth: false) as? [JSON] else { return [] }
        return json.compactMap { v in
            guard let vid = v.int("id"), let u = v.str("url").flatMap(URL.init(string:)) else { return nil }
            let img = v.str("image_url").map { $0.replacingOccurrences(of: "http://", with: "https://") }
                .flatMap(URL.init(string:))
            return ShikiVideo(id: vid, name: v.str("name") ?? "", kind: v.str("kind") ?? "other", url: u, image: img)
        }
        .filter { $0.kind != "episode_preview" }
    }

    /// Characters (main first) and key staff of an anime.
    static func roles(_ id: Int) async -> (characters: [ShikiPerson], staff: [ShikiPerson]) {
        guard let json = try? await request("/api/animes/\(id)/roles", auth: false) as? [JSON] else { return ([], []) }
        var characters: [ShikiPerson] = []
        var staff: [ShikiPerson] = []
        for r in json {
            let roles = r["roles_russian"] as? [String] ?? r["roles"] as? [String] ?? []
            let isMain = (r["roles"] as? [String] ?? []).contains("Main")
            func person(_ o: JSON) -> ShikiPerson? {
                guard let pid = o.int("id") else { return nil }
                let img = o.obj("image")?.str("preview").flatMap { $0.contains("missing") ? nil : URL(string: host + $0) }
                return ShikiPerson(id: pid, name: o.str("russian") ?? o.str("name") ?? "", image: img,
                                   role: roles.joined(separator: ", "), isMain: isMain)
            }
            if let c = r.obj("character"), let p = person(c) {
                characters.append(p)
            } else if let o = r.obj("person"), var p = person(o) {
                p.isCharacter = false
                let key = Set(r["roles"] as? [String] ?? [])
                if !key.isDisjoint(with: ["Director", "Original Creator", "Series Composition", "Music",
                                          "Character Design", "Chief Animation Director"]) {
                    staff.append(p)
                }
            }
        }
        characters.sort { $0.isMain && !$1.isMain }
        return (Array(characters.prefix(30)), Array(staff.prefix(12)))
    }

    // MARK: OAuth

    static func authorizeURL() -> URL? {
        guard let id = ShikiSession.clientId else { return nil }
        var c = URLComponents(string: host + "/oauth/authorize")!
        c.queryItems = [
            URLQueryItem(name: "client_id", value: id),
            URLQueryItem(name: "redirect_uri", value: ShikiSession.redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: "user_rates")
        ]
        return c.url
    }

    static func exchange(code: String) async throws {
        guard let id = ShikiSession.clientId, let secret = ShikiSession.clientSecret else { throw LibError.unauthorized }
        let json = try await request("/oauth/token", method: "POST", form: [
            "grant_type": "authorization_code", "client_id": id, "client_secret": secret,
            "code": code, "redirect_uri": ShikiSession.redirectURI
        ], auth: false) as? JSON ?? [:]
        guard let access = json.str("access_token") else { throw LibError.server("Shikimori не выдал токен") }
        ShikiSession.token = access
        ShikiSession.refreshToken = json.str("refresh_token")
        ShikiSession.cookieHeader = nil
        ShikiSession.csrf = nil
        let me = try await request("/api/users/whoami") as? JSON ?? [:]
        ShikiSession.userId = me.int("id")
        ShikiSession.nickname = me.str("nickname")
        DebugLog.log("shiki: signed in as \(ShikiSession.nickname ?? "?")")
    }

    private static func refresh() async -> Bool {
        guard let id = ShikiSession.clientId, let secret = ShikiSession.clientSecret,
              let refresh = ShikiSession.refreshToken,
              let json = try? await request("/oauth/token", method: "POST", form: [
                "grant_type": "refresh_token", "client_id": id, "client_secret": secret, "refresh_token": refresh
              ], auth: false) as? JSON,
              let access = json.str("access_token") else { return false }
        ShikiSession.token = access
        if let r = json.str("refresh_token") { ShikiSession.refreshToken = r }
        return true
    }

    // MARK: User rates

    struct RemoteRate {
        let shikiId: Int
        let name: String
        let russian: String?
        let status: String
        let episodes: Int
        let score: Int
    }

    /// Whole anime list of the signed-in user.
    static func animeRates() async throws -> [RemoteRate] {
        guard let uid = ShikiSession.userId else { throw LibError.unauthorized }
        var all: [RemoteRate] = []
        var page = 1
        while page <= 20 {
            let json = try await request("/api/users/\(uid)/anime_rates?limit=500&page=\(page)")
            let items = json as? [JSON] ?? []
            for x in items {
                guard let a = x.obj("anime"), let id = a.int("id") else { continue }
                all.append(RemoteRate(shikiId: id, name: a.str("name") ?? "", russian: a.str("russian"),
                                      status: x.str("status") ?? "watching", episodes: x.int("episodes") ?? 0,
                                      score: x.int("score") ?? 0))
            }
            if items.count < 500 { break }
            page += 1
        }
        return all
    }

    static func userRate(animeId: Int) async throws -> JSON? {
        guard let uid = ShikiSession.userId else { return nil }
        let json = try await request("/api/v2/user_rates?user_id=\(uid)&target_id=\(animeId)&target_type=Anime")
        return (json as? [JSON])?.first
    }

    static func upsertRate(animeId: Int, status: String?, episodes: Int?, score: Int? = nil,
                           force: Bool = false) async throws {
        guard let uid = ShikiSession.userId else { return }
        var fields: JSON = [:]
        if let status { fields["status"] = status }
        if let episodes { fields["episodes"] = episodes }
        if let score { fields["score"] = score }
        if let existing = try await userRate(animeId: animeId), let rid = existing.int("id") {
            if !force, let e = episodes, let have = existing.int("episodes"), have >= e,
               status == nil || status == existing.str("status") {
                return
            }
            _ = try await request("/api/v2/user_rates/\(rid)", method: "PATCH", json: ["user_rate": fields])
        } else {
            fields["user_id"] = uid
            fields["target_id"] = animeId
            fields["target_type"] = "Anime"
            if fields["status"] == nil { fields["status"] = "watching" }
            _ = try await request("/api/v2/user_rates", method: "POST", json: ["user_rate": fields])
        }
    }
}

// MARK: - Sync

@MainActor
final class ShikiSync: ObservableObject {
    static let shared = ShikiSync()
    @Published var isLoggedIn = ShikiSession.isLoggedIn
    @Published var lastError: String?

    func refreshState() { isLoggedIn = ShikiSession.isLoggedIn }

    func episodeWatched(title: Title, episodes: Int, completed: Bool) async {
        guard ShikiSession.isLoggedIn, let id = title.shikiId else { return }
        do {
            try await ShikiAPI.upsertRate(animeId: id, status: completed ? "completed" : "watching", episodes: episodes)
            DebugLog.log("shiki: \(title.title) -> \(episodes) эп.\(completed ? " (completed)" : "")")
            lastError = nil
        } catch {
            lastError = "Shikimori: \(error.localizedDescription)"
        }
    }

    func setStatus(title: Title, status: BookmarkStatus) async {
        guard ShikiSession.isLoggedIn, let id = title.shikiId else { return }
        do {
            try await ShikiAPI.upsertRate(animeId: id, status: status.shikiStatus, episodes: nil)
        } catch {
            lastError = "Shikimori: \(error.localizedDescription)"
        }
    }

    func rate(title: Title, score: Int) async {
        guard ShikiSession.isLoggedIn, let id = title.shikiId else { return }
        try? await ShikiAPI.upsertRate(animeId: id, status: nil, episodes: nil, score: score)
    }
}

// MARK: - New-episode notifications

/// Schedules a local notification for the next episode of every title in "Смотрю",
/// using Shikimori's `next_episode_at`. Re-run on every app launch / foreground.
@MainActor
enum EpisodeNotifier {
    static var isEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: "notify.enabled") }
        set { UserDefaults.standard.set(newValue, forKey: "notify.enabled") }
    }

    static func enable() async -> Bool {
        let granted = (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        isEnabled = granted
        return granted
    }

    private static var lastRun = Date.distantPast

    static func reschedule(titles: [Title], force: Bool = false) async {
        guard force || Date().timeIntervalSince(lastRun) > 6 * 3600 else { return }
        lastRun = Date()
        let center = UNUserNotificationCenter.current()
        guard isEnabled else {
            center.removeAllPendingNotificationRequests()
            return
        }
        center.removeAllPendingNotificationRequests()
        var scheduled = 0
        for title in titles.prefix(40) {
            guard let sid = title.shikiId, let info = try? await ShikiAPI.anime(sid),
                  let at = info.nextEpisodeAt, at > Date() else { continue }
            let content = UNMutableNotificationContent()
            content.title = title.title
            let next = (info.episodesAired ?? 0) + 1
            content.body = "Вышла \(next)-я серия. Озвучки обычно появляются в течение нескольких часов."
            content.sound = .default
            content.userInfo = ["slug": title.slugURL]
            // Dubs usually land a little after the Japanese air time.
            let fire = at.addingTimeInterval(2 * 3600)
            let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: fire)
            let request = UNNotificationRequest(identifier: "ep-\(title.id)-\(next)", content: content,
                                                trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false))
            try? await center.add(request)
            scheduled += 1
        }
        DebugLog.log("notify: scheduled \(scheduled) episode notifications")
    }
}
