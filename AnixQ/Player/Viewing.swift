import SwiftUI

/// "Без спойлеров": names of episodes you haven't watched are hidden (they often give the plot away).
enum Spoilers {
    static func title(_ ep: LibEpisode, watched: Bool) -> String {
        PlayerPrefs.noSpoilers && !watched ? "Серия \(ep.number)" : ep.displayTitle
    }
}

/// Marathon: after a few episodes in a row, a gentle stop instead of the next one.
struct MarathonBreakCard: View {
    @ObservedObject var c: PlayerController

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "cup.and.saucer.fill").font(.system(size: 34)).foregroundStyle(.white)
            Text("Уже \(PlayerController.marathonBreakAfter) серии подряд").font(.title3.weight(.bold))
            Text("Может, перерыв? Глазам и спине тоже нужен отдых.")
                .font(.subheadline).foregroundStyle(.white.opacity(0.75)).multilineTextAlignment(.center)
            HStack(spacing: 10) {
                Button { c.breakPrompt = false } label: {
                    Text("Перерыв").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 44)
                        .background(.white.opacity(0.15), in: Capsule())
                }
                Button { c.breakPrompt = false; c.next() } label: {
                    Label("Ещё одну", systemImage: "play.fill").font(.subheadline.weight(.bold)).frame(maxWidth: .infinity, minHeight: 44)
                        .background(.white, in: Capsule()).foregroundStyle(.black)
                }
            }
        }
        .foregroundStyle(.white)
        .padding(22)
        .frame(maxWidth: 380)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .environment(\.colorScheme, .dark)
        .padding()
    }
}


/// Community "previously on…" marks (AniSkip, by MAL id = Shikimori id).
enum AniSkipRecap {
    private static var cache: [String: SkipSegment?] = [:]

    @MainActor
    static func find(malId: Int, episode: Int) async -> SkipSegment? {
        let key = "\(malId)/\(episode)"
        if let hit = cache[key] { return hit }
        guard let url = URL(string: "https://api.aniskip.com/v2/skip-times/\(malId)/\(episode)?types[]=recap&episodeLength=0"),
              let (data, _) = try? await URLSession.shared.data(from: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let first = (json["results"] as? [[String: Any]])?.first,
              let iv = first["interval"] as? [String: Any],
              let from = (iv["startTime"] as? NSNumber)?.doubleValue, let to = (iv["endTime"] as? NSNumber)?.doubleValue else {
            cache[key] = .some(nil)
            return nil
        }
        let seg = SkipSegment(kind: "recap", from: from, to: to)
        cache[key] = seg
        return seg
    }
}


/// Search that forgives: wrong keyboard layout, Latin ↔ Cyrillic, a typo at the end, then Shikimori's search.
enum SmartSearch {
    private static let en = Array("qwertyuiop[]asdfghjkl;'zxcvbnm,.`")
    private static let ru = Array("йцукенгшщзхъфывапролджэячсмитьбюё")

    static func swapLayout(_ s: String) -> String {
        String(s.lowercased().map { ch in
            if let i = en.firstIndex(of: ch) { return ru[i] }
            if let j = ru.firstIndex(of: ch) { return en[j] }
            return ch
        })
    }

    static func translit(_ s: String) -> String {
        let low = s.lowercased()
        if low.range(of: "[а-яё]", options: .regularExpression) != nil {
            return low.applyingTransform(.toLatin, reverse: false)?.folding(options: .diacriticInsensitive, locale: nil) ?? low
        }
        return low.applyingTransform(.latinToCyrillic, reverse: false) ?? low
    }

    /// Titles and, when a variant was used, what was actually searched for.
    static func search(_ q: String) async -> ([Title], String?) {
        let first = (try? await LibAPI.search(q)) ?? []
        if !first.isEmpty || q.count < 3 { return (first, nil) }
        var variants: [String] = [swapLayout(q), translit(q)]
        if q.count >= 5 { variants += [String(q.dropLast()), translit(String(q.dropLast()))] }
        if q.count >= 7 { variants.append(String(q.dropLast(2))) }
        var seen = Set([q])
        for v in variants where !v.isEmpty && seen.insert(v).inserted {
            let r = (try? await LibAPI.search(v)) ?? []
            if !r.isEmpty { return (r, v) }
        }
        let encoded = q.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? q
        guard let list = try? await ShikiAPI.request("/api/animes?limit=6&search=\(encoded)", auth: false) as? [JSON] else { return ([], nil) }
        var out: [Title] = []
        for x in list {
            guard let id = x["id"] as? Int else { continue }
            let names = [x["russian"] as? String, x["name"] as? String].compactMap { $0 }
            if let t = await LibAPI.title(shikiId: id, names: names), !out.contains(where: { $0.id == t.id }) { out.append(t) }
        }
        return (out, out.first?.title)
    }
}
