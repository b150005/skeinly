import UIKit
import UserNotifications
import Shared

/// Notification name posted when the user taps a push notification (or
/// the app launches as a result of a tap, including cold-start). The
/// `userInfo` dictionary carries `["route": String]` — the host-relative
/// deep-link target sent in the `data.route` field by the
/// `notify-on-write` Edge Function (Phase 24.5; ADR-017 §3.8 + ADR-018).
/// `AppRootView` subscribes via `.onReceive(...)` and pushes the
/// resolved `Route` onto its `NavigationPath`.
extension Notification.Name {
    static let openPushRoute: Notification.Name =
        Notification.Name(rawValue: "io.github.b150005.skeinly.openPushRoute")
}

/// Key used inside `Notification.userInfo` for the route string. Kept
/// here as a single source of truth so a typo never silently breaks
/// the bridge.
let openPushRouteUserInfoKey = "route"

/// Phase 24.2e + 24.5 (ADR-017 §3.5, §3.8) — bridges iOS APNs
/// registration AND notification-tap callbacks into the shared layer.
///
/// SwiftUI's `App` lifecycle does not expose `application(_:didRegisterFor...)`
/// or `userNotificationCenter(_:didReceive:...)` directly — we install
/// a `UIApplicationDelegate` adapter via `@UIApplicationDelegateAdaptor`
/// in `iOSApp` (see `iOSApp.swift`). The token callbacks forward the
/// result (hex token on success, nil on failure) to
/// `KoinHelperKt.handleApnsTokenReceived` which resolves the Koin-
/// singleton `PushTokenRegistrar` and completes the in-flight
/// `Channel<String?>` opened by `registerForPushNotifications`.
///
/// The notification-center delegate methods read the `data.route`
/// payload and post `Notification.Name.openPushRoute` on the default
/// `NotificationCenter`. `AppRootView`'s `.onReceive(...)` consumes
/// the event and routes via `NavigationPath.append`.
///
/// **Why a `Data → hex string` encoding step**: APNs hands the token
/// back as a 32-byte `Data` blob; the Apple-facing wire format on
/// `aps-development` / `aps-production` HTTP/2 endpoints + the storage
/// layer (`device_tokens.token TEXT`) both expect the lowercase hex
/// representation. The 64-character output is what the
/// `notify-on-write` Edge Function feeds into the APNs `:path` header.
///
/// **Lives under `Core/Bridging/`** because it imports the `Shared`
/// framework — the `iosAppTests` target excludes that directory per the
/// project.yml convention block (test target does not link `Shared`).
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions:
            [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        // Phase 24.5 — register as the notification-center delegate so
        // tap callbacks reach `userNotificationCenter(_:didReceive:...)`.
        // Without this registration, taps still launch the app but the
        // delegate methods are never invoked and the route is silently
        // dropped.
        UNUserNotificationCenter.current().delegate = self

        // Cold-start path: if the OS launched the app as a result of a
        // notification tap, `launchOptions[.remoteNotification]` carries
        // the same `userInfo` dict that `didReceive` would surface for
        // a warm-start tap. Re-post via `NotificationCenter` after a
        // single run-loop tick so `AppRootView`'s `.onReceive(...)` is
        // already wired by the time the post lands. (Without the async
        // dispatch, the post would race the SwiftUI scene-graph init
        // and the listener would miss it.)
        if let remoteUserInfo =
            launchOptions?[.remoteNotification] as? [AnyHashable: Any]
        {
            DispatchQueue.main.async {
                postRouteIfPresent(userInfo: remoteUserInfo)
            }
        }
        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        // 32-byte Data → 64-char lowercase hex. `String(format: "%02x", $0)`
        // explicitly pads single-digit bytes (e.g. 0x05 → "05") so the
        // resulting string has a fixed length and is uniquely decodable.
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        KoinHelperKt.handleApnsTokenReceived(token: hex)
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        // Surface failure as a `nil` token so the registrar's pending
        // `Channel<String?>.receive()` resolves and the suspend caller
        // returns null. Common failure modes:
        //   - missing aps-environment entitlement (provisioning profile
        //     mismatch) — typically caught at archive time
        //   - APNs sandbox unavailable in airplane mode
        //   - device offline at registration time
        // The OS retries on its own schedule; the next app foreground
        // will re-attempt.
        KoinHelperKt.handleApnsTokenReceived(token: nil)
    }

    /// Pre-alpha A16 — Universal Link handler. Fires when iOS hands the
    /// app a `https://` URL claimed by the Associated Domains entitlement
    /// (`applinks:b150005.github.io`, see iosApp.entitlements). Apple
    /// validates the URL against the AASA file at
    /// `<host>/.well-known/apple-app-site-association` BEFORE this
    /// callback fires; we trust the URL host/path at this layer.
    ///
    /// Wire-up shape: extract the `pull-request/<id>` style path from
    /// `webpageURL` and post the same `.openPushRoute` notification used
    /// by APNs taps. `AppRootView`'s `.onReceive(...)` then routes via
    /// `NavigationPath.append`. This keeps a single deep-link consumer
    /// at the SwiftUI layer instead of two parallel pipelines.
    func application(
        _ application: UIApplication,
        continue userActivity: NSUserActivity,
        restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void
    ) -> Bool {
        guard
            userActivity.activityType == NSUserActivityTypeBrowsingWeb,
            let url = userActivity.webpageURL,
            let route = extractUniversalLinkRoute(from: url),
            !route.isEmpty
        else {
            return false
        }
        NotificationCenter.default.post(
            name: .openPushRoute,
            object: nil,
            userInfo: [openPushRouteUserInfoKey: route]
        )
        return true
    }
}

