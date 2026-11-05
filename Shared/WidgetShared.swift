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

    // MARK: Actions from interactive widgets (iOS 17+), applied by the app on next launch.

    struct WidgetAction: Codable, Hashable {
        let slug: String
        let episode: String
        let date: Date
    }

    private static var actionsURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("widget-actions.json")
    }

    static func appendAction(_ a: WidgetAction) {
        guard let url = actionsURL else { return }
        var list = (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode([WidgetAction].self, from: $0) } ?? []
        list.append(a)
        if let data = try? JSONEncoder().encode(list) { try? data.write(to: url, options: .atomic) }
    }

    static func takeActions() -> [WidgetAction] {
        guard let url = actionsURL, let data = try? Data(contentsOf: url),
              let list = try? JSONDecoder().decode([WidgetAction].self, from: data) else { return [] }
        try? FileManager.default.removeItem(at: url)
        return list
    }

    /// The widget shows the next episode right away, before the app has synced anything.
    static func advance(slug: String, after episode: String) {
        guard var p = read(), let i = p.continueItems.firstIndex(where: { $0.slug == slug }) else { return }
        let it = p.continueItems[i]
        let next = Int(episode).map { String($0 + 1) } ?? it.episode
        p.continueItems[i] = ContinueItem(title: it.title, slug: it.slug, episode: next, team: it.team, progress: 0, cover: it.cover)
        write(p)
    }

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
