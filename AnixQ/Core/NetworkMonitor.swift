import Foundation
import Network
import UIKit

/// App-wide connectivity state (for the offline banner).
@MainActor
final class NetworkMonitor: ObservableObject {
    static let shared = NetworkMonitor()
    @Published private(set) var isOnline = true
    private(set) var isWiFi = false
    private(set) var isExpensive = false
    private let monitor = NWPathMonitor()

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            let wifi = path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet)
            let expensive = path.isExpensive || path.isConstrained
            Task { @MainActor in
                guard let self else { return }
                self.isWiFi = wifi
                self.isExpensive = expensive
                guard self.isOnline != online else { return }
                self.isOnline = online
                if online { NotificationCenter.default.post(name: .anixqNetworkBack, object: nil) }
                DebugLog.log("network: \(online ? "online" : "offline")")
            }
        }
        monitor.start(queue: DispatchQueue(label: "app.net.monitor"))
    }
}

enum Haptics {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
}

extension Notification.Name {
    static let anixqNetworkBack = Notification.Name("anixqNetworkBack")
}
