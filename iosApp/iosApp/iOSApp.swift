import SwiftUI
import Shared
import Sentry
import os.log

@main
struct iOSApp: App {

    init() {
        if ProcessInfo.processInfo.arguments.contains("--reset-database") {
            Self.resetDatabase()
        }

        // Phase F1: init Sentry crash + error reporting BEFORE Koin so any
        // Koin init failure is captured. Empty DSN means local dev — skip.
        if let dsn = Bundle.main.object(forInfoDictionaryKey: "SENTRY_DSN_IOS") as? String,
           !dsn.isEmpty {
            SentrySDK.start { options in
                options.dsn = dsn
                options.releaseName =
                    Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
                // Phase F1 ships with conservative perf sampling; tune via
                // Sentry dashboard once telemetry arrives.
                options.tracesSampleRate = 0.2
                // Privacy: avoid attaching screenshots / view hierarchies that
                // could leak user-authored content into crash reports.
                options.attachScreenshot = false
                options.attachViewHierarchy = false
            }
        }

        KoinHelperKt.doInitKoin()
    }

    private static func resetDatabase() {
        let fileManager = FileManager.default
        let dbNames = ["knitnote.db", "knitnote.db-shm", "knitnote.db-wal"]

        // NativeSqliteDriver stores the DB in Library/Application Support/databases/
        var searchDirs: [URL] = []
        if let libURL = fileManager.urls(for: .libraryDirectory, in: .userDomainMask).first {
            searchDirs.append(libURL.appendingPathComponent("Application Support/databases"))
            searchDirs.append(libURL)
        }
        if let docURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first {
            searchDirs.append(docURL)
        }

        let logger = Logger(subsystem: "io.github.b150005.knitnote", category: "database")
        for dir in searchDirs {
            for name in dbNames {
                let url = dir.appendingPathComponent(name)
                do {
                    try fileManager.removeItem(at: url)
                    logger.info("Removed \(url.path)")
                } catch let error as NSError where error.domain == NSCocoaErrorDomain && error.code == NSFileNoSuchFileError {
                    // File doesn't exist — expected, no action needed
                } catch {
                    logger.error("Failed to remove \(url.path): \(error.localizedDescription)")
                }
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            AppRootView()
        }
    }
}
