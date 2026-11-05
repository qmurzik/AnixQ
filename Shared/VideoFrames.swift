import AVFoundation
import UIKit

/// Pulls a handful of evenly spaced frames out of a video (TikTok / Shorts / screen recordings)
/// so each can be recognised and the answers voted on.
enum VideoFrames {
    static func extract(_ url: URL, count: Int = 6, maxSide: CGFloat = 900) async -> [UIImage] {
        let asset = AVURLAsset(url: url)
        guard let duration = try? await asset.load(.duration), duration.seconds > 0 else { return [] }
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: maxSide, height: maxSide)
        gen.requestedTimeToleranceBefore = CMTime(seconds: 0.5, preferredTimescale: 600)
        gen.requestedTimeToleranceAfter = CMTime(seconds: 0.5, preferredTimescale: 600)
        let total = duration.seconds
        // Skip the very start and end (intros, "follow me" end cards).
        let times = (0..<count).map { i in total * (0.1 + 0.8 * Double(i) / Double(max(1, count - 1))) }
        var out: [UIImage] = []
        for t in times {
            if let cg = try? gen.copyCGImage(at: CMTime(seconds: t, preferredTimescale: 600), actualTime: nil) {
                out.append(UIImage(cgImage: cg))
            }
        }
        return out
    }
}
