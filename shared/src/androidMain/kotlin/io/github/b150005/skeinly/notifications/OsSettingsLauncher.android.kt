package io.github.b150005.skeinly.notifications

import android.content.Context

/**
 * Phase 24.2c (ADR-017 §3.6) — Android **no-op stub**. 24.2d will
 * replace this with:
 *   `Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)`
 *     .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)`
 *     `.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).also(context::startActivity)`.
 *
 * The FLAG_ACTIVITY_NEW_TASK is load-bearing on application Context
 * which doesn't carry an Activity stack on its own. The
 * BugSubmissionLauncher.android.kt precedent has the same wiring.
 */
@Suppress("UnusedPrivateProperty")
actual class OsSettingsLauncher(
    private val context: Context,
) {
    actual fun openAppNotificationSettings() {
        // 24.2c stub — 24.2d wires the real intent.
    }
}
