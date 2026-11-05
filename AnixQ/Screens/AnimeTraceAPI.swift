import Foundation
import UIKit

/// AnimeTrace (ai.animedb.cn) recognises *characters*, so it works on cropped, mirrored or zoomed
/// frames from short videos where trace.moe's whole-frame matching fails.
struct CharacterMatch: Identifiable, Hashable {
    let work: String          // usually the Japanese title
    let characters: [String]
    var hits: Int
    var title: Title?
    /// For video search: how many of the analysed frames pointed at this work.
    var frames = 1
    var id: String { work }
}

enum AnimeTraceAPI {
    private static let models = ["pre_stable", "anime_model_lovelive"]

    static func search(_ image: UIImage) async -> [CharacterMatch] {
        // ~800 px is plenty for character recognition and keeps the upload small.
        let scale = min(1, 800 / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let small = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let jpeg = small.jpegData(compressionQuality: 0.85) else { return [] }
        let b64 = jpeg.base64EncodedString()
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        for model in models {
            var req = URLRequest(url: URL(string: "https://api.animetrace.com/v1/search")!)
            req.httpMethod = "POST"
            req.timeoutInterval = 40
            req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X)", forHTTPHeaderField: "User-Agent")
            let body = "is_multi=1&ai_detect=0&model=\(model)&base64=" + (b64.addingPercentEncoding(withAllowedCharacters: allowed) ?? b64)
            req.httpBody = Data(body.utf8)
            guard let (data, _) = try? await URLSession.shared.data(for: req),
                  let json = try? JSONSerialization.jsonObject(with: data) as? JSON else {
                DebugLog.log("animetrace: \(model) no response")
                continue
            }
            let boxes = json.arr("data")
            // Each detected face lists candidate (work, character) pairs; count works across faces.
            var works: [String: (chars: [String], hits: Int, order: Int)] = [:]
            var order = 0
            for box in boxes {
                for (rank, c) in box.arr("character").prefix(3).enumerated() {
                    guard let work = c.str("work"), !work.isEmpty else { continue }
                    var entry = works[work] ?? ([], 0, order)
                    if entry.hits == 0 { order += 1 }
                    if let name = c.str("character"), !entry.chars.contains(name) { entry.chars.append(name) }
                    entry.hits += rank == 0 ? 3 : 1
                    works[work] = entry
                }
            }
            let matches = works.map { CharacterMatch(work: $0.key, characters: $0.value.chars, hits: $0.value.hits) }
                .sorted { $0.hits > $1.hits }
            DebugLog.log("animetrace: \(model) code \(json.int("code") ?? -1) -> \(matches.prefix(3).map(\.work))")
            if !matches.isEmpty { return Array(matches.prefix(4)) }
        }
        return []
    }

    /// Several frames from a video: each is recognised, works are ranked by how many frames agree.
    static func search(frames: [UIImage], progress: @escaping (Int) -> Void) async -> [CharacterMatch] {
        var merged: [String: CharacterMatch] = [:]
        var done = 0
        // Two at a time: fast enough, gentle on the free API.
        for chunk in stride(from: 0, to: frames.count, by: 2).map({ Array(frames[$0..<min($0 + 2, frames.count)]) }) {
            let parts = await withTaskGroup(of: [CharacterMatch].self) { group in
                for f in chunk { group.addTask { await search(f) } }
                var all: [[CharacterMatch]] = []
                for await r in group { all.append(r) }
                return all
            }
            for list in parts {
                for (rank, m) in list.enumerated() {
                    let weight = max(1, m.hits) * (rank == 0 ? 2 : 1)
                    if var e = merged[m.work] {
                        e.hits += weight
                        e.frames += 1
                        for c in m.characters where !e.characters.contains(c) { e = CharacterMatch(work: e.work, characters: e.characters + [c], hits: e.hits, title: nil, frames: e.frames) }
                        merged[m.work] = e
                    } else {
                        merged[m.work] = CharacterMatch(work: m.work, characters: m.characters, hits: weight, title: nil, frames: 1)
                    }
                }
                done += 1
                progress(done)
            }
        }
        return merged.values.sorted { $0.frames != $1.frames ? $0.frames > $1.frames : $0.hits > $1.hits }.prefix(5).map { $0 }
    }

    /// Work title (usually Japanese) -> AnimeLib title via Shikimori search.
    static func resolve(_ m: CharacterMatch) async -> Title? {
        for hit in await ShikiAPI.search(m.work, limit: 2) {
            if let t = await LibAPI.title(shikiId: hit.id, names: [hit.name, hit.russian]) { return t }
        }
        return (try? await LibAPI.search(m.work))?.first
    }
}
