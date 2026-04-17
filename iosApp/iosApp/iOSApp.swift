import SwiftUI
import Shared
import os.log

@main
struct iOSApp: App {

    init() {
        if ProcessInfo.processInfo.arguments.contains("--reset-database") {
            Self.resetDatabase()
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
