package io.github.b150005.skeinly.notifications

/**
 * Phase 24.2 (ADR-017 §3.6) — opens the OS-level "App Notifications"
 * Settings page so the user can toggle the system permission they
 * previously denied.
 *
 * iOS: `UIApplication.sharedApplication.openURL(NSURL(string: UIApplicationOpenSettingsURLString)!)`.
 * Android: `Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply { putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName) }`.
 *
 * **24.2c ships no-op stubs** so the call sites compile + work without
 * crashing; 24.2d wires the native intent / openURL.
 *
 * **Why a separate class instead of a top-level expect fun:** Android's
 * actual needs `Context`, which doesn't reach into commonMain naturally.
 * Wrapping in a class with constructor-injected Context (mirroring the
 * existing [io.github.b150005.skeinly.platform.BugSubmissionLauncher]
 * precedent) keeps the DI surface clean.
 */
expect class OsSettingsLauncher {
    fun openAppNotificationSettings()
}
