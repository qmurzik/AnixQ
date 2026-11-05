import Foundation

struct SubtitleCue {
    let start: Double
    let end: Double
    let text: String
}

/// Minimal parsers for the subtitle formats AnimeLib serves (ASS/SSA, WebVTT, SRT).
enum SubtitleParser {
    static func load(_ sub: LibSubtitle) async throws -> [SubtitleCue] {
        var req = URLRequest(url: sub.url)
        req.setValue(LibAPI.site + "/", forHTTPHeaderField: "Referer")
        let (data, _) = try await URLSession.shared.data(for: req)
        let text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        let format = sub.format.lowercased()
        let cues: [SubtitleCue]
        if format.contains("ass") || format.contains("ssa") || text.contains("[Events]") {
            cues = parseASS(text)
        } else {
            cues = parseVTT(text)
        }
        return cues.sorted { $0.start < $1.start }
    }

    // MARK: ASS

    static func parseASS(_ text: String) -> [SubtitleCue] {
        var fields: [String] = []
        var cues: [SubtitleCue] = []
        var inEvents = false
        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("[") { inEvents = line.lowercased() == "[events]"; continue }
            guard inEvents else { continue }
            if line.lowercased().hasPrefix("format:") {
                fields = line.dropFirst(7).split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
                continue
            }
            guard line.lowercased().hasPrefix("dialogue:"), !fields.isEmpty else { continue }
            let body = String(line.dropFirst(9))
            // The last field (Text) may itself contain commas.
            let parts = body.split(separator: ",", maxSplits: fields.count - 1, omittingEmptySubsequences: false)
                .map(String.init)
            guard parts.count == fields.count,
                  let si = fields.firstIndex(of: "start"), let ei = fields.firstIndex(of: "end"),
                  let ti = fields.firstIndex(of: "text"),
                  let start = assTime(parts[si]), let end = assTime(parts[ei]) else { continue }
            var t = parts[ti]
            t = t.replacingOccurrences(of: "\\{[^}]*\\}", with: "", options: .regularExpression)
            t = t.replacingOccurrences(of: "\\N", with: "\n").replacingOccurrences(of: "\\n", with: "\n")
                .replacingOccurrences(of: "\\h", with: " ")
            t = t.trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty { cues.append(SubtitleCue(start: start, end: end, text: t)) }
        }
        return cues
    }

    private static func assTime(_ s: String) -> Double? {
        let p = s.trimmingCharacters(in: .whitespaces).split(separator: ":")
        guard p.count == 3, let h = Double(p[0]), let m = Double(p[1]), let sec = Double(p[2]) else { return nil }
        return h * 3600 + m * 60 + sec
    }

    // MARK: VTT / SRT

    static func parseVTT(_ text: String) -> [SubtitleCue] {
        var cues: [SubtitleCue] = []
        let blocks = text.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n\n")
        for block in blocks {
            let lines = block.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
            guard let ti = lines.firstIndex(where: { $0.contains("-->") }) else { continue }
            let times = lines[ti].components(separatedBy: "-->")
            guard times.count == 2, let start = vttTime(times[0]),
                  let end = vttTime(times[1].split(separator: " ").first.map(String.init) ?? times[1]) else { continue }
            let body = lines[(ti + 1)...].joined(separator: "\n")
                .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !body.isEmpty { cues.append(SubtitleCue(start: start, end: end, text: body)) }
        }
        return cues
    }

    private static func vttTime(_ s: String) -> Double? {
        let clean = s.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        let p = clean.split(separator: ":").compactMap { Double($0) }
        switch p.count {
        case 3: return p[0] * 3600 + p[1] * 60 + p[2]
        case 2: return p[0] * 60 + p[1]
        default: return nil
        }
    }

    /// Binary search for the cue showing at `t`.
    static func cue(at t: Double, in cues: [SubtitleCue]) -> String? {
        var lo = 0, hi = cues.count - 1, found: Int?
        while lo <= hi {
            let mid = (lo + hi) / 2
            if cues[mid].start <= t { found = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        guard let i = found else { return nil }
        // Several cues can overlap; collect the ones active at t near the found index.
        var lines: [String] = []
        var j = i
        while j >= 0, j >= i - 4 {
            if cues[j].start <= t, cues[j].end > t { lines.insert(cues[j].text, at: 0) }
            j -= 1
        }
        return lines.isEmpty ? nil : lines.joined(separator: "\n")
    }
}
