import Foundation

// MARK: - Session

enum LibSession {
    private static let tokenKey = "animelib.token"
    private static let refreshKey = "animelib.refresh"
    private static let nameKey = "animelib.username"
    private static let userIdKey = "animelib.userId"

    static var token: String? {
        get { UserDefaults.standard.string(forKey: tokenKey) }
        set { UserDefaults.standard.set(newValue, forKey: tokenKey) }
    }
    static var refreshToken: String? {
        get { UserDefaults.standard.string(forKey: refreshKey) }
        set { UserDefaults.standard.set(newValue, forKey: refreshKey) }
    }
    static var username: String? {
        get { UserDefaults.standard.string(forKey: nameKey) }
        set { UserDefaults.standard.set(newValue, forKey: nameKey) }
    }
    static var userId: Int? {
        get { UserDefaults.standard.object(forKey: userIdKey) as? Int }
        set { UserDefaults.standard.set(newValue, forKey: userIdKey) }
    }
    static var isLoggedIn: Bool { token != nil }

    static func clear() {
        token = nil
        refreshToken = nil
        username = nil
        userId = nil
    }
}

enum LibError: LocalizedError {
    case notFound, unauthorized, noVideo, badStatus(Int), server(String)
    var errorDescription: String? {
        switch self {
        case .notFound: return "Не найдено на AnimeLib"
        case .unauthorized: return "Войдите в аккаунт AnimeLib"
        case .noVideo: return "У этой озвучки нет видео для серии"
        case .badStatus(let s): return "AnimeLib ответил ошибкой \(s)"
        case .server(let m): return m
        }
    }
}

// MARK: - API

/// Client for the AnimeLib (lib.social) API that anilib.me / animelib.org use themselves.
enum LibAPI {
    static let hosts = ["https://api.cdnlibs.org", "https://api.lib.social"]
    static let site = "https://anilib.me"
    static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
    private static let fallbackServers = ["https://video1.cdnlibs.org/.аs/", "https://video2.cdnlibs.org/",
                                          "https://video1.imglib.info/"]

    // MARK: Transport

    @discardableResult
    static func request(_ path: String, query: [URLQueryItem] = [], auth: Bool = true,
                        method: String = "GET", body: Any? = nil) async throws -> Any {
        let cacheable = method == "GET" && !path.hasPrefix("/bookmarks") && !path.hasPrefix("/auth")
        let cacheKey = cacheable ? ResponseCache.key(path, query) : nil
        if let cacheKey, let hit = ResponseCache.memory(cacheKey) { return hit }
        var lastError: Error = LibError.notFound
        let bodyData = try body.map { try JSONSerialization.data(withJSONObject: $0) }
        for host in hosts {
            var comps = URLComponents(string: host + "/api" + path)!
            if !query.isEmpty { comps.queryItems = query }
            var req = URLRequest(url: comps.url!)
            req.httpMethod = method
            req.httpBody = bodyData
            req.timeoutInterval = 20
            req.setValue("5", forHTTPHeaderField: "Site-Id")
            req.setValue(site + "/", forHTTPHeaderField: "Referer")
            req.setValue(site, forHTTPHeaderField: "Origin")
            req.setValue("application/json", forHTTPHeaderField: "Accept")
            req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
            if bodyData != nil { req.setValue("application/json", forHTTPHeaderField: "Content-Type") }
            if auth, let token = LibSession.token {
                req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            }
            do {
                let (data, response) = try await URLSession.shared.data(for: req)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                if status == 401 {
                    if auth, LibSession.token != nil, await refresh() {
                        return try await request(path, query: query, auth: auth, method: method, body: body)
                    }
                    throw LibError.unauthorized
                }
                if status == 204 || data.isEmpty { return JSON() }
                guard (200..<300).contains(status) else {
                    if (400..<500).contains(status) {
                        // Re-serialise so Cyrillic validation messages are readable (server sends \uXXXX).
                        let msg = (try? JSONSerialization.jsonObject(with: data))
                            .flatMap { try? JSONSerialization.data(withJSONObject: $0) }
                            .flatMap { String(data: $0.prefix(600), encoding: .utf8) }
                            ?? String(data: data.prefix(400), encoding: .utf8) ?? ""
                        DebugLog.log("lib: \(method) \(path) -> \(status) \(msg)")
                        throw LibError.badStatus(status)
                    }
                    lastError = LibError.badStatus(status)
                    continue
                }
                let json = try JSONSerialization.jsonObject(with: data)
                if let cacheKey { ResponseCache.store(cacheKey, json: json, data: data, path: path) }
                return json
            } catch let e as LibError {
                throw e
            } catch {
                if (error as? URLError)?.code == .cancelled { throw error }
                lastError = error
            }
        }
        throw lastError
    }

