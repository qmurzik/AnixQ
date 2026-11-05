import Foundation
import AVFoundation

/// Player settings remembered per title: dub, quality, speed, delays, subtitles.
struct TitlePlayerSettings: Codable, Equatable {
    var teamId: Int?
    var qualityHeight: Int?          // 0 = auto
    var rate: Float?
    var subtitleDelay: Double = 0    // seconds, + shows later
    var audioDelay: Double = 0       // seconds, + plays sound later
    var subtitleLabel: String?
    var subtitlesOff: Bool?

    private static let key = "titleSettings"

    static func all() -> [String: TitlePlayerSettings] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let d = try? JSONDecoder().decode([String: TitlePlayerSettings].self, from: data) else { return [:] }
        return d
    }

    static func load(_ titleId: Int) -> TitlePlayerSettings { all()[String(titleId)] ?? TitlePlayerSettings() }

    static func save(_ s: TitlePlayerSettings, for titleId: Int) {
        var d = all()
        d[String(titleId)] = s
        if let data = try? JSONEncoder().encode(d) { UserDefaults.standard.set(data, forKey: key) }
    }

    static func replaceAll(_ d: [String: TitlePlayerSettings]) {
        if let data = try? JSONEncoder().encode(d) { UserDefaults.standard.set(data, forKey: key) }
    }
}

enum AudioOffset {
    /// Progressive MP4 with the audio track shifted against the video. Nil if tracks can't be read.
    static func item(for asset: AVURLAsset, delay: Double) async -> AVPlayerItem? {
        guard let video = try? await asset.loadTracks(withMediaType: .video).first,
              let audio = try? await asset.loadTracks(withMediaType: .audio).first,
              let duration = try? await asset.load(.duration), duration.seconds > 0 else { return nil }
        let comp = AVMutableComposition()
        let full = CMTimeRange(start: .zero, duration: duration)
        guard let v = comp.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              let a = comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else { return nil }
        do {
            try v.insertTimeRange(full, of: video, at: .zero)
            if let t = try? await video.load(.preferredTransform) { v.preferredTransform = t }
            let shift = CMTime(seconds: abs(delay), preferredTimescale: 600)
            if delay >= 0 {
                // Sound later: audio starts `delay` into the timeline.
                try a.insertTimeRange(CMTimeRange(start: .zero, duration: duration - shift), of: audio, at: shift)
            } else {
                // Sound earlier: skip the first `delay` seconds of audio.
                try a.insertTimeRange(CMTimeRange(start: shift, duration: duration - shift), of: audio, at: .zero)
            }
        } catch {
            return nil
        }
        return AVPlayerItem(asset: comp)
    }
}

/// Rough bitrate each height needs (bits per second) for the automatic quality choice.
enum QualityBudget {
    static func need(_ height: Int) -> Double {
        switch height {
        case 1080...: return 4_500_000
        case 720...: return 2_300_000
        case 480...: return 1_200_000
        default: return 700_000
        }
    }
}
