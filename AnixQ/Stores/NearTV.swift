import Foundation
import CoreBluetooth
import CryptoKit
import CallKit
import UserNotifications
import UIKit

/// "The iPhone is next to the TV" over Bluetooth, no Wi-Fi needed. The Android TV app sends a tiny beacon:
/// a 16-bit AnixQ service id plus 6 bytes derived from the paired channel, so only our own TV is recognised.
/// When it is heard and an episode was paused here recently, a notification offers to continue on the TV.
final class NearTV: NSObject, ObservableObject, CBCentralManagerDelegate {
    static let shared = NearTV()
    static let service = CBUUID(string: "A7E1")
    private static let restoreId = "anixq.near-tv"
    static let category = "NEAR_TV"

    static var isOn: Bool {
        get { UserDefaults.standard.bool(forKey: "nearTV") }
        set { UserDefaults.standard.set(newValue, forKey: "nearTV") }
    }

    @Published private(set) var status = "Выключено"
    private var central: CBCentralManager?

    /// Called at launch (also when iOS relaunches the app in the background for a Bluetooth match).
    func resume() { if Self.isOn { start() } }

    func setOn(_ on: Bool) {
        Self.isOn = on
        if on {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
            start()
        } else {
            central?.stopScan(); status = "Выключено"
        }
    }

    private func start() {
        if let c = central { scan(c); return }
        central = CBCentralManager(delegate: self, queue: nil, options: [CBCentralManagerOptionRestoreIdentifierKey: Self.restoreId])
    }

    private func scan(_ c: CBCentralManager) {
        guard Self.isOn else { return }
        switch c.state {
        case .poweredOn:
            guard Self.tag != nil else { status = "Сначала подключите ТВ"; return }
            // A service filter is required for scanning in the background; duplicates are not needed.
            c.scanForPeripherals(withServices: [Self.service], options: nil)
            status = "Ждёт приставку рядом"
        case .unauthorized: status = "Нет доступа к Bluetooth — разрешите в Настройках"
        case .poweredOff: status = "Bluetooth выключен"
        case .unsupported: status = "Не поддерживается"
        default: status = "Запуск…"
        }
    }

    static var tag: Data? {
        // Read directly: RemoteTV lives on the main actor, the Bluetooth callbacks may not.
        guard let ch = UserDefaults.standard.string(forKey: "rc.channel"), !ch.isEmpty else { return nil }
        return Data(SHA256.hash(data: Data("anixq-near:\(ch)".utf8)).prefix(6))
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) { scan(central) }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {}

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard let data = (advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data])?[Self.service],
              let tag = Self.tag, data.prefix(6) == tag else { return }
        Task { @MainActor in self.heard() }
    }

    // MARK: Offer

    @MainActor private func heard() {
        let d = UserDefaults.standard
        let now = Date().timeIntervalSince1970
        guard now - d.double(forKey: "nearTV.notifiedAt") > 45 * 60, let e = Self.candidate() else { return }
        // Already playing there — nothing to offer.
        if let s = RemoteTV.shared.tv, s.slug == e.title.slug, s.ep == e.episodeNumber, s.isActive { return }
        d.set(now, forKey: "nearTV.notifiedAt")

        let content = UNMutableNotificationContent()
        content.title = "Досмотреть на ТВ?"
        content.body = "\(e.title.title) · \(e.episodeNumber) серия, с \(Int(e.position / 60)) мин"
        content.sound = .default
        content.categoryIdentifier = Self.category
        content.userInfo = ["nearTV": true]
        content.interruptionLevel = .timeSensitive
        let req = UNNotificationRequest(identifier: "near-tv", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }

    @MainActor static func candidate() -> WatchEntry? {
        guard let e = LibraryStore.current?.history.first else { return nil }
        let age = Date().timeIntervalSince(e.date)
        guard age > 30, age < 6 * 3600 else { return nil }
        if e.duration > 0 && (e.progress > 0.92 || e.position < 30) { return nil }
        return e
    }

    /// The notification (or its button) was tapped: start the episode on the TV from where it stopped.
    @MainActor static func playOnTV() {
        guard let e = LibraryStore.current?.history.first else { return }
        RemoteTV.shared.play(title: e.title, episodeId: e.episodeId, teamId: e.teamId, position: e.position)
    }

    static func registerCategory() {
        let play = UNNotificationAction(identifier: "PLAY_ON_TV", title: "Включить на ТВ", options: [.foreground])
        let cat = UNNotificationCategory(identifier: category, actions: [play], intentIdentifiers: [])
        UNUserNotificationCenter.current().getNotificationCategories { existing in
            UNUserNotificationCenter.current().setNotificationCategories(existing.filter { $0.identifier != category }.union([cat]))
        }
    }
}


/// Pause the TV while a call rings or is on, then resume. iOS reports calls only while AnixQ is running
/// (open, or in the background playing audio) — that's a system limit.
final class CallPause: NSObject, CXCallObserverDelegate {
    static let shared = CallPause()
    private let observer = CXCallObserver()
    private var pausedAt: Date?

    static var isOn: Bool {
        get { UserDefaults.standard.bool(forKey: "callPause") }
        set { UserDefaults.standard.set(newValue, forKey: "callPause"); if newValue { shared.start() } }
    }

    func start() { observer.setDelegate(self, queue: .main) }

    func callObserver(_ callObserver: CXCallObserver, callChanged call: CXCall) {
        guard Self.isOn, !(UserDefaults.standard.string(forKey: "rc.channel") ?? "").isEmpty else { return }
        let active = callObserver.calls.contains { !$0.hasEnded }
        Task { @MainActor in
            if active, self.pausedAt == nil {
                if RemoteTV.shared.tv.map({ $0.state == "playing" }) ?? true {
                    RemoteTV.shared.send("pause"); self.pausedAt = Date()
                }
            } else if !active, let at = self.pausedAt {
                self.pausedAt = nil
                if Date().timeIntervalSince(at) < 3600 { RemoteTV.shared.send("resume") }
            }
        }
    }
}
