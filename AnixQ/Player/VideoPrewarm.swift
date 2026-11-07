import AVFoundation

/// Starts opening the video file of the episode you're about to watch (connection + the file's
/// header with duration and track layout) before you press Play, so the player only has to begin
/// buffering. That header fetch is most of the "ready after N s" wait on AnimeLib's mp4 files.
@MainActor
enum VideoPrewarm {
    private static var assets: [URL: (asset: AVURLAsset, at: Date)] = [:]

    static func makeAsset(_ url: URL) -> AVURLAsset {
        AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["Referer": LibAPI.site + "/"]])
    }

    static func prepare(_ url: URL) {
        guard assets[url] == nil else { return }
        let asset = makeAsset(url)
        assets[url] = (asset, Date())
        asset.loadValuesAsynchronously(forKeys: ["playable", "duration", "tracks"]) {}
        if assets.count > 4, let oldest = assets.min(by: { $0.value.at < $1.value.at })?.key { assets[oldest] = nil }
        DebugLog.log("prewarm: \(url.deletingLastPathComponent().lastPathComponent)/\(url.lastPathComponent)")
    }

    /// The prepared asset for this exact URL (fresh enough), or nil.
    static func take(_ url: URL) -> AVURLAsset? {
        guard let entry = assets.removeValue(forKey: url), Date().timeIntervalSince(entry.at) < 15 * 60 else { return nil }
        return entry.asset
    }

    /// Height the player will start with when nothing was measured yet — shared with PlayerController
    /// so the prepared file is exactly the one that gets played.
    static func startHeight(titleId: Int?) -> Int {
        let wanted = titleId.flatMap { TitlePlayerSettings.load($0).qualityHeight } ?? PlayerPrefs.preferredHeight
        if wanted != 0 { return wanted }
        return NetworkMonitor.shared.isExpensive ? 480 : NetworkMonitor.shared.isWiFi ? 1080 : 720
    }

    /// Same choice as the player: the highest quality not above [height], else the lowest.
    static func pick(_ hrefs: [String: String], height: Int) -> String? {
        let sorted = hrefs.keys.sorted { (Int($0.filter(\.isNumber)) ?? 0) > (Int($1.filter(\.isNumber)) ?? 0) }
        let label = sorted.first { (Int($0.filter(\.isNumber)) ?? 0) <= height } ?? sorted.last
        return label.flatMap { hrefs[$0] }
    }
}
