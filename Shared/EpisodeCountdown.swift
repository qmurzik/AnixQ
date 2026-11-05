import Foundation
import ActivityKit

/// Live Activity: countdown to the next episode of a followed title (lock screen + Dynamic Island).
struct EpisodeCountdownAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var airAt: Date
        var episode: Int
    }
    var title: String
    var shikiId: Int
    var cover: String?
}
