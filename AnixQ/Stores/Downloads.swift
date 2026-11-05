import UserNotifications
import Foundation
import SwiftUI
import UIKit
import ActivityKit

struct DownloadItem: Codable, Identifiable, Hashable {
    enum State: String, Codable { case queued, downloading, paused, done, failed }

    var title: Title
    var episode: LibEpisode
    var team: LibTeam
    var quality: String
    var state: State
    var progress: Double
    var bytes: Int64
    var fileName: String?
    var error: String?
    var date: Date
    /// Higher goes first in the queue (optional so older saved lists still decode).
    var priority: Int?

    var id: String { DownloadItem.key(episodeId: episode.id, teamId: team.id) }
    static func key(episodeId: Int, teamId: Int) -> String { "\(episodeId)-\(teamId)" }
}

/// Offline episodes. Uses a background URLSession so downloads keep going when the app is closed.
@MainActor
final class DownloadManager: NSObject, ObservableObject {
    static let shared = DownloadManager()

    @Published private(set) var items: [String: DownloadItem] = [:]

    private let indexURL: URL
    let folder: URL
    private var tasks: [String: URLSessionDownloadTask] = [:]
    private var lastPublished: [String: Date] = [:]
    var backgroundCompletion: (() -> Void)?

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: "ru.qmods.anixq.downloads")
        config.sessionSendsLaunchEvents = true
        config.isDiscretionary = false
        config.httpMaximumConnectionsPerHost = 2
        return URLSession(configuration: config, delegate: self, delegateQueue: .main)
    }()

    override init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        folder = base.appendingPathComponent("Downloads", isDirectory: true)
        indexURL = base.appendingPathComponent("downloads.json")
        super.init()
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var f = folder
        try? f.setResourceValues(values)
        if let data = try? Data(contentsOf: indexURL),
           let saved = try? JSONDecoder().decode([String: DownloadItem].self, from: data) {
            items = saved
        }
        // Re-attach to transfers that survived an app restart.
        session.getAllTasks { [weak self] list in
            Task { @MainActor in
                guard let self else { return }
                for t in list {
                    if let key = t.taskDescription, let dt = t as? URLSessionDownloadTask { self.tasks[key] = dt }
                }
                // Transfers lost with the app process go back to the queue (resuming where possible).
                for (key, item) in self.items where item.state == .downloading && self.tasks[key] == nil {
                    self.items[key]?.state = .queued
                }
                self.persist()
                self.pump()
            }
        }
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(items) { try? data.write(to: indexURL, options: .atomic) }
        updateLiveActivity()
    }

    // MARK: Live Activity

    private var activity: Activity<DownloadActivityAttributes>?
    private var lastActivityUpdate = Date.distantPast

    private func updateLiveActivity(force: Bool = false) {
        let active = items.values.filter { $0.state == .downloading || $0.state == .queued }
        if active.isEmpty {
            if let activity {
                let final = DownloadActivityAttributes.ContentState(title: "Загрузки завершены", detail: "", progress: 1, remaining: 0)
                Task { await activity.end(using: final, dismissalPolicy: .after(Date().addingTimeInterval(5))) }
                self.activity = nil
            }
            return
        }
        guard force || Date().timeIntervalSince(lastActivityUpdate) > 2 else { return }
        lastActivityUpdate = Date()
        let current = active.first { $0.state == .downloading } ?? active[0]
        let overall = active.map(\.progress).reduce(0, +) / Double(active.count)
        let state = DownloadActivityAttributes.ContentState(
            title: current.title.title,
            detail: "Серия \(current.episode.number)" + (active.count > 1 ? " · ещё \(active.count - 1)" : ""),
            progress: overall, remaining: active.count)
        if let activity {
            Task { await activity.update(using: state) }
        } else if ActivityAuthorizationInfo().areActivitiesEnabled {
            activity = try? Activity.request(attributes: DownloadActivityAttributes(started: Date()), contentState: state)
        }
    }

    // MARK: Queries

    func item(episodeId: Int, teamId: Int) -> DownloadItem? {
        items[DownloadItem.key(episodeId: episodeId, teamId: teamId)]
    }

    /// Local file for an episode (any team when `teamId` is nil).
    func localFile(episodeId: Int, teamId: Int?) -> URL? {
        let candidates = items.values.filter {
            $0.episode.id == episodeId && $0.state == .done && (teamId == nil || $0.team.id == teamId)
        }
        for c in candidates {
            if let name = c.fileName {
                let url = folder.appendingPathComponent(name)
                if FileManager.default.fileExists(atPath: url.path) { return url }
            }
        }
        return nil
    }

    var titles: [(title: Title, items: [DownloadItem])] {
        let grouped = Dictionary(grouping: items.values) { $0.title.id }
        return grouped.values.compactMap { list in
            guard let first = list.first else { return nil }
            return (first.title, list.sorted { $0.episode.numberValue < $1.episode.numberValue })
        }
        .sorted { ($0.items.map(\.date).max() ?? .distantPast) > ($1.items.map(\.date).max() ?? .distantPast) }
    }

    var totalBytes: Int64 { items.values.filter { $0.state == .done }.reduce(0) { $0 + $1.bytes } }

    // MARK: Settings

    static var maxConcurrent: Int {
        get { UserDefaults.standard.object(forKey: "dl.concurrent") as? Int ?? 2 }
        set { UserDefaults.standard.set(newValue, forKey: "dl.concurrent") }
    }
    static var wifiOnly: Bool {
        get { UserDefaults.standard.bool(forKey: "dl.wifiOnly") }
        set { UserDefaults.standard.set(newValue, forKey: "dl.wifiOnly") }
    }
    /// Storage limit in GB, 0 = none.
    static var limitGB: Double {
        get { UserDefaults.standard.double(forKey: "dl.limitGB") }
        set { UserDefaults.standard.set(newValue, forKey: "dl.limitGB") }
    }
    static var deleteWatched: Bool {
        get { UserDefaults.standard.bool(forKey: "dl.deleteWatched") }
        set { UserDefaults.standard.set(newValue, forKey: "dl.deleteWatched") }
    }

    var activeCount: Int { items.values.filter { $0.state == .downloading || $0.state == .queued }.count }
    var pausedCount: Int { items.values.filter { $0.state == .paused }.count }

    // MARK: Actions

    func download(title: Title, episode: LibEpisode, team: LibTeam, priority: Int = 0) {
        let key = DownloadItem.key(episodeId: episode.id, teamId: team.id)
        if let existing = items[key], existing.state != .failed { return }
        Haptics.tap()
        items[key] = DownloadItem(title: title, episode: episode, team: team, quality: "", state: .queued,
                                  progress: 0, bytes: 0, fileName: nil, error: nil, date: Date(), priority: priority)
        persist()
        pump()
    }

    private var starting: Set<String> = []

    /// Starts queued items, highest priority first, up to the concurrency limit.
    func pump() {
        var slots = Self.maxConcurrent - items.values.filter { $0.state == .downloading }.count - starting.count
        guard slots > 0 else { return }
        let queue = items.values.filter { $0.state == .queued && !starting.contains($0.id) }
            .sorted { ($0.priority ?? 0) != ($1.priority ?? 0) ? ($0.priority ?? 0) > ($1.priority ?? 0) : $0.date < $1.date }
        for item in queue where slots > 0 {
            slots -= 1
            starting.insert(item.id)
            Task {
                await start(item.id)
                starting.remove(item.id)
            }
        }
    }

    private func resumeURL(_ key: String) -> URL { folder.appendingPathComponent(key + ".resume") }

    private func start(_ key: String) async {
        guard let item = items[key], item.state == .queued else { return }
        guard ensureSpace() else {
            items[key]?.state = .failed
            items[key]?.error = "Достигнут лимит места для загрузок"
            persist()
            return
        }
        // Paused earlier: continue from the saved point.
        if let data = try? Data(contentsOf: resumeURL(key)) {
            try? FileManager.default.removeItem(at: resumeURL(key))
            let task = session.downloadTask(withResumeData: data)
            task.taskDescription = key
            tasks[key] = task
            items[key]?.state = .downloading
            items[key]?.error = nil
            persist()
            task.resume()
            DebugLog.log("download: resume \(item.title.title) ep \(item.episode.number)")
            return
        }
        do {
            let players = try await LibAPI.players(episodeId: item.episode.id)
            guard let p = players.first(where: { $0.team.id == item.team.id && $0.isNative }) else {
                throw LibError.server("Скачать можно только серии с плеером AnimeLib (не Kodik)")
            }
            let wanted = PlayerPrefs.downloadHeight
            let label = p.hrefs.keys.sorted { (Int($0.filter(\.isNumber)) ?? 0) > (Int($1.filter(\.isNumber)) ?? 0) }
                .first { (Int($0.filter(\.isNumber)) ?? 0) <= wanted } ?? p.hrefs.keys.first ?? ""
            guard let href = p.hrefs[label] else { throw LibError.noVideo }
            let server = await LibAPI.rankServers(await LibAPI.videoServers(), href: href).first ?? ""
            guard let url = LibAPI.videoURL(server: server, href: href) else { throw LibError.noVideo }
            guard items[key]?.state == .queued else { return }   // paused or cancelled meanwhile
            var req = URLRequest(url: url)
            req.setValue(LibAPI.site + "/", forHTTPHeaderField: "Referer")
            req.allowsCellularAccess = !Self.wifiOnly
            req.allowsExpensiveNetworkAccess = !Self.wifiOnly
            let task = session.downloadTask(with: req)
            task.taskDescription = key
            tasks[key] = task
            items[key]?.quality = label
            items[key]?.state = .downloading
            items[key]?.error = nil
            persist()
            task.resume()
            DebugLog.log("download: start \(item.title.title) ep \(item.episode.number) \(label)")
        } catch {
            items[key]?.state = .failed
            items[key]?.error = error.localizedDescription
            persist()
            pump()
        }
    }

    func pause(_ item: DownloadItem) {
        let key = item.id
        if let task = tasks[key] {
            tasks[key] = nil
            task.cancel(byProducingResumeData: { [weak self] data in
                Task { @MainActor in
                    guard let self else { return }
                    if let data { try? data.write(to: self.resumeURL(key)) }
                    self.items[key]?.state = .paused
                    self.persist()
                    self.pump()
                }
            })
        } else if item.state == .queued {
            items[key]?.state = .paused
            persist()
        }
    }

    func resume(_ item: DownloadItem) {
        guard item.state == .paused || item.state == .failed else { return }
        items[item.id]?.state = .queued
        items[item.id]?.error = nil
        persist()
        pump()
    }

    func pauseAll() { items.values.filter { $0.state == .downloading || $0.state == .queued }.forEach(pause) }
    func resumeAll() { items.values.filter { $0.state == .paused }.forEach(resume) }

    /// Moves an item to the front of the queue.
    func prioritize(_ item: DownloadItem) {
        let top = (items.values.compactMap(\.priority).max() ?? 0) + 1
        items[item.id]?.priority = top
        persist()
        pump()
    }

    func retry(_ item: DownloadItem) {
        items[item.id]?.state = .queued
        items[item.id]?.error = nil
        persist()
        pump()
    }

    func cancel(_ item: DownloadItem) {
        tasks[item.id]?.cancel()
        tasks[item.id] = nil
        delete(item)
    }

    func delete(_ item: DownloadItem) {
        if let name = item.fileName { try? FileManager.default.removeItem(at: folder.appendingPathComponent(name)) }
        try? FileManager.default.removeItem(at: resumeURL(item.id))
        tasks[item.id]?.cancel()
        tasks[item.id] = nil
        items[item.id] = nil
        persist()
        pump()
    }

    func deleteAll(titleId: Int) {
        items.values.filter { $0.title.id == titleId }.forEach(delete)
    }

    // MARK: Storage

    private func isWatched(_ item: DownloadItem) -> Bool {
        LibraryStore.current?.isWatched(titleId: item.title.id, number: item.episode.number) ?? false
    }

    /// Removes finished downloads of watched episodes (when enabled). Called when the app goes to the
    /// background, so the file that is being played right now is never pulled away.
    func cleanupWatched() {
        guard Self.deleteWatched else { return }
        let watched = items.values.filter { $0.state == .done && isWatched($0) }
        guard !watched.isEmpty else { return }
        watched.forEach(delete)
        DebugLog.log("download: removed \(watched.count) watched episodes")
    }

    /// Makes room under the limit: watched episodes go first (if allowed), oldest first.
    private func ensureSpace() -> Bool {
        guard Self.limitGB > 0 else { return true }
        let limit = Int64(Self.limitGB * 1_000_000_000)
        guard totalBytes >= limit else { return true }
        if Self.deleteWatched {
            for item in items.values.filter({ $0.state == .done && isWatched($0) }).sorted(by: { $0.date < $1.date }) {
                delete(item)
                if totalBytes < limit { return true }
            }
        }
        return totalBytes < limit
    }
}