    private static func refresh() async -> Bool {
        guard let refresh = LibSession.refreshToken else { return false }
        let body: JSON = ["grant_type": "refresh_token", "client_id": 1, "refresh_token": refresh, "scope": ""]
        guard let json = try? await request("/auth/oauth/token", auth: false, method: "POST", body: body) as? JSON,
              let access = json.str("access_token") else {
            return false
        }
        LibSession.token = access
        if let r = json.str("refresh_token") { LibSession.refreshToken = r }
        return true
    }

    private static func data(_ any: Any) -> Any? { (any as? JSON)?["data"] }

    // MARK: Account

    static func loadProfile() async {
        guard let json = try? await request("/auth/me"), let d = data(json) as? JSON else { return }
        LibSession.username = d.str("username")
        LibSession.userId = d.int("id")
    }

    // MARK: Catalog

    static func catalog(_ query: CatalogQuery, page: Int) async throws -> (items: [Title], hasMore: Bool) {
        let json = try await request("/anime", query: query.items(page: page))
        let items = (data(json) as? [JSON] ?? []).compactMap(Title.init(json:))
        let meta = (json as? JSON)?.obj("meta")
        let hasMore = meta?["has_next_page"] as? Bool ?? (items.count >= (meta?.int("per_page") ?? 60))
        return (items, hasMore)
    }

    static func search(_ text: String) async throws -> [Title] {
        var q = CatalogQuery()
        q.text = text
        return try await catalog(q, page: 1).items
    }

    static let detailFields = ["background", "summary", "releaseDate", "genres", "tags", "authors",
                               "publisher", "time", "otherNames", "views"]

    static func detail(_ slug: String) async throws -> TitleDetail {
        let q = detailFields.map { URLQueryItem(name: "fields[]", value: $0) }
        let json = try await request("/anime/\(slug)", query: q)
        guard let d = data(json) as? JSON, let detail = TitleDetail(json: d) else { throw LibError.notFound }
        return detail
    }

    /// Instant, possibly stale data from disk for the title page.
    static func cachedDetail(_ slug: String) -> TitleDetail? {
        let q = detailFields.map { URLQueryItem(name: "fields[]", value: $0) }
        guard let d = (ResponseCache.disk("/anime/\(slug)", q) as? JSON)?.obj("data") else { return nil }
        return TitleDetail(json: d)
    }

    static func cachedEpisodes(_ slug: String) -> [LibEpisode] {
        let q = [URLQueryItem(name: "anime_id", value: slug)]
        let items = (ResponseCache.disk("/episodes", q) as? JSON)?["data"] as? [JSON] ?? []
        return items.enumerated().compactMap { i, x in
            guard let id = x.int("id") else { return nil }
            return LibEpisode(id: id, number: x.str("number") ?? String(i + 1), season: x.str("season"),
                              name: x.str("name"), index: i)
        }
    }

    static func background(_ slug: String) async -> URL? {
        guard let json = try? await request("/anime/\(slug)", query: [URLQueryItem(name: "fields[]", value: "background")]),
              let d = data(json) as? JSON else { return nil }
        return realImage(d.obj("background")?.str("url")).flatMap(URL.init(string:))
    }

