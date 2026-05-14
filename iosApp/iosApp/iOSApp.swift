import SwiftUI
import Shared
import Sentry
import PostHog
import os.log

@main
struct iOSApp: App {
    /// Phase 24.2e (ADR-017 §3.5) — installs `AppDelegate` so iOS UIKit
    /// surfaces `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)`
    /// + `application(_:didFailToRegisterForRemoteNotificationsWithError:)`
    /// callbacks. SwiftUI App lifecycle does not expose UIApplicationDelegate
    /// methods directly; the adaptor is the documented bridge. The
    /// AppDelegate forwards the token (or nil on failure) to the shared
    /// `PushTokenRegistrar` via `KoinHelperKt.handleApnsTokenReceived`,
    /// which completes the `Channel<String?>` opened by
    /// `registerForPushNotifications`. See `AppDelegate.swift` for details.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

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
                // I1 (Phase 39.1): tag every event with the runtime
                // environment so Sentry dashboards / alerts / release-health
                // can filter dev vs prod cleanly. Mirrors the Android
                // SkeinlyApplication.kt configuration.
                #if DEBUG
                options.environment = "development"
                #else
                options.environment = "production"
                #endif
                // Phase F1 ships with conservative perf sampling; tune via
                // Sentry dashboard once telemetry arrives.
                options.tracesSampleRate = 0.2
                // Privacy: avoid attaching screenshots / view hierarchies that
                // could leak user-authored content into crash reports.
                options.attachScreenshot = false
                options.attachViewHierarchy = false

