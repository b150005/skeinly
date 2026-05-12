@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Pre-alpha A30 — Android impl. Primary path uses the
 * `market://account/subscriptions?package=...` deep link which the Play
 * Store app catches + opens the Subscriptions UI on. Falls back to the
 * web Play Store subscriptions URL when no Play Store app is installed
 * (e.g. Amazon Fire devices, China devices without GMS).
 *
 * `FLAG_ACTIVITY_NEW_TASK` is required because the injected [Context]
 * is the application Context (Koin singleton), not an Activity — same
 * pattern as [StoreUrlLauncher.android] and other platform launchers.
 *
 * Both paths swallow failure rather than crashing. The user can always
 * navigate to Play Store → Subscriptions manually as a last resort.
 */
actual class SubscriptionManagementLauncher(
    private val context: Context,
) {
    actual fun open() {
        // Primary: Play Store deep link via the market:// scheme.
        val deepLinkIntent =
            Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_SUBSCRIPTIONS_DEEP_LINK))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(deepLinkIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // No Play Store app — fall through to web fallback below.
        } catch (_: Throwable) {
            // Other unexpected failure — fall through too.
        }
        // Fallback: web Play Store subscriptions page in the default browser.
        val webIntent =
            Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_SUBSCRIPTIONS_WEB_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(webIntent)
        } catch (_: Throwable) {
            // Best-effort.
        }
    }
}