    /// AnimeLib returns `/static/images/placeholders/…` (no host) when a title has no art.
    static func realImage(_ s: String?) -> String? {
        guard let s, s.hasPrefix("http"), !s.contains("/placeholders/") else { return nil }
        return s
    }

    static func related(_ slug: String) async -> [RelatedTitle] {
        guard let json = try? await request("/anime/\(slug)/relations") else { return [] }
        var seen = Set<Int>()
        return (data(json) as? [JSON] ?? []).compactMap { r -> RelatedTitle? in
            guard let media = r.obj("media"), media.str("model") == "anime", let t = Title(json: media),
                  seen.insert(t.id).inserted else { return nil }
            return RelatedTitle(title: t, relation: r.obj("related_type")?.str("label") ?? "")
        }
    }

    /// Whole franchise (anime only) in release order: walks relations a few levels deep.
    static func franchise(_ root: Title) async -> [(title: Title, date: String)] {
        var queue: [Title] = [root]
        var seen: Set<Int> = [root.id]
        var nodes: [Title] = []
        var depth = 0
        while !queue.isEmpty, depth < 3, nodes.count < 25 {
            var next: [Title] = []
            for t in queue {
                nodes.append(t)
                for r in await related(t.slugURL) where seen.insert(r.title.id).inserted {
                    next.append(r.title)
                }
            }
            queue = next
            depth += 1
        }
        nodes.append(contentsOf: queue.prefix(max(0, 25 - nodes.count)))
        guard nodes.count > 1 else { return [] }
        var dated: [(Title, String)] = []
        for t in nodes {
            let d = (try? await request("/anime/\(t.slugURL)", query: [URLQueryItem(name: "fields[]", value: "releaseDate")]))
                .flatMap { ($0 as? JSON)?.obj("data")?.str("releaseDate") }
            dated.append((t, d ?? t.year.map { "\($0)-12-31" } ?? "9999"))
        }
        return dated.sorted { $0.1 < $1.1 }.map { (title: $0.0, date: $0.1) }
    }

    static func similar(_ slug: String) async -> [Title] {
        guard let json = try? await request("/anime/\(slug)/similar") else { return [] }
        var seen = Set<Int>()
        return (data(json) as? [JSON] ?? []).compactMap { $0.obj("media").flatMap(Title.init(json:)) }
            .filter { seen.insert($0.id).inserted }
    }

    @MainActor private static var genreCache: [Genre]?

    @MainActor
    static func genres() async -> [Genre] {
        if let genreCache { return genreCache }
        let json = try? await request("/constants", query: [URLQueryItem(name: "fields[]", value: "genres")])
        let list = ((json.flatMap(data) as? JSON)?.arr("genres") ?? []).compactMap { g -> Genre? in
            guard let ids = g["site_ids"] as? [Int], ids.contains(5),
                  let id = g.int("id"), let n = g.str("name"), g["adult"] as? Bool != true else { return nil }
            return Genre(id: id, name: n)
        }
        if !list.isEmpty { genreCache = list }
        return list
    }

    /// Finds the AnimeLib title for a Shikimori id (used by the schedule). Results are cached
    /// on disk, so each calendar entry is searched only once.
    @MainActor private static var shikiMap: [Int: Title] = {
        guard let data = UserDefaults.standard.data(forKey: "shikiMap"),
              let map = try? JSONDecoder().decode([Int: Title].self, from: data) else { return [:] }
        return map
    }()
    @MainActor private static var shikiMisses: Set<Int> = []

    @MainActor
    static func cachedTitle(shikiId: Int) -> Title? { shikiMap[shikiId] }

    @MainActor
    static func title(shikiId: Int, names: [String]) async -> Title? {
        if let hit = shikiMap[shikiId] { return hit }
        if shikiMisses.contains(shikiId) { return nil }
        for n in names where !n.isEmpty {
            if let hit = try? await search(n).first(where: { $0.shikiId == shikiId }) {
                shikiMap[shikiId] = hit
                if let data = try? JSONEncoder().encode(shikiMap) { UserDefaults.standard.set(data, forKey: "shikiMap") }
                return hit
            }
        }
        shikiMisses.insert(shikiId)
        return nil
    }

