package io.github.b150005.skeinly.notifications

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * Phase 24.2d (ADR-017 §3.6) — iOS implementation. Opens the system
 * Settings app deep-linked to the app's notification preference panel
 * via `UIApplicationOpenSettingsURLString`.
 *
 * The deep link lands on the app's Settings page (NOT the global
 * notifications page); from there the user toggles "Allow Notifications".
 * iOS automatically scrolls to the matching section if the user
 * previously denied; otherwise the panel just shows whatever scope the
 * app's permissions currently expose.
 *
 * `UIApplicationOpenSettingsURLString` always resolves on real devices +
 * simulators (no need to gate on `canOpenURL`). The completion handler
 * is intentionally not consumed — failure here is non-fatal at the UX
 * layer; the user re-tapping is the recovery path.
 */
actual class OsSettingsLauncher {
    actual fun openAppNotificationSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
