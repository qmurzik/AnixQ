import SwiftUI
import UIKit
import LocalAuthentication

/// Face ID / Touch ID / passcode lock for the whole app and for chosen bookmark folders.
@MainActor
final class AppLock: ObservableObject {
    static let shared = AppLock()

    @Published private(set) var locked = AppLock.appLockEnabled {
        didSet { updateWindows() }
    }
    /// Folders opened during this session (locked again when the app goes to the background).
    @Published private(set) var unlockedFolders: Set<Int> = []
    /// Covers the UI in the app switcher.
    @Published var obscured = false {
        didSet { updateWindows() }
    }
    @Published var lastError: String?
    private var authenticating = false

    static var appLockEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: "lock.app") }
        set { UserDefaults.standard.set(newValue, forKey: "lock.app") }
    }

    static var lockedFolders: Set<Int> {
        get { Set(UserDefaults.standard.array(forKey: "lock.folders") as? [Int] ?? []) }
        set { UserDefaults.standard.set(Array(newValue), forKey: "lock.folders") }
    }

    static var biometryName: String {
        let ctx = LAContext()
        _ = ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
        switch ctx.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        default: return "код-пароль"
        }
    }

    func isLocked(_ folder: BookmarkStatus) -> Bool {
        Self.lockedFolders.contains(folder.rawValue) && !unlockedFolders.contains(folder.rawValue)
    }

    func authenticate(_ reason: String) async -> Bool {
        guard !authenticating else { return false }
        authenticating = true
        defer { authenticating = false }
        let ctx = LAContext()
        ctx.localizedFallbackTitle = "Ввести код-пароль"
        var error: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            // Only a device without any passcode has nothing to check against; every other error
            // (lockout, unavailable biometry, system failure) keeps things locked.
            if (error as? LAError)?.code == .passcodeNotSet {
                lastError = nil
                return true
            }
            lastError = error?.localizedDescription ?? "Проверка недоступна"
            DebugLog.log("lock: cannot evaluate \(error?.code ?? 0)")
            return false
        }
        do {
            let ok = try await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
            lastError = ok ? nil : "Не удалось подтвердить"
            return ok
        } catch {
            let code = (error as? LAError)?.code
            lastError = code == .userCancel || code == .appCancel || code == .systemCancel ? nil : error.localizedDescription
            return false
        }
    }

    /// Whether a lock can be turned on at all (a device passcode is required).
    static var canLock: Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
    }

    // MARK: Cover window

    /// The lock lives in its own window above everything — sheets, full-screen players and
    /// alerts presented by the app can't end up on top of it.
    private var windows: [UIWindow] = []

    private func updateWindows() {
        let show = locked || obscured
        if show {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            for scene in scenes where !windows.contains(where: { $0.windowScene === scene }) {
                let w = UIWindow(windowScene: scene)
                w.windowLevel = .alert + 1
                let host = UIHostingController(rootView: LockCover())
                host.view.backgroundColor = .clear
                w.rootViewController = host
                w.backgroundColor = .clear
                w.isHidden = false
                windows.append(w)
            }
        } else {
            windows.forEach { $0.isHidden = true }
            windows = []
        }
    }

    func unlockApp() async {
        guard locked else { return }
        if await authenticate("Разблокировать AnixQ") { locked = false }
    }

    func unlock(_ folder: BookmarkStatus) async {
        if await authenticate("Открыть папку «\(folder.title)»") { unlockedFolders.insert(folder.rawValue) }
    }

    func sceneChanged(_ phase: ScenePhase) {
        switch phase {
        case .background:
            if Self.appLockEnabled { locked = true }
            unlockedFolders = []
            obscured = Self.appLockEnabled
        case .inactive:
            obscured = Self.appLockEnabled
        case .active:
            obscured = false
        @unknown default:
            break
        }
    }
}

private struct LockCover: View {
    @ObservedObject private var lock = AppLock.shared

    var body: some View {
        if lock.locked {
            LockScreen()
        } else {
            Rectangle().fill(.ultraThickMaterial).ignoresSafeArea()
        }
    }
}

struct LockScreen: View {
    @ObservedObject private var lock = AppLock.shared

    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThickMaterial).ignoresSafeArea()
            VStack(spacing: 18) {
                Image("AppIcon-Preview").resizable().frame(width: 84, height: 84)
                    .clipShape(RoundedRectangle(cornerRadius: 19, style: .continuous))
                Text("AnixQ заблокирован").font(.title3.bold())
                if lock.locked {
                    Button {
                        Task { await lock.unlockApp() }
                    } label: {
                        Label("Разблокировать", systemImage: "faceid").padding(.horizontal, 20)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .frame(maxWidth: 260)
                    if let e = lock.lastError {
                        Text(e).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.horizontal)
                    }
                }
            }
        }
        .task { await lock.unlockApp() }
    }
}

struct LockedFolderView: View {
    let folder: BookmarkStatus
    @ObservedObject private var lock = AppLock.shared

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "lock.fill").font(.system(size: 44)).foregroundStyle(.secondary)
            Text("Папка «\(folder.title)» закрыта").font(.headline)
            Button {
                Task { await lock.unlock(folder) }
            } label: {
                Label("Открыть через \(AppLock.biometryName)", systemImage: "faceid").padding(.horizontal, 16)
            }
            .buttonStyle(PrimaryButtonStyle())
            .frame(maxWidth: 300)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task { await lock.unlock(folder) }
    }
}

struct LockSettingsView: View {
    @State private var appLock = AppLock.appLockEnabled
    @State private var folders = AppLock.lockedFolders

    var body: some View {
        Form {
            Section {
                Toggle("Блокировать AnixQ", isOn: Binding(get: { appLock }, set: { on in
                    Task {
                        guard !on || AppLock.canLock else {
                            AppLock.shared.lastError = "Сначала задайте код-пароль в Настройках iOS."
                            return
                        }
                        if await AppLock.shared.authenticate(on ? "Включить блокировку AnixQ" : "Выключить блокировку AnixQ") {
                            appLock = on
                            AppLock.appLockEnabled = on
                        }
                    }
                }))
            } footer: {
                Text(AppLock.shared.lastError ?? "При каждом открытии приложения нужен \(AppLock.biometryName). В переключателе приложений содержимое скрыто.")
            }
            Section {
                ForEach(BookmarkStatus.allCases) { s in
                    Toggle(isOn: Binding(get: { folders.contains(s.rawValue) }, set: { on in
                        Task {
                            guard await AppLock.shared.authenticate("Изменить закрытые папки") else { return }
                            if on { folders.insert(s.rawValue) } else { folders.remove(s.rawValue) }
                            AppLock.lockedFolders = folders
                        }
                    })) {
                        Label(s.title, systemImage: s.icon)
                    }
                }
            } header: {
                Text("Закрытые папки закладок")
            } footer: {
                Text("Папку можно открыть только через \(AppLock.biometryName). Количество тайтлов в ней в медиатеке не показывается.")
            }
        }
        .styledList()
        .navigationTitle("Защита")
    }
}
