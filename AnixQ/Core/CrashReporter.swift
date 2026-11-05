import Foundation
import Darwin

/// Writes a crash report (call stack + recent log lines) to Documents/crash.log so the user can
/// send it from Settings → Diagnostics after the next launch.
enum CrashReporter {
    static let fileURL: URL = {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("crash.log")
    }()

    private static var installed = false

    static func install() {
        guard !installed else { return }
        installed = true
        NSSetUncaughtExceptionHandler { e in
            CrashReporter.write(reason: "Exception \(e.name.rawValue): \(e.reason ?? "")",
                                stack: e.callStackSymbols)
        }
        for sig in [SIGABRT, SIGILL, SIGSEGV, SIGFPE, SIGBUS, SIGTRAP] {
            signal(sig) { s in
                CrashReporter.write(reason: "Signal \(s)", stack: Thread.callStackSymbols)
                signal(s, SIG_DFL)
                raise(s)
            }
        }
    }

    static func write(reason: String, stack: [String]) {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        var text = "Сбой \(f.string(from: Date()))\n\(reason)\n\n"
        text += stack.prefix(60).joined(separator: "\n")
        text += "\n\nПоследние записи журнала:\n" + DebugLog.recentLines().joined(separator: "\n")
        try? text.data(using: .utf8)?.write(to: fileURL, options: .atomic)
    }

    static var lastReport: String? {
        try? String(contentsOf: fileURL, encoding: .utf8)
    }

    static func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }
}
