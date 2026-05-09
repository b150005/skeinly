package io.github.b150005.skeinly.notifications

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Phase 24.2d (ADR-017 §3.6) — Android implementation. Opens the system
 * App Notifications settings panel via [Settings.ACTION_APP_NOTIFICATION_SETTINGS]
 * (introduced API 26 / Oreo, which is below our `minSdk = 26` floor so
 * no legacy fallback path is needed).
 *
 * The `EXTRA_APP_PACKAGE` intent extra targets the app's settings page
 * (NOT the global notifications page). `FLAG_ACTIVITY_NEW_TASK` is
 * load-bearing because [Context] (the application context Koin injects)
 * doesn't carry an Activity stack — the same wiring as
 * [io.github.b150005.skeinly.platform.BugSubmissionLauncher.android].
 *
 * Failure (e.g. ActivityNotFoundException on extreme custom ROMs) is
 * caught + swallowed: the recovery path is "user re-taps" or "user
 * navigates to Settings manually". We do not surface an error toast
 * because the failure is rare and the user already knows they tapped
 * an action that should open Settings.
 */
@Suppress("UnusedPrivateProperty")
actual class OsSettingsLauncher(
    private val context: Context,
) {
    actual fun openAppNotificationSettings() {
        val intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            // Best-effort: user can re-tap or navigate to Settings manually.
        }
    }
}
