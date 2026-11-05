import Foundation

/// Tiny timestamped log written to Documents/debug.log. Used by the CI player test to measure
/// how long each step takes on a real simulator; harmless in normal use (it is truncated on launch).
enum DebugLog {
    private static let start = Date()
    private static let queue = DispatchQueue(label: "debuglog")
    private static let url: URL = {
        // Caches, not Documents: Documents is visible in the Files app (backups live there).
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let file = dir.appendingPathComponent("debug.log")
        try? Data().write(to: file)
        return file
    }()

    static var fileURL: URL { url }

    static func read() -> String {
        queue.sync {}
        return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
    }

    private static var ring: [String] = []
    private static let ringLock = NSLock()

    /// Last lines kept in memory (used by the crash reporter).
    static func recentLines() -> [String] {
        ringLock.lock(); defer { ringLock.unlock() }
        return ring
    }

    /// Tokens, cookies and secrets never reach the log (it can be attached to bug reports).
    static func redact(_ s: String) -> String {
        var out = s
        let patterns = [#"(?i)(bearer\s+)[A-Za-z0-9._\-]+"#,
                        #"(?i)((?:access_|refresh_)?token["']?\s*[=:]\s*["']?)[A-Za-z0-9._\-]+"#,
                        #"(?i)(client_secret["']?\s*[=:]\s*["']?)[A-Za-z0-9._\-]+"#,
                        #"(?i)(_kawai_session|remember_user_token|cookie)=[^;\s]+"#,
                        #"(?i)(code=)[A-Za-z0-9._\-]{10,}"#]
        for p in patterns {
            out = out.replacingOccurrences(of: p, with: "$1•••", options: .regularExpression)
        }
        return out
    }

    static func log(_ message: String) {
        let line = String(format: "[%7.2fs] ", Date().timeIntervalSince(start)) + redact(message) + "\n"
        ringLock.lock()
        ring.append(String(line.dropLast()))
        if ring.count > 40 { ring.removeFirst(ring.count - 40) }
        ringLock.unlock()
        #if DEBUG
        print(line, terminator: "")
        #endif
        queue.async {
            guard let data = line.data(using: .utf8) else { return }
            if let handle = try? FileHandle(forWritingTo: url) {
                handle.seekToEndOfFile()
                handle.write(data)
                try? handle.close()
            }
        }
    }
}
