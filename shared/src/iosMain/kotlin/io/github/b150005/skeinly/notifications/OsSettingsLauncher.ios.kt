package io.github.b150005.skeinly.notifications

/**
 * Phase 24.2c (ADR-017 §3.6) — iOS **no-op stub**. 24.2d will replace
 * this with:
 *   `UIApplication.sharedApplication.openURL(
 *       NSURL.URLWithString(UIApplicationOpenSettingsURLString)
 *   )`.
 */
actual class OsSettingsLauncher {
    actual fun openAppNotificationSettings() {
        // 24.2c stub — 24.2d wires `UIApplication.sharedApplication.openURL`.
    }
}
