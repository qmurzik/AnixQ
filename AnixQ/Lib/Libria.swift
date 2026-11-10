import Foundation

/// AniLibria's own servers for the AniLibria dub: when AnimeLib is slow (or has it only in Kodik), the same
/// episode comes straight from AniLibria as HLS — no ads, usually faster. Same matching as the web room and Android.
enum Libria {
    private static let api = "https://anilibria.top/api/v1"
    struct Episode { let qualities: [StreamQuality]; let segments: [SkipSegment] }
    /// titleId → release JSON, or NSNull when AniLibria doesn't have it.
    @MainActor private static var releases: [Int: Any] = [:]

    static var enabled: Bool {
        get { UserDefaults.standard.object(forKey: "libriaDirect") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "libriaDirect") }
    }

    static func isLibria(_ team: String) -> Bool {
        team.range(of: #"ani\s*libria|анилибри"#, options: [.regularExpression, .caseInsensitive]) != nil
    }

    private static func norm(_ s: String?) -> String {
        (s ?? "").lowercased().replacingOccurrences(of: "ё", with: "е")
            .unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(String.init).joined()
    }

    private static func json(_ url: String) async -> Any? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u); req.timeoutInterval = 6
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    @MainActor private static func release(for title: Title) async -> [String: Any]? {
        if let r = releases[title.id] { return r as? [String: Any] }
        let names = Set([title.rusName, title.name, title.engName].compactMap { $0 }.map(norm).filter { !$0.isEmpty })
        func matches(_ r: [String: Any]) -> Bool {
            guard let n = r["name"] as? [String: Any] else { return names.isEmpty }
            return names.isEmpty || names.contains(norm(n["main"] as? String)) || names.contains(norm(n["english"] as? String))
        }
        // AnimeLib's slug ("123--jujutsu-kaisen-anime") is usually AniLibria's alias; the name decides.
        var alias = title.slugURL
        if let r = alias.range(of: "--") { alias = String(alias[r.upperBound...]) }
        if alias.hasSuffix("-anime") { alias.removeLast(6) }
        var found = (await json("\(api)/anime/releases/\(alias)") as? [String: Any]).flatMap { matches($0) ? $0 : nil }
        if found == nil {
            let q = (title.rusName ?? title.name ?? alias).addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
            if let list = await json("\(api)/app/search/releases?query=\(q)") as? [[String: Any]],
               let hit = list.first(where: matches), let id = hit["id"] as? Int {
                found = await json("\(api)/anime/releases/\(id)") as? [String: Any]
            }
        }
        releases[title.id] = found ?? NSNull()
        return found
    }

    /// Their HLS links for this episode (best first), or nil when they don't have it.
    @MainActor static func episode(title: Title, number: String) async -> Episode? {
        guard let n = Double(number), let r = await release(for: title),
              let eps = r["episodes"] as? [[String: Any]],
              let ep = eps.first(where: { ($0["ordinal"] as? NSNumber)?.doubleValue == n }) else { return nil }
        let qualities = ["1080", "720", "480"].compactMap { q -> StreamQuality? in
            guard let s = ep["hls_\(q)"] as? String, let u = URL(string: s) else { return nil }
            return StreamQuality(label: "\(q)p", url: u)
        }
        guard !qualities.isEmpty else { return nil }
        func seg(_ key: String) -> SkipSegment? {
            guard let o = ep[key] as? [String: Any], let a = (o["start"] as? NSNumber)?.doubleValue,
                  let b = (o["stop"] as? NSNumber)?.doubleValue, b > a else { return nil }
            return SkipSegment(kind: key, from: a, to: b)
        }
        return Episode(qualities: qualities, segments: [seg("opening"), seg("ending")].compactMap { $0 })
    }
}