extension DownloadManager: URLSessionDownloadDelegate {
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                                totalBytesExpectedToWrite: Int64) {
        guard let key = downloadTask.taskDescription else { return }
        let progress = totalBytesExpectedToWrite > 0 ? Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) : 0
        Task { @MainActor in
            // Throttle UI updates.
            if let last = self.lastPublished[key], Date().timeIntervalSince(last) < 0.5, progress < 1 { return }
            self.lastPublished[key] = Date()
            self.items[key]?.progress = progress
            self.items[key]?.bytes = totalBytesWritten
            self.items[key]?.state = .downloading
            self.updateLiveActivity()
        }
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didFinishDownloadingTo location: URL) {
        guard let key = downloadTask.taskDescription else { return }
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 200
        // The temp file disappears when this method returns, so move it synchronously.
        let name = key + ".mp4"
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dest = base.appendingPathComponent("Downloads", isDirectory: true).appendingPathComponent(name)
        var moved = false
        if (200..<300).contains(status) {
            try? FileManager.default.removeItem(at: dest)
            moved = (try? FileManager.default.moveItem(at: location, to: dest)) != nil
        }
        let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? Int64) ?? 0
        Task { @MainActor in
            if moved {
                self.items[key]?.state = .done
                self.items[key]?.progress = 1
                self.items[key]?.fileName = name
                self.items[key]?.bytes = size
                DebugLog.log("download: done \(key) \(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))")
            } else {
                self.items[key]?.state = .failed
                self.items[key]?.error = "Сервер ответил \(status)"
            }
            self.tasks[key] = nil
            self.persist()
            self.pump()
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let key = task.taskDescription else { return }
        Task { @MainActor in
            if (error as? URLError)?.code == .cancelled { return }
            // Network dropped: keep the partial data and retry later instead of failing.
            if let data = (error as NSError).userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
                try? data.write(to: self.resumeURL(key))
                self.items[key]?.state = .queued
                self.items[key]?.error = "Связь прервалась — продолжим"
                self.tasks[key] = nil
                self.persist()
                DispatchQueue.main.asyncAfter(deadline: .now() + 10) { self.pump() }
                return
            }
            self.items[key]?.state = .failed
            self.items[key]?.error = error.localizedDescription
            self.tasks[key] = nil
            self.persist()
            self.pump()
        }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in
            self.backgroundCompletion?()
            self.backgroundCompletion = nil
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    /// Show banners while the app is open too.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }

    /// Update → GBox; new episode → the title page.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        Task { @MainActor in
            if info["update"] != nil {
                UpdateChecker.openGBox()
            } else if let slug = info["slug"] as? String, let url = URL(string: "anixq://title/\(slug)") {
                UIApplication.shared.open(url)
            }
            completionHandler()
        }
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        Task { @MainActor in DownloadManager.shared.backgroundCompletion = completionHandler }
    }
}