    // MARK: Episodes

    static func episodes(slug: String) async throws -> [LibEpisode] {
        let json = try await request("/episodes", query: [URLQueryItem(name: "anime_id", value: slug)])
        return (data(json) as? [JSON] ?? []).enumerated().compactMap { i, x in
            guard let id = x.int("id") else { return nil }
            return LibEpisode(id: id, number: x.str("number") ?? String(i + 1), season: x.str("season"),
                              name: x.str("name"), index: i)
        }
    }

    @MainActor private static var playerCache: [Int: (Date, [LibPlayer])] = [:]

    /// Player lists differ for guests and signed-in users, so they are dropped on login/logout.
    @MainActor static func clearCache() {
        playerCache = [:]
        ResponseCache.invalidate()
    }

    @MainActor
    static func players(episodeId: Int) async throws -> [LibPlayer] {
        if let hit = playerCache[episodeId], Date().timeIntervalSince(hit.0) < 600 { return hit.1 }
        let json = try await request("/episodes/\(episodeId)")
        let items = (data(json) as? JSON)?.arr("players") ?? []
        var loggedKeys = false
        let players: [LibPlayer] = items.compactMap { p in
            guard let teamObj = p.obj("team"), let teamId = teamObj.int("id") else { return nil }
            let tt = p.obj("translation_type")?.int("id")
            let team = LibTeam(id: teamId, name: teamObj.str("name") ?? "Команда", isSubtitles: tt == 1)
            var hrefs: [String: String] = [:]
            for q in p.obj("video")?.arr("quality") ?? [] {
                guard let href = q.str("href") else { continue }
                let label = q.int("quality").map { "\($0)p" } ?? "auto"
                hrefs[label] = href
            }
            let subs: [LibSubtitle] = p.arr("subtitles").compactMap { s in
                guard let src = s.str("src") ?? s.str("url"), let url = URL(string: src) else { return nil }
                return LibSubtitle(url: url, format: s.str("format") ?? url.pathExtension,
                                   label: s.str("name") ?? s.str("label") ?? "Субтитры")
            }
            let segments = LibAPI.parseTimecode(p["timecode"])
            if !loggedKeys, (p.str("player") ?? "").lowercased() == "animelib" {
                loggedKeys = true
                let tc = (p["timecode"]).flatMap { try? JSONSerialization.data(withJSONObject: $0) }
                    .flatMap { String(data: $0.prefix(300), encoding: .utf8) } ?? String(describing: p["timecode"] ?? "nil")
                DebugLog.log("lib: timecode=\(tc) subtitles=\(p.arr("subtitles").count)")
            }
            var player = LibPlayer(team: team, player: p.str("player") ?? "?", kodikSrc: p.str("src"),
                                   qualities: [], hrefs: hrefs, subtitles: subs, segments: segments)
            player.playerId = p.int("id")
            player.isViewed = p["is_viewed"] as? Bool ?? false
            if let n = p.double("timecode") { player.serverTimecode = n }
            else if let o = p.obj("timecode") { player.serverTimecode = o.double("timecode") ?? o.double("time") ?? o.double("value") }
            return player
        }
        playerCache[episodeId] = (Date(), players)
        return players
    }

    /// Accepts the shapes seen in the wild: [{type,from,to}], [{name,start,end}], {opening:{from,to}}.
    static func parseTimecode(_ any: Any?) -> [SkipSegment] {
        func seconds(_ v: Any?) -> Double? {
            if let d = v as? Double { return d }
            if let i = v as? Int { return Double(i) }
            guard let s = v as? String else { return nil }
            let parts = s.split(separator: ":").compactMap { Double($0) }
            return parts.isEmpty ? nil : parts.reduce(0) { $0 * 60 + $1 }
        }
        func segment(_ kind: String, _ x: JSON) -> SkipSegment? {
            guard let from = seconds(x["from"] ?? x["start"] ?? x["begin"]),
                  let to = seconds(x["to"] ?? x["end"] ?? x["finish"]), to > from else { return nil }
            return SkipSegment(kind: kind, from: from, to: to)
        }
        if let list = any as? [JSON] {
            return list.compactMap { segment($0.str("type") ?? $0.str("name") ?? $0.str("label") ?? "segment", $0) }
        }
        if let dict = any as? JSON {
            return dict.compactMap { k, v in (v as? JSON).flatMap { segment(k, $0) } }
        }
        return []
    }

