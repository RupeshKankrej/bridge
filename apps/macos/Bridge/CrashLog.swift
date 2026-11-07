import AppKit
import BridgeCore

/// Crash reporting to a local file only (no telemetry): `~/Library/Logs/Bridge/crash.log`. Catches Objective-C exceptions
/// and Kotlin exceptions escaping core. Swift traps (force-unwraps, array bounds) can't be caught safely in-process;
/// macOS already writes those to ~/Library/Logs/DiagnosticReports, which the diagnostics view links to.
enum CrashLog {
    static let url = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0].appendingPathComponent("Logs/Bridge/crash.log")
    static let systemReports = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0].appendingPathComponent("Logs/DiagnosticReports")
    private static let maxBytes = 256 * 1024

    static func install() {
        // A C function pointer: the closure can't capture anything, so it calls the static method.
        NSSetUncaughtExceptionHandler { e in
            CrashLog.append("Uncaught \(e.name.rawValue): \(e.reason ?? "")\n" + e.callStackSymbols.joined(separator: "\n"))
        }
        // AppKit otherwise just logs main-thread exceptions and carries on in an unknown state.
        UserDefaults.standard.register(defaults: ["NSApplicationCrashOnExceptions": true])
        CrashHookKt.installCrashHook { CrashLog.append("Kotlin exception in core\n" + $0) }
    }

    static func append(_ entry: String) {
        let stamp = ISO8601DateFormatter().string(from: Date())
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        // Keep the newest entries; the cap keeps a crash loop from filling the disk.
        let text = String((read() + "=== \(stamp)\n\(entry)\n").suffix(maxBytes))
        try? text.write(to: url, atomically: true, encoding: .utf8)
    }

    static func read() -> String { (try? String(contentsOf: url, encoding: .utf8)) ?? "" }

    static func clear() { try? FileManager.default.removeItem(at: url) }

    /// macOS's own reports for Bridge (Swift traps, signals), newest first.
    static func systemCrashes() -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(at: systemReports, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        return files.filter { $0.lastPathComponent.hasPrefix("Bridge") }
            .sorted { ($0.modificationDate ?? .distantPast) > ($1.modificationDate ?? .distantPast) }
    }
}

private extension URL {
    var modificationDate: Date? { try? resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate }
}
