import Foundation
import ActivityKit
import EventKit
import UIKit

// MARK: - Countdown to the next episode (Live Activity)

@MainActor
enum EpisodeCountdown {
    static var enabled: Bool {
        get { UserDefaults.standard.object(forKey: "countdown.enabled") as? Bool ?? true }
        set {
            UserDefaults.standard.set(newValue, forKey: "countdown.enabled")
            if !newValue { endAll() }
        }
    }

    /// Starts countdowns for followed titles airing in the next few hours; ends finished ones.
    /// Live Activities can only be started while the app is open, so this runs on activation.
    static func refresh(library: LibraryStore) async {
        let now = Date()
        for a in Activity<EpisodeCountdownAttributes>.activities where a.contentState.airAt.addingTimeInterval(3600) < now {
            await a.end(dismissalPolicy: .immediate)
        }
        guard enabled, ActivityAuthorizationInfo().areActivitiesEnabled,
              let items = try? await ShikiAPI.calendar() else { return }
        let followed = Set(library.watchingTitles.compactMap(\.shikiId))
        let soon = items.filter { followed.contains($0.shikiId) && $0.nextEpisodeAt > now
            && $0.nextEpisodeAt < now.addingTimeInterval(6 * 3600) }
            .sorted { $0.nextEpisodeAt < $1.nextEpisodeAt }.prefix(2)
        let running = Activity<EpisodeCountdownAttributes>.activities
        for item in soon {
            if running.contains(where: { $0.attributes.shikiId == item.shikiId && $0.contentState.episode == item.nextEpisode }) { continue }
            let attrs = EpisodeCountdownAttributes(title: item.title, shikiId: item.shikiId, cover: item.image?.absoluteString)
            let state = EpisodeCountdownAttributes.ContentState(airAt: item.nextEpisodeAt, episode: item.nextEpisode)
            do {
                if #available(iOS 16.2, *) {
                    _ = try Activity.request(attributes: attrs, content: ActivityContent(state: state, staleDate: item.nextEpisodeAt.addingTimeInterval(3600)))
                } else {
                    _ = try Activity.request(attributes: attrs, contentState: state)
                }
                DebugLog.log("countdown: \(item.title) ep \(item.nextEpisode)")
            } catch {
                DebugLog.log("countdown: \(error.localizedDescription)")
            }
        }
    }

    static func endAll() {
        Task { for a in Activity<EpisodeCountdownAttributes>.activities { await a.end(dismissalPolicy: .immediate) } }
    }
}

// MARK: - Handoff

enum HandoffSeek {
    static let activityType = "ru.qmods.anixq.watching"
    private static var pending: (episodeId: Int, position: Double)?

    /// Position handed over from another device, used once when that episode loads.
    static func set(episodeId: Int, position: Double) { pending = (episodeId, position) }

    static func take(episodeId: Int) -> Double? {
        guard let p = pending, p.episodeId == episodeId else { return nil }
        pending = nil
        return p.position
    }
}

// MARK: - Episodes in the iOS Calendar

@MainActor
enum CalendarSync {
    private static let store = EKEventStore()

    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: "calendar.enabled") }
        set { UserDefaults.standard.set(newValue, forKey: "calendar.enabled") }
    }

    /// "shikiId-episode" keys already added (write-only access can't read the calendar back).
    private static var added: Set<String> {
        get { Set(UserDefaults.standard.stringArray(forKey: "calendar.added") ?? []) }
        set { UserDefaults.standard.set(Array(newValue.suffix(400)), forKey: "calendar.added") }
    }

    static func requestAccess() async -> Bool {
        if #available(iOS 17.0, *) {
            return (try? await store.requestWriteOnlyAccessToEvents()) ?? false
        } else {
            return (try? await store.requestAccess(to: .event)) ?? false
        }
    }

    /// Adds the next episode of every title in "Смотрю" (Shikimori calendar) with an alert.
    @discardableResult
    static func sync(library: LibraryStore, force: Bool = false) async -> Int {
        guard enabled || force else { return 0 }
        guard await requestAccess(), let calendar = store.defaultCalendarForNewEvents,
              let items = try? await ShikiAPI.calendar() else { return 0 }
        let followed = Set(library.watchingTitles.compactMap(\.shikiId))
        var done = added
        var count = 0
        for item in items where followed.contains(item.shikiId) && item.nextEpisodeAt > Date() {
            let key = "\(item.shikiId)-\(item.nextEpisode)"
            guard !done.contains(key) else { continue }
            let e = EKEvent(eventStore: store)
            e.calendar = calendar
            e.title = "\(item.title) — серия \(item.nextEpisode)"
            e.startDate = item.nextEpisodeAt
            e.endDate = item.nextEpisodeAt.addingTimeInterval(24 * 60)
            e.notes = "Добавлено из AnixQ. Озвучка обычно выходит через 1–3 часа после премьеры."
            e.url = URL(string: "anixq://shiki/\(item.shikiId)")
            e.addAlarm(EKAlarm(relativeOffset: 0))
            do {
                try store.save(e, span: .thisEvent)
                done.insert(key)
                count += 1
            } catch {
                DebugLog.log("calendar: \(error.localizedDescription)")
            }
        }
        added = done
        if count > 0 { DebugLog.log("calendar: added \(count) episodes") }
        return count
    }
}