                // App Hang Tracking — gated per environment to keep the
                // signal/noise ratio tight before Phase 39 beta tester invites
                // land. Stack-trace pattern observed in 9 unresolved issues
                // (SKEINLY-IOS-{1,8,P,1Q,1Y,2K,2R,2S,2T} as of 2026-05-08):
                // every one is a Simulator development build hitting the
                // 2-second threshold during a) SF Symbol / SwiftUI Image
                // first-resolve via CUIStructuredThemeStore lookupAssetForKey,
                // b) UIKitToolbarStrategy first toolbar render after
                // App.main, or c) XCUITest accessibility snapshot scans
                // through NSBundle / readdir during E2E runs. None reproduce
                // on physical-device Release builds — they are first-paint
                // overhead amplified by host-CPU sharing + cold asset cache.
                //
                // - Simulator: disabled outright. False positives drown out
                //   real signal; closed-beta testers will not run Simulator.
                // - Real-device Debug: 4s threshold (vs 2s default) so
                //   debugger pauses, breakpoints, and slow first-launch
                //   asset hydration don't fire false hangs while still
                //   capturing genuine multi-second freezes for beta testers
                //   running development sideloads via TestFlight.
                // - Release: leave defaults (enabled, 2s threshold). Strict
                //   capture of production-grade hangs is the whole point of
                //   Phase 39 telemetry.
                #if targetEnvironment(simulator)
                options.enableAppHangTrackingV2 = false
                #elseif DEBUG
                options.appHangTimeoutInterval = 4.0
                #endif
            }
        }

        KoinHelperKt.doInitKoin()

        // Phase 39.3 (ADR-015 §6) — start the bug-report event trail
        // collector exactly once at app init. Not gated on `BuildFlags.isBeta`
        // — the buffer is harmless on production (it only ever fills with
        // opt-in-gated events, and Phase 39.5's bug-reporter gesture is
        // itself beta-only so production never reads `snapshot()`).
        Self.startEventRingBuffer()

        // Phase 41.3 (ADR-016 §6 §41.3) — RevenueCat IAP SDK init.
        // Idempotent; no-ops when REVENUECAT_API_KEY (Info.plist, sourced
        // from xcconfig) is empty (local-dev / CI without GitHub Secret
        // REVENUECAT_API_KEY_IOS wired). Verbose logs gated on
        // BuildFlags.isBeta so beta testers' Sentry breadcrumbs capture
        // richer purchase-flow detail. Mirrors SkeinlyApplication.kt.
        KoinHelperKt.configureRevenueCat(verbose: BuildFlags.isBeta)

        // Phase 39 closed beta prep — bridge auth state to RevenueCat
        // identity so webhook events carry the Skeinly user UUID.
        // Started AFTER `configureRevenueCat` and Koin init; the bridge
        // silently no-ops on RevenueCat errors and never blocks auth
        // flow. See `RevenueCatAuthBridge.kt` for the full rationale.
        KoinHelperKt.startRevenueCatAuthBridge()

        // Phase 26.6 (ADR-022 §6.5) — bridge process-level
        // UIApplication{WillEnterForeground,DidEnterBackground}Notification
        // to the shared BiometricGuardian. On each Background→Foreground
        // transition past the user-configured threshold, the Guardian
        // fires the OS biometric prompt via LAContext. No-ops when the
        // user hasn't opted in via Settings → Security → Biometric.
        KoinHelperKt.startBiometricLifecycleBridge()

        // Phase F2: PostHog product analytics. Default OFF (opt-in, not
        // opt-out). SDK is initialized lazily on the first ON flip; toggling
        // OFF mid-session calls optOut() to suspend further capture; toggling
        // ON again calls optIn(). Mirrors SkeinlyApplication.kt's lifecycle.
        //
        // Phase 39.3 (ADR-015 §2) — gated on `BuildFlags.isBeta` so production
        // (v1.0+) binaries never call PostHog SDK setup, even if opt-in toggles
        // ON via a stale settings payload. Mirrors the Android-side gate.
        if BuildFlags.isBeta {
            Self.observeAnalyticsOptIn()
            // Phase F.3 — bridge shared AnalyticsTracker events to PostHog.
            Self.observeAnalyticsEvents()
        }
    }

    /// Phase 39.3 (ADR-015 §6) — kicks off the EventRingBuffer collector
    /// on the shared `applicationScopeQualifier` CoroutineScope (resolved
    /// inside `KoinHelperKt.startEventRingBuffer`, so Swift never juggles
    /// the scope). The buffer itself is a Koin `single`, so the same
    /// instance services Phase 39.5's `snapshot()` calls from the
    /// bug-report flow.
    private static func startEventRingBuffer() {
        KoinHelperKt.startEventRingBuffer()
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
            // FlowWrapper<Boolean> bridges to a non-optional KotlinBoolean
            // on the Swift side — read .boolValue directly.
            let optIn = value.boolValue
            switch (optIn, posthogInitialized) {
            case (true, false):
                let config = PostHogConfig(projectToken: apiKey, host: host)
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
                // I1 (Phase 39.1): tag every event with the runtime
                // environment so dev captures filter out of prod
                // dashboards. Registered as a super property so every
                // subsequent capture inherits it without per-call boilerplate.
                #if DEBUG
                PostHogSDK.shared.register(["environment": "development"])
                #else
                PostHogSDK.shared.register(["environment": "production"])
                #endif
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
        analyticsEventsCloseable = wrapper.collect { event in
            if posthogInitialized {
                // Phase F.4 — Kotlin `Map<String, Any>?` bridges to Swift
                // `[String : Any]?`. PostHog-iOS's `capture(_:properties:)`
                // accepts `[String : Any]?` and decodes Bool / Int / Double
                // / String at the SDK boundary. Phase F.5+ promoted the
                // call sites to a sealed `AnalyticsEvent` hierarchy so
                // cardinality discipline is enforced structurally — see
                // `AnalyticsEvent` KDoc on the Kotlin side for the
                // variant catalog and contract.
                PostHogSDK.shared.capture(event.name, properties: event.properties)
            }
        }
    }

    private static func resetDatabase() {
        let fileManager = FileManager.default
        let dbNames = ["skeinly.db", "skeinly.db-shm", "skeinly.db-wal"]

        // NativeSqliteDriver default path is Library/databases/<name>; older
        // versions wrote to Library/Application Support/databases/. Walk both
        // plus Library/ root and Documents/ to cover historical layouts. See
        // touchlab/SQLiter NativeSqliteDriver for the canonical default.
        var searchDirs: [URL] = []
        if let libURL = fileManager.urls(for: .libraryDirectory, in: .userDomainMask).first {
            searchDirs.append(libURL.appendingPathComponent("databases"))
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