    // MARK: Video mirrors

    @MainActor private static var cachedServers: [String]?
    @MainActor private static var rankedServers: (Date, [String])?

    @MainActor
    static func videoServers() async -> [String] {
        if let cachedServers { return cachedServers }
        let json = try? await request("/constants", query: [URLQueryItem(name: "fields[]", value: "videoServers")])
        let list = ((json.flatMap(data) as? JSON)?.arr("videoServers") ?? []).compactMap { $0.str("url") }
        let servers = list.isEmpty ? fallbackServers : list
        cachedServers = servers
        return servers
    }

    static func videoURL(server: String, href: String) -> URL? {
        let base = server.hasSuffix("/") ? server : server + "/"
        let path = href.hasPrefix("/") ? String(href.dropFirst()) : href
        let raw = base + path
        if let url = URL(string: raw) { return url }
        return URL(string: raw.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? raw)
    }

    /// Mirrors ordered with the first one that answered a small range request first.
    @MainActor
    static func rankServers(_ servers: [String], href: String) async -> [String] {
        if let cached = rankedServers, Date().timeIntervalSince(cached.0) < 20 * 60, Set(cached.1) == Set(servers) {
            return cached.1
        }
        let start = Date()
        let winner: String? = await withTaskGroup(of: String?.self) { group in
            for server in servers {
                group.addTask {
                    guard let url = videoURL(server: server, href: href) else { return nil }
                    var req = URLRequest(url: url)
                    req.setValue("bytes=0-65535", forHTTPHeaderField: "Range")
                    req.setValue(site + "/", forHTTPHeaderField: "Referer")
                    req.timeoutInterval = 6
                    guard let (data, response) = try? await URLSession.shared.data(for: req),
                          let status = (response as? HTTPURLResponse)?.statusCode,
                          status == 200 || status == 206, !data.isEmpty else { return nil }
                    return server
                }
            }
            for await result in group {
                if let result { group.cancelAll(); return result }
            }
            return nil
        }
        DebugLog.log(String(format: "lib: fastest mirror %@ in %.2fs",
                            winner.flatMap { URL(string: $0)?.host } ?? "none", Date().timeIntervalSince(start)))
        guard let winner else { return servers }
        let ranked = [winner] + servers.filter { $0 != winner }
        rankedServers = (Date(), ranked)
        return ranked
    }

    /// Resolves players and the fastest mirror ahead of time so play only waits for the video.
    @MainActor
    static func warmUp(episodeId: Int, teamId: Int) {
        Task {
            guard let players = try? await players(episodeId: episodeId),
                  let p = players.first(where: { $0.team.id == teamId && $0.isNative }),
                  let href = p.hrefs["720p"] ?? p.hrefs.values.first else { return }
            _ = await rankServers(await videoServers(), href: href)
        }
    }

    static func kodikURL(_ src: String) -> URL? {
        URL(string: src.hasPrefix("//") ? "https:" + src : src)
    }

    // MARK: Viewing progress (account)

    @MainActor private static var progressDisabled = false

    /// Saves the playback position on the AnimeLib account (`PUT /episodes/timecodes`).
    @MainActor
    static func saveTimecode(episodeId: Int, playerId: Int?, seconds: Double) async {
        guard LibSession.isLoggedIn, !progressDisabled else { return }
        var item: JSON = ["episode_id": episodeId, "timecode": Int(seconds)]
        if let playerId { item["player_id"] = playerId }
        do {
            try await request("/episodes/timecodes", method: "PUT", body: item)
        } catch LibError.badStatus(let code) where code == 422 || code == 404 || code == 405 {
            // Field names differ from our guess: the response is in the log; stop retrying this run.
            progressDisabled = true
        } catch {}
    }

