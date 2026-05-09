import UIKit
import Shared

/// Phase 24.2e (ADR-017 §3.5) — bridges the iOS APNs registration
/// callback into the shared `PushTokenRegistrar`.
///
/// SwiftUI's `App` lifecycle does not expose `application(_:didRegisterFor...)`
/// directly — we have to install a `UIApplicationDelegate` adapter via
/// `@UIApplicationDelegateAdaptor` in `iOSApp` (see `iOSApp.swift`).
/// The two callbacks below forward the result (hex token on success,
/// nil on failure) to `KoinHelperKt.handleApnsTokenReceived` which
/// resolves the Koin-singleton `PushTokenRegistrar` and completes the
/// in-flight `Channel<String?>` opened by
/// `registerForPushNotifications`.
///
/// **Why a `Data → hex string` encoding step**: APNs hands the token
/// back as a 32-byte `Data` blob; the Apple-facing wire format on
/// `aps-development` / `aps-production` HTTP/2 endpoints + the storage
/// layer (`device_tokens.token TEXT`) both expect the lowercase hex
/// representation. The 64-character output is what the
/// `notify-on-write` Edge Function will eventually feed into the APNs
/// `:path` header.
///
/// **Lives under `Core/Bridging/`** because it imports the `Shared`
/// framework — the `iosAppTests` target excludes that directory per the
/// project.yml convention block (test target does not link `Shared`).
final class AppDelegate: NSObject, UIApplicationDelegate {
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
}
