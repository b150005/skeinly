import SwiftUI
import Shared

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

        for dir in searchDirs {
            for name in dbNames {
                let url = dir.appendingPathComponent(name)
                try? fileManager.removeItem(at: url)
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            AppRootView()
        }
    }
}
