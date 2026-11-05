import Foundation
import ActivityKit

/// Live Activity shown while episodes are downloading.
struct DownloadActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var title: String
        var detail: String
        var progress: Double
        var remaining: Int
    }
    var started: Date
}
