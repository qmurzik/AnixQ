import Foundation

/// Opening / ending times from every provider, for any source being played: AniLibria's marks (same TV
/// broadcast, any dub) and AniSkip (community, often recorded on longer Crunchyroll cuts). Times from a cut of
/// another length are moved, not dropped: the opening keeps its place from the start, the ending from the end.
enum SkipTimes {
    private static let tolerance = 180.0

    static func fit(_ s: SkipSegment, recordedOn source: Double, duration: Double) -> SkipSegment? {
        guard s.to > s.from else { return nil }
        if s.isOpening && !(20...200).contains(s.to - s.from) { return nil }
        guard source > 0, duration > 0 else { return s }
        let diff = duration - source
        guard abs(diff) <= tolerance else { return nil }
        let moved = (abs(diff) >= 3 && s.isEnding) ? SkipSegment(kind: s.kind, from: s.from + diff, to: s.to + diff) : s
        return moved.from >= 0 && moved.to <= duration + 1 ? moved : nil
    }

    private static func aniSkip(malId: Int, episode: Int, duration: Double) async -> [SkipSegment] {
        guard let url = URL(string: "https://api.aniskip.com/v2/skip-times/\(malId)/\(episode)?types[]=op&types[]=ed&episodeLength=0"),
              let (data, _) = try? await URLSession.shared.data(from: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = json["results"] as? [[String: Any]] else { return [] }
        let items: [(SkipSegment, Double)] = results.compactMap { r in
            guard let iv = r["interval"] as? [String: Any], let a = (iv["startTime"] as? NSNumber)?.doubleValue,
                  let b = (iv["endTime"] as? NSNumber)?.doubleValue else { return nil }
            let kind = (r["skipType"] as? String) == "op" ? "opening" : "ending"
            return (SkipSegment(kind: kind, from: a, to: b), (r["episodeLength"] as? NSNumber)?.doubleValue ?? 0)
        }
        // Per kind: the recording closest to our length, moved onto our video.
        return ["opening", "ending"].compactMap { k in
            items.filter { $0.0.kind == k }.min { abs($0.1 - duration) < abs($1.1 - duration) }
                .flatMap { fit($0.0, recordedOn: $0.1, duration: duration) }
        }
    }

    /// What's missing from [own], filled from the other providers.
    @MainActor static func find(title: Title, episode: String, duration: Double, own: [SkipSegment]) async -> [SkipSegment] {
        let ownOp = own.first { $0.isOpening }, ownEd = own.first { $0.isEnding }
        if ownOp != nil && ownEd != nil { return own }
        async let libria = Libria.marks(title: title, number: episode)
        let n = Double(episode).map { Int($0) }
        var community: [SkipSegment] = []
        if let mal = title.shikiId, let n { community = await aniSkip(malId: mal, episode: n, duration: duration) }
        let fromLibria = (await libria).flatMap { m in m.segments.compactMap { fit($0, recordedOn: m.duration, duration: duration) } } ?? []
        let all = own + fromLibria + community
        return [ownOp ?? all.first { $0.isOpening }, ownEd ?? all.first { $0.isEnding }].compactMap { $0 }
    }
}