    /// Marks an episode as viewed on the AnimeLib account (`PUT /episodes/views`).
    @MainActor
    static func markViewed(episodeId: Int, playerId: Int?) async {
        guard LibSession.isLoggedIn else { return }
        var item: JSON = ["episode_id": episodeId]
        if let playerId { item["player_id"] = playerId }
        do {
            try await request("/episodes/views", method: "PUT", body: item)
            DebugLog.log("lib: episode \(episodeId) marked viewed")
        } catch {
            DebugLog.log("lib: mark viewed failed \(error)")
        }
    }

    // MARK: Bookmarks

    /// All anime bookmarks of the signed-in user: slug -> (bookmark id, status).
    static func bookmarks(_ status: BookmarkStatus, page: Int = 1) async throws -> [(bookmarkId: Int?, title: Title)] {
        guard let uid = LibSession.userId else { throw LibError.unauthorized }
        let json = try await request("/bookmarks", query: [
            URLQueryItem(name: "user_id", value: String(uid)),
            URLQueryItem(name: "status", value: String(status.rawValue)),
            URLQueryItem(name: "media_type", value: "anime"),
            URLQueryItem(name: "page", value: String(page))
        ])
        return (data(json) as? [JSON] ?? []).compactMap { b -> (bookmarkId: Int?, title: Title)? in
            guard let media = b.obj("media") ?? (b["model"] as? String == "anime" ? b : nil),
                  let t = Title(json: media) else { return nil }
            return (b.int("id"), t)
        }
    }

    static func setBookmark(slug: String, status: BookmarkStatus) async throws {
        try await request("/bookmarks", method: "POST",
                          body: ["media_type": "anime", "media_slug": slug, "status": status.rawValue])
    }

    static func removeBookmark(id: Int) async throws {
        try await request("/bookmarks/\(id)", method: "DELETE")
    }
}


/// Short-lived memory cache for GET responses plus an on-disk copy of title data (detail,
/// episodes, relations), used to paint screens instantly before the network answers.
enum ResponseCache {
    private static let lock = NSLock()
    private static var mem: [String: (Date, Any)] = [:]
    private static let ttl: TimeInterval = 5 * 60
    private static let dir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("api")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    static func key(_ path: String, _ query: [URLQueryItem]) -> String {
        path + "?" + query.map { "\($0.name)=\($0.value ?? "")" }.joined(separator: "&")
            + (LibSession.isLoggedIn ? "#u" : "")
    }

    static func memory(_ key: String) -> Any? {
        lock.lock(); defer { lock.unlock() }
        guard let (date, json) = mem[key], Date().timeIntervalSince(date) < ttl else { return nil }
        return json
    }

    static func store(_ key: String, json: Any, data: Data, path: String) {
        lock.lock()
        mem[key] = (Date(), json)
        if mem.count > 300 { mem = mem.filter { Date().timeIntervalSince($0.value.0) < ttl } }
        lock.unlock()
        // Persist only title-level data; catalog pages change too often to be worth it.
        guard path.hasPrefix("/anime/") || path.hasPrefix("/episodes") else { return }
        try? data.write(to: file(key), options: .atomic)
    }

    /// Last known response from disk (any age).
    static func disk(_ path: String, _ query: [URLQueryItem] = []) -> Any? {
        guard let data = try? Data(contentsOf: file(key(path, query))) else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    static func invalidate() {
        lock.lock(); mem = [:]; lock.unlock()
    }

    private static func file(_ key: String) -> URL {
        var h: UInt64 = 1469598103934665603
        for b in key.utf8 { h = (h ^ UInt64(b)) &* 1099511628211 }
        return dir.appendingPathComponent(String(h, radix: 36) + ".json")
    }
}
