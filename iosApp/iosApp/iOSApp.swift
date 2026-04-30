import SwiftUI
import Shared
import Sentry
import PostHog
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

        // Phase F2: PostHog product analytics. Default OFF (opt-in, not
        // opt-out). SDK is initialized lazily on the first ON flip; toggling
        // OFF mid-session calls optOut() to suspend further capture; toggling
        // ON again calls optIn(). Mirrors SkeinlyApplication.kt's lifecycle.
        Self.observeAnalyticsOptIn()
        // Phase F.3 — bridge shared AnalyticsTracker events to PostHog.
        Self.observeAnalyticsEvents()
    }

    /// Holds the `Closeable` for the analytics opt-in observer. Static so
    /// SwiftUI's struct re-init does not drop the subscription. The observer
    /// lives for the entire app process lifetime — no `close()` needed.
    private static var analyticsOptInCloseable: Closeable?

    /// Phase F.3 — events stream subscription. Same lifetime + ownership
    /// rationale as `analyticsOptInCloseable`.
    private static var analyticsEventsCloseable: Closeable?

    /// Phase F.3 — promoted to a class-level static so both the opt-in
    /// observer and the events collector can read/write it. Both closures
    /// are dispatched via `FlowWrapper`'s Dispatchers.Main, so accesses
    /// are serialized on the iOS main thread by construction. Do NOT
    /// refactor FlowWrapper to a different dispatcher without promoting
    /// this to a thread-safe primitive — the invariant is fragile.
    private static var posthogInitialized = false

    private static func observeAnalyticsOptIn() {
        guard
            let apiKey = Bundle.main.object(forInfoDictionaryKey: "POSTHOG_API_KEY") as? String,
            !apiKey.isEmpty
        else { return }

        let host =
            (Bundle.main.object(forInfoDictionaryKey: "POSTHOG_HOST") as? String)?
                .nonEmpty ?? "https://us.i.posthog.com"

        // Idempotency guard: if observeAnalyticsOptIn() is ever called twice
        // (e.g. a future refactor moves it into `body`), close the prior
        // subscription before re-creating one — orphaning the FlowWrapper
        // scope would leak the coroutine. Mirrors the Phase 32.2/36.5
        // iOS Closeable audit pattern.
        analyticsOptInCloseable?.close()

        let prefs = KoinHelperKt.getAnalyticsPreferences()
        let wrapper = KoinHelperKt.wrapAnalyticsOptInFlow(flow: prefs.analyticsOptIn)
        analyticsOptInCloseable = wrapper.collect { value in
            // Generic FlowWrapper<Boolean> bridges to KotlinBoolean on Swift
            // side — unwrap explicitly. False on accidental nil to keep
            // analytics off (privacy default).
            let optIn = (value as? KotlinBoolean)?.boolValue ?? false
            switch (optIn, posthogInitialized) {
            case (true, false):
                let config = PostHogConfig(apiKey: apiKey, host: host)
                // Privacy-respecting defaults parallel to the Android side:
                // no screen-view ping, no app-lifecycle pings, no autocapture
                // of touch events, no session replay, no automatic feature-
                // flag-called events. Phase F+ wires explicit PostHogSDK
                // .shared.capture() calls for the events we want.
                config.captureScreenViews = false
                config.captureApplicationLifecycleEvents = false
                config.sessionReplay = false
                config.sendFeatureFlagEvent = false
                PostHogSDK.shared.setup(config)
                posthogInitialized = true
            case (false, true):
                PostHogSDK.shared.optOut()
            case (true, true):
                // Re-enable after toggling off then on in the same session.
                PostHogSDK.shared.optIn()
            default:
                break
            }
        }
    }

    /// Phase F.3 — collects shared `AnalyticsTracker.events` and forwards
    /// each emission to `PostHogSDK.shared.capture(...)`. The tracker
    /// silently no-ops captures while opt-in is OFF, so this collector
    /// only sees events the user has consented to. The
    /// `posthogInitialized` guard handles the brief window between
    /// opt-in flipping ON and the SDK setup() landing — any event that
    /// lands in that gap is silently dropped to avoid a "not initialized"
    /// SDK warning.
    private static func observeAnalyticsEvents() {
        // Idempotency guard mirrors observeAnalyticsOptIn().
        analyticsEventsCloseable?.close()
        let tracker = KoinHelperKt.getAnalyticsTracker()
        let wrapper = KoinHelperKt.wrapAnalyticsEventsFlow(flow: tracker.events)
        analyticsEventsCloseable = wrapper.collect { value in
            guard let event = value as? AnalyticsEvent else { return }
            if posthogInitialized {
                // Phase F.4 — Kotlin `Map<String, Any>?` bridges to Swift
                // `[String : Any]?`. PostHog-iOS's `capture(_:properties:)`
                // accepts `[String : Any]?` and decodes Bool / Int / Double
                // / String at the SDK boundary. Phase F.5+ promoted the
                // call sites to a sealed `AnalyticsEvent` hierarchy so
                // cardinality discipline is enforced structurally — see
                // `AnalyticsEvent` KDoc on the Kotlin side for the
                // variant catalog and contract.
                let props = event.properties as? [String: Any]
                PostHogSDK.shared.capture(event.name, properties: props)
            }
        }
    }

    private static func resetDatabase() {
        let fileManager = FileManager.default
        let dbNames = ["skeinly.db", "skeinly.db-shm", "skeinly.db-wal"]

        // NativeSqliteDriver stores the DB in Library/Application Support/databases/
        var searchDirs: [URL] = []
        if let libURL = fileManager.urls(for: .libraryDirectory, in: .userDomainMask).first {
            searchDirs.append(libURL.appendingPathComponent("Application Support/databases"))
            searchDirs.append(libURL)
        }
        if let docURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first {
            searchDirs.append(docURL)
        }

        let logger = Logger(subsystem: "io.github.b150005.skeinly", category: "database")
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

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
