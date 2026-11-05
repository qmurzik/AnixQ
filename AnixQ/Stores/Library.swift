import Foundation
import SwiftUI
import WidgetKit

struct WatchEntry: Codable, Identifiable, Hashable {
    var title: Title
    var episodeId: Int
    var episodeNumber: String
    var episodeName: String
    var teamId: Int
    var teamName: String
    var position: Double
    var duration: Double
    var date: Date

    var id: Int { title.id }
    var progress: Double { duration > 0 ? min(1, position / duration) : 0 }
}

struct PlaybackPosition: Codable, Hashable {
    var position: Double
    var duration: Double
    var date: Date
}

struct LocalBookmark: Codable, Hashable {
    var title: Title
    var status: BookmarkStatus
    var remoteId: Int?
    var date: Date
}

/// Everything the user did, stored on device and mirrored to AnimeLib / Shikimori when signed in.
@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var history: [WatchEntry] = []
    @Published private(set) var positions: [Int: PlaybackPosition] = [:]        // episodeId
    @Published private(set) var watched: [Int: Set<String>] = [:]               // titleId -> episode numbers
    @Published private(set) var bookmarks: [String: LocalBookmark] = [:]        // slug
    @Published private(set) var isSyncing = false
    /// titleId -> genre names, remembered when a title page is opened (for statistics).
    @Published private(set) var genres: [Int: [String]] = [:]
    @Published var lastSyncError: String?
    /// Shikimori import progress: (matched so far, total), nil when idle.
    @Published private(set) var shikiImport: (done: Int, total: Int)?
    @Published private(set) var shikiImportSummary: String?

    private struct Snapshot: Codable {
        var history: [WatchEntry]
        var positions: [Int: PlaybackPosition]
        var watched: [Int: Set<String>]
        var bookmarks: [String: LocalBookmark]
        var genres: [Int: [String]]? = nil
    }

    private let fileURL: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("library.json")
    }()

    private var saveTask: Task<Void, Never>?

    /// The app's single store (for background tasks).
    static weak var current: LibraryStore?

    init() {
        defer { LibraryStore.current = self }
        if let data = try? Data(contentsOf: fileURL),
           let s = try? JSONDecoder().decode(Snapshot.self, from: data) {
            history = s.history
            positions = s.positions
            watched = s.watched
            bookmarks = s.bookmarks
            genres = s.genres ?? [:]
        }
    }

    private var lastWidgetPush = Date.distantPast

    private func pushWidgetData() {
        guard Date().timeIntervalSince(lastWidgetPush) > 20 else { return }
        lastWidgetPush = Date()
        let items = history.prefix(5).map {
            WidgetShared.ContinueItem(title: $0.title.title, slug: $0.title.slugURL, episode: $0.episodeNumber,
                                      team: $0.teamName, progress: $0.progress, cover: $0.title.cover)
        }
        let followed = watchingTitles + history.prefix(30).map(\.title)
        let ids = followed.compactMap(\.shikiId)
        var covers: [Int: String] = [:]
        var slugs: [Int: String] = [:]
        for t in followed { if let sid = t.shikiId { covers[sid] = t.coverThumb ?? t.cover; slugs[sid] = t.slugURL } }
        WidgetShared.write(.init(continueItems: Array(items), watchingShikiIds: Array(Set(ids)), updated: Date(),
                                 covers: covers, slugs: slugs))
        WidgetCenter.shared.reloadAllTimelines()
        reindexSpotlightIfNeeded()
    }

    private var spotlightSignature = 0

    /// Re-index system search only when the set of titles actually changed.
    private func reindexSpotlightIfNeeded() {
        var hasher = Hasher()
        for id in Set(bookmarks.values.map(\.title.id)).union(history.map(\.title.id)).sorted() { hasher.combine(id) }
        let sig = hasher.finalize()
        guard sig != spotlightSignature else { return }
        spotlightSignature = sig
        SpotlightIndexer.update(bookmarks.values.map(\.title) + history.map(\.title))
    }

    private func save() {
        pushWidgetData()
        saveTask?.cancel()
        let snapshot = Snapshot(history: history, positions: positions, watched: watched, bookmarks: bookmarks,
                                genres: genres)
        let url = fileURL
        saveTask = Task.detached(priority: .utility) {
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled, let data = try? JSONEncoder().encode(snapshot) else { return }
            try? data.write(to: url, options: .atomic)
        }
    }

    // MARK: Playback progress

    func updateProgress(title: Title, episode: LibEpisode, team: LibTeam, position: Double, duration: Double) {
        guard position.isFinite, duration.isFinite, duration > 0 else { return }
        positions[episode.id] = PlaybackPosition(position: position, duration: duration, date: Date())
        if positions.count > 3500 {
            // Keep the 3000 most recent positions.
            let keep = positions.sorted { $0.value.date > $1.value.date }.prefix(3000)
            positions = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
        }
        let entry = WatchEntry(title: title, episodeId: episode.id, episodeNumber: episode.number,
                               episodeName: episode.displayTitle, teamId: team.id, teamName: team.name,
                               position: position, duration: duration, date: Date())
        history.removeAll { $0.title.id == title.id }
        history.insert(entry, at: 0)
        if history.count > 300 { history.removeLast(history.count - 300) }
        save()
    }

    /// Records that an episode was opened without a known position (Kodik web player), so it shows
    /// up in "Продолжить просмотр".
    func touch(title: Title, episode: LibEpisode, team: LibTeam) {
        let previous = lastEntry(titleId: title.id)
        let entry = WatchEntry(title: title, episodeId: episode.id, episodeNumber: episode.number,
                               episodeName: episode.displayTitle, teamId: team.id, teamName: team.name,
                               position: previous?.episodeId == episode.id ? previous?.position ?? 0 : 0,
                               duration: previous?.episodeId == episode.id ? previous?.duration ?? 0 : 0,
                               date: Date())
        history.removeAll { $0.title.id == title.id }
        history.insert(entry, at: 0)
        save()
    }

    /// Position to resume from (nil when the episode was finished or barely started).
    func resumePosition(episodeId: Int) -> Double? {
        guard let p = positions[episodeId], p.position > 15, p.duration > 0, p.position / p.duration < 0.92 else {
            return nil
        }
        return p.position
    }

    func progress(episodeId: Int) -> Double {
        guard let p = positions[episodeId], p.duration > 0 else { return 0 }
        return min(1, p.position / p.duration)
    }

    func recordGenres(titleId: Int, names: [String]) {
        guard !names.isEmpty, genres[titleId] != names else { return }
        genres[titleId] = names
        save()
    }

    func lastEntry(titleId: Int) -> WatchEntry? {
        history.first { $0.title.id == titleId }
    }

    func isWatched(titleId: Int, number: String) -> Bool {
        watched[titleId]?.contains(number) ?? false
    }

    /// Marks an episode watched locally and pushes progress to AnimeLib / Shikimori.
    func markWatched(title: Title, episode: LibEpisode, totalEpisodes: Int) {
        let already = watched[title.id]?.contains(episode.number) ?? false
        watched[title.id, default: []].insert(episode.number)
        save()
        WatchingUpdater.shared.episodeWatched(titleId: title.id)
        guard !already else { return }
        let isLast = totalEpisodes > 0 && Int(episode.numberValue) >= totalEpisodes && !title.isOngoing
        let target: BookmarkStatus = isLast ? .completed : .watching
        let current = bookmarks[title.slugURL]?.status
        if current == nil || current == .planned || current == .onHold || (isLast && current == .watching) {
            setBookmark(title: title, status: target)
        }
        let count = Int(episode.numberValue)
        Task { await ShikiSync.shared.episodeWatched(title: title, episodes: count, completed: isLast) }
    }

    func toggleWatched(title: Title, number: String) {
        if watched[title.id]?.contains(number) == true {
            watched[title.id]?.remove(number)
        } else {
            watched[title.id, default: []].insert(number)
        }
        save()
    }

    func removeFromHistory(_ entry: WatchEntry) {
        history.removeAll { $0.title.id == entry.title.id }
        save()
    }

    func clearHistory() {
        history = []
        positions = [:]
        watched = [:]
        save()
    }

    // MARK: Bookmarks

    func bookmark(for slug: String) -> BookmarkStatus? { bookmarks[slug]?.status }

    func titles(in status: BookmarkStatus) -> [Title] {
        bookmarks.values.filter { $0.status == status }.sorted { $0.date > $1.date }.map(\.title)
    }

    var watchingTitles: [Title] { titles(in: .watching) + titles(in: .rewatching) }

    func setBookmark(title: Title, status: BookmarkStatus?) {
        Haptics.tap()
        let previous = bookmarks[title.slugURL]
        if let status {
            bookmarks[title.slugURL] = LocalBookmark(title: title, status: status, remoteId: previous?.remoteId, date: Date())
        } else {
            bookmarks[title.slugURL] = nil
        }
        save()
        guard LibSession.isLoggedIn else { return }
        Task {
            do {
                if let status {
                    try await LibAPI.setBookmark(slug: title.slugURL, status: status)
                } else if let rid = previous?.remoteId {
                    try await LibAPI.removeBookmark(id: rid)
                }
                DebugLog.log("lib: bookmark \(title.slugURL) -> \(status?.title ?? "removed")")
            } catch {
                DebugLog.log("lib: bookmark sync failed \(error)")
                lastSyncError = "AnimeLib: не удалось сохранить закладку (\(error.localizedDescription))"
            }
            if let status { await ShikiSync.shared.setStatus(title: title, status: status) }
        }
    }

    /// Pulls the user's Shikimori list: every title is matched to AnimeLib (cached), put into the
    /// matching folder (unless the user already has an AnimeLib bookmark for it) and its watched
    /// episodes are marked. Nothing is pushed back, so this is safe to run repeatedly.
    private var lastShikiImport = Date.distantPast
    private var lastBookmarkSync = Date.distantPast

    func importShikimori(force: Bool = false) async {
        guard ShikiSession.isLoggedIn, shikiImport == nil else { return }
        guard force || Date().timeIntervalSince(lastShikiImport) > 6 * 3600 else { return }
        lastShikiImport = Date()
        let rates: [ShikiAPI.RemoteRate]
        do {
            rates = try await ShikiAPI.animeRates()
        } catch {
            lastSyncError = "Shikimori: \(error.localizedDescription)"
            DebugLog.log("shiki: import failed \(error)")
            return
        }
        DebugLog.log("shiki: importing \(rates.count) titles")
        shikiImport = (0, rates.count)
        var added = 0, matched = 0, missing = 0
        // Active lists first so "Смотрю" fills up quickly.
        let order: [String: Int] = ["watching": 0, "rewatching": 1, "on_hold": 2, "planned": 3, "completed": 4, "dropped": 5]
        let sorted = rates.sorted { (order[$0.status] ?? 9) < (order[$1.status] ?? 9) }
        for (i, rate) in sorted.enumerated() {
            if let title = await LibAPI.title(shikiId: rate.shikiId, names: [rate.name, rate.russian ?? ""]) {
                matched += 1
                if bookmarks[title.slugURL] == nil, let status = BookmarkStatus(shikiStatus: rate.status) {
                    bookmarks[title.slugURL] = LocalBookmark(title: title, status: status, remoteId: nil,
                                                             date: Date().addingTimeInterval(-Double(i)))
                    added += 1
                }
                if rate.episodes > 0 {
                    var set = watched[title.id] ?? []
                    for n in 1...rate.episodes { set.insert(String(n)) }
                    watched[title.id] = set
                }
            } else {
                missing += 1
            }
            shikiImport = (i + 1, rates.count)
            if i % 10 == 9 { save() }
        }
        save()
        shikiImport = nil
        shikiImportSummary = "Shikimori: \(rates.count) тайтлов, найдено на AnimeLib \(matched), добавлено в закладки \(added)"
            + (missing > 0 ? ", не найдено \(missing)" : "")
        DebugLog.log(shikiImportSummary ?? "")
    }

    /// Pulls every bookmark folder from AnimeLib; remote state wins.
    func syncBookmarks(force: Bool = false) async {
        guard LibSession.isLoggedIn, !isSyncing else { return }
        guard force || Date().timeIntervalSince(lastBookmarkSync) > 10 * 60 else { return }
        lastBookmarkSync = Date()
        isSyncing = true
        defer { isSyncing = false }
        if LibSession.userId == nil { await LibAPI.loadProfile() }
        var remote: [String: LocalBookmark] = [:]
        do {
            for status in BookmarkStatus.allCases {
                var page = 1
                while page <= 20 {
                    let items = try await LibAPI.bookmarks(status, page: page)
                    for (i, item) in items.enumerated() {
                        // Keep the local date so folder order is stable; new ones follow server order.
                        let date = bookmarks[item.title.slugURL]?.date
                            ?? Date().addingTimeInterval(-Double((page - 1) * 100 + i))
                        remote[item.title.slugURL] = LocalBookmark(title: item.title, status: status,
                                                                   remoteId: item.bookmarkId, date: date)
                    }
                    if items.count < 20 { break }
                    page += 1
                }
            }
            // Keep local-only bookmarks that never reached the server.
            for (slug, local) in bookmarks where remote[slug] == nil && local.remoteId == nil {
                remote[slug] = local
            }
            bookmarks = remote
            save()
            lastSyncError = nil
            DebugLog.log("lib: synced \(remote.count) bookmarks")
        } catch {
            lastSyncError = "AnimeLib: \(error.localizedDescription)"
            DebugLog.log("lib: bookmark sync error \(error)")
        }
    }
}
