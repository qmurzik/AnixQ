import Foundation
import Security

/// Secrets (tokens, cookies, client secret) live in the Keychain, not in UserDefaults.
/// Values found in UserDefaults from older versions are moved over on first read.
enum SecureStore {
    private static let service = "ru.qmods.anixq.secrets"
    private static let cacheLock = NSLock()
    private static var cache: [String: String?] = [:]
    /// Some re-signed builds have no Keychain access (errSecMissingEntitlement): keep using
    /// UserDefaults there rather than logging the user out.
    private static var keychainBroken = false

    static func string(_ key: String) -> String? {
        cacheLock.lock()
        if let hit = cache[key] { cacheLock.unlock(); return hit }
        cacheLock.unlock()
        var value = read(key)
        if value == nil, let legacy = UserDefaults.standard.string(forKey: key) {
            value = legacy
            if write(key, legacy) { UserDefaults.standard.removeObject(forKey: key) }
        }
        cacheLock.lock(); cache[key] = .some(value); cacheLock.unlock()
        return value
    }

    static func set(_ key: String, _ value: String?) {
        cacheLock.lock(); cache[key] = .some(value); cacheLock.unlock()
        if let value {
            if write(key, value) {
                UserDefaults.standard.removeObject(forKey: key)
            } else {
                UserDefaults.standard.set(value, forKey: key)
            }
        } else {
            delete(key)
            UserDefaults.standard.removeObject(forKey: key)
        }
    }

    static var usesKeychain: Bool { !keychainBroken }

    private static func base(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: key]
    }

    private static func read(_ key: String) -> String? {
        guard !keychainBroken else { return UserDefaults.standard.string(forKey: key) }
        var q = base(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        if status == errSecMissingEntitlement { keychainBroken = true; return UserDefaults.standard.string(forKey: key) }
        guard status == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    private static func write(_ key: String, _ value: String) -> Bool {
        guard !keychainBroken else { return false }
        let data = Data(value.utf8)
        let attrs: [String: Any] = [kSecValueData as String: data,
                                    // Background refresh runs after the first unlock.
                                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(base(key) as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            var add = base(key)
            add.merge(attrs) { $1 }
            status = SecItemAdd(add as CFDictionary, nil)
        }
        if status == errSecMissingEntitlement { keychainBroken = true }
        if status != errSecSuccess { DebugLog.log("keychain: write \(key) failed \(status)") }
        return status == errSecSuccess
    }

    private static func delete(_ key: String) {
        guard !keychainBroken else { return }
        SecItemDelete(base(key) as CFDictionary)
    }
}
