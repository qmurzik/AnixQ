import Foundation

/// Data the app hands to its widgets through the shared App Group container.
enum WidgetShared {
    static let group = "group.ru.qmods.anixq"

    struct ContinueItem: Codable, Hashable {
        let title: String
        let slug: String
        let episode: String
        let team: String
        let progress: Double
        let cover: String?
    }

    struct Payload: Codable {
        var continueItems: [ContinueItem]
        var watchingShikiIds: [Int]
        var updated: Date
        /// shikiId -> AnimeLib cover / slug for titles the user follows (covers in "Скоро выходит").
        var covers: [Int: String]? = nil
        var slugs: [Int: String]? = nil
    }

    static var fileURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("widget.json")
    }

    static func write(_ payload: Payload) {
        guard let url = fileURL, let data = try? JSONEncoder().encode(payload) else { return }
        try? data.write(to: url, options: .atomic)
    }

    static func read() -> Payload? {
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Payload.self, from: data)
    }

    static func titleURL(_ slug: String) -> URL? { URL(string: "anixq://title/\(slug)") }

    /// Heartbeat written by the widget extension so the app can show whether widgets run.
    struct WidgetStatus: Codable {
        var date: Date
        var kind: String
        var note: String
    }

    static func writeStatus(kind: String, note: String) {
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("widget-status-\(kind).json"),
              let data = try? JSONEncoder().encode(WidgetStatus(date: Date(), kind: kind, note: note)) else { return }
        try? data.write(to: url, options: .atomic)
    }

    static func readStatuses() -> [WidgetStatus] {
        guard let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
              let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return [] }
        return files.filter { $0.lastPathComponent.hasPrefix("widget-status-") }.compactMap {
            (try? Data(contentsOf: $0)).flatMap { try? JSONDecoder().decode(WidgetStatus.self, from: $0) }
        }
    }
}
