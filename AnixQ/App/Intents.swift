import AppIntents
import Foundation

/// "Продолжить просмотр в AnixQ": opens the app on the last watched title.
struct ContinueWatchingIntent: AppIntent {
    static var title: LocalizedStringResource = "Продолжить просмотр"
    static var description = IntentDescription("Открывает последний тайтл, который вы смотрели.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        UserDefaults.standard.set("continue", forKey: "pendingAction")
        NotificationCenter.default.post(name: .anixqPendingAction, object: nil)
        return .result()
    }
}

/// "Что скоро выходит в AnixQ": answers with the next episodes from the Shikimori calendar.
struct UpcomingEpisodesIntent: AppIntent {
    static var title: LocalizedStringResource = "Что скоро выходит"
    static var description = IntentDescription("Серии, которые выходят в ближайшие сутки.")

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let items = (try? await ShikiAPI.calendar()) ?? []
        let now = Date()
        let soon = items.filter { $0.nextEpisodeAt > now && $0.nextEpisodeAt < now.addingTimeInterval(24 * 3600) }
            .sorted { $0.nextEpisodeAt < $1.nextEpisodeAt }
        guard !soon.isEmpty else { return .result(dialog: "В ближайшие сутки ничего не выходит.") }
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "HH:mm"
        let lines = soon.prefix(5).map { "\($0.title), серия \($0.nextEpisode), в \(f.string(from: $0.nextEpisodeAt))" }
        return .result(dialog: IntentDialog(stringLiteral: "Скоро выходит: " + lines.joined(separator: "; ") + "."))
    }
}

struct AnixQShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: ContinueWatchingIntent(),
                    phrases: ["Продолжить просмотр в \(.applicationName)", "\(.applicationName) продолжить"])
        AppShortcut(intent: UpcomingEpisodesIntent(),
                    phrases: ["Что скоро выходит в \(.applicationName)", "\(.applicationName) расписание"])
    }
}

extension Notification.Name {
    static let anixqPendingAction = Notification.Name("anixqPendingAction")
}