extension AppDelegate: UNUserNotificationCenterDelegate {
    /// Phase 24.5 — fires when a notification arrives while the app is
    /// in the foreground. iOS by default suppresses the system banner
    /// in this state; we ask it to show banner + list + sound anyway
    /// so the user sees the same UX as background delivery (the
    /// Phase 39 closed-beta flow expects parity here).
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler:
            @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    /// Phase 24.5 — fires when the user taps a notification (warm
    /// start) OR when the app comes to the foreground from a tap that
    /// occurred while the app was killed (cold start; first delivery
    /// after the launch options finish). Either way, parse `data.route`
    /// out of `userInfo` and post the open-route notification.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        postRouteIfPresent(userInfo: userInfo)
        completionHandler()
    }
}

/// File-private helper that pulls the `data.route` string out of an
/// APNs `userInfo` dict and re-posts it via `NotificationCenter` for
/// `AppRootView`'s `.onReceive(...)` to consume.
///
/// The Edge Function's APNs payload shape is:
///   `{ "aps": { "alert": ..., "sound": "default" }, "data": { "route": "..." } }`
/// — see `supabase/functions/notify-on-write/apns.ts` `sendApns()`.
/// We pluck `userInfo["data"]["route"]` defensively (any missing key
/// or wrong type yields a silent no-op rather than a crash).
private func postRouteIfPresent(userInfo: [AnyHashable: Any]) {
    guard
        let dataDict = userInfo["data"] as? [AnyHashable: Any],
        let route = dataDict["route"] as? String,
        !route.isEmpty
    else {
        return
    }
    NotificationCenter.default.post(
        name: .openPushRoute,
        object: nil,
        userInfo: [openPushRouteUserInfoKey: route]
    )
}

/// Pre-alpha A16 — Universal Link path extractor. Converts an inbound
/// `https://<host>/<deep-link-path>` into the host-relative route string
/// the shared `parsePushRoute` (commonMain) consumes. Designed to be
/// symmetric with the APNs `data.route` field so the SwiftUI consumer
/// at `AppRootView` does not need to distinguish the source.
///
/// Examples:
///   - `https://b150005.github.io/skeinly/pull-request/abc` → `pull-request/abc`
///   - `https://skeinly.app/pull-request/abc`              → `pull-request/abc`
///   - `https://b150005.github.io/skeinly/`                → `nil` (no deep-link path)
///   - `https://b150005.github.io/skeinly/unknown/x`       → `unknown/x`
///     (let `parsePushRoute` decide whether the route is recognized;
///      unknown prefixes return nil downstream and the consumer drops)
///
/// We strip a leading `/skeinly/` Project Pages prefix if present so the
/// same route shape works regardless of the final deploy decision
/// (Project Pages with `/skeinly/` base path, custom domain at root, or
/// User Pages repo at root). The strip is a no-op for the latter two.
internal func extractUniversalLinkRoute(from url: URL) -> String? {
    var path = url.path
    // Drop the Project-Pages base path if present so downstream consumers
    // see the same canonical `pull-request/<id>` shape regardless of host.
    let projectPagesPrefix = "/skeinly/"
    if path.hasPrefix(projectPagesPrefix) {
        path = String(path.dropFirst(projectPagesPrefix.count - 1))
    }
    // Trim the leading "/" so the route is host-relative (matches the
    // APNs `data.route` convention: `pull-request/<id>`, not
    // `/pull-request/<id>`).
    if path.hasPrefix("/") {
        path = String(path.dropFirst())
    }
    // Strip trailing slash so `pull-request/<id>/` and `pull-request/<id>`
    // resolve to the same route.
    if path.hasSuffix("/") {
        path = String(path.dropLast())
    }
    return path.isEmpty ? nil : path
}
