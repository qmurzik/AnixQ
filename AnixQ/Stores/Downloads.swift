import Foundation
import SwiftUI
import UIKit
import ActivityKit

struct DownloadItem: Codable, Identifiable, Hashable {
    enum State: String, Codable { case queued, downloading, done, failed }

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
                for (key, item) in self.items where (item.state == .downloading || item.state == .queued)
                    && self.tasks[key] == nil {
                    self.items[key]?.state = .failed
                    self.items[key]?.error = "Прервано"
                }
                self.persist()
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

    // MARK: Actions

    func download(title: Title, episode: LibEpisode, team: LibTeam) {
        let key = DownloadItem.key(episodeId: episode.id, teamId: team.id)
        if let existing = items[key], existing.state == .done || existing.state == .downloading { return }
        Haptics.tap()
        items[key] = DownloadItem(title: title, episode: episode, team: team, quality: "", state: .queued,
                                  progress: 0, bytes: 0, fileName: nil, error: nil, date: Date())
        persist()
        Task { await start(key) }
    }

    private func start(_ key: String) async {
        guard let item = items[key] else { return }
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
            var req = URLRequest(url: url)
            req.setValue(LibAPI.site + "/", forHTTPHeaderField: "Referer")
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
        }
    }

    func retry(_ item: DownloadItem) {
        items[item.id]?.state = .queued
        Task { await start(item.id) }
    }

    func cancel(_ item: DownloadItem) {
        tasks[item.id]?.cancel()
        tasks[item.id] = nil
        delete(item)
    }

    func delete(_ item: DownloadItem) {
        if let name = item.fileName { try? FileManager.default.removeItem(at: folder.appendingPathComponent(name)) }
        tasks[item.id]?.cancel()
        tasks[item.id] = nil
        items[item.id] = nil
        persist()
    }

    func deleteAll(titleId: Int) {
        items.values.filter { $0.title.id == titleId }.forEach(delete)
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
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let key = task.taskDescription else { return }
        Task { @MainActor in
            if (error as? URLError)?.code == .cancelled { return }
            self.items[key]?.state = .failed
            self.items[key]?.error = error.localizedDescription
            self.tasks[key] = nil
            self.persist()
        }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in
            self.backgroundCompletion?()
            self.backgroundCompletion = nil
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        Task { @MainActor in DownloadManager.shared.backgroundCompletion = completionHandler }
    }
}
