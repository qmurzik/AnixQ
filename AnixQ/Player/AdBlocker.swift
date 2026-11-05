import WebKit

/// Content blocker for the embedded web players: drops requests to third-party ad networks
/// (the same approach Safari content blockers use). Pre-roll ads in Kodik come from these
/// networks and each one may hold playback for 5–20 seconds while it loads.
@MainActor
final class AdBlocker {
    static let shared = AdBlocker()

    /// On by default; the CI test can turn it off with `-adblock NO`.
    nonisolated static var isEnabled: Bool {
        UserDefaults.standard.object(forKey: "adblock") == nil ? true : UserDefaults.standard.bool(forKey: "adblock")
    }

    private var compiled: WKContentRuleList?
    private var compileTask: Task<WKContentRuleList?, Never>?

    private static let blockedDomains = [
        "buzzoola.com", "adfox.ru", "an.yandex.ru", "yandex.ru/ads", "mc.yandex.ru", "yandexadexchange.net",
        "bracdn.online", "snsv.ru", "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "imasdk.googleapis.com", "google-analytics.com", "googletagmanager.com", "adriver.ru",
        "betweendigital.com", "otm-r.com", "hybrid.ai", "mediametrics.ru", "admixer.net", "adhigh.net",
        "adlook.me", "vidroll.ru", "videonow.ru", "mytarget.ru", "ad.mail.ru", "r.mail.ru", "top-fwz1.mail.ru",
        "smi2.ru", "propellerads.com", "popads.net", "onclickads.net", "exoclick.com", "aj1907.online",
        "adstag0102.xyz", "adstag.xyz", "kodikres.com/p-e"
    ]

    private static var rulesJSON: String {
        let rules = blockedDomains.map { domain -> String in
            let escaped = domain
                .replacingOccurrences(of: ".", with: "\\\\.")
                .replacingOccurrences(of: "/", with: "\\\\/")
            return #"{"trigger":{"url-filter":"^https?://([^/]+\\.)?\#(escaped)"},"action":{"type":"block"}}"#
        }
        return "[" + rules.joined(separator: ",") + "]"
    }

    func warmUp() {
        _ = startCompile()
    }

    func rules() async -> WKContentRuleList? {
        if let compiled { return compiled }
        return await startCompile().value
    }

    private func startCompile() -> Task<WKContentRuleList?, Never> {
        if let compileTask { return compileTask }
        let json = Self.rulesJSON
        let task = Task<WKContentRuleList?, Never> { @MainActor in
            let list: WKContentRuleList? = await withCheckedContinuation { cont in
                WKContentRuleListStore.default().compileContentRuleList(
                    forIdentifier: "anixq-adblock-v1", encodedContentRuleList: json) { list, _ in
                    cont.resume(returning: list)
                }
            }
            self.compiled = list
            return list
        }
        compileTask = task
        return task
    }
}
