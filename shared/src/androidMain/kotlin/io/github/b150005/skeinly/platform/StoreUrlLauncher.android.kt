@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Phase 39 (W4 / 2026-05-11) — Android impl: primary path uses the
 * `market://` scheme which Play Store intercepts. Fallback to the web
 * Play Store listing when no Play Store app is installed (e.g. Amazon
 * Fire devices, China devices without GMS).
 *
 * `FLAG_ACTIVITY_NEW_TASK` is required because the injected [Context]
 * is the application Context (Koin singleton), not an Activity — same
 * pattern as [BugSubmissionLauncher.android] and
 * [io.github.b150005.skeinly.notifications.OsSettingsLauncher.android].
 *
 * Both paths swallow failure rather than crashing — the user is on a
 * force-update screen and can re-tap. The genuinely-no-network case
 * leaves them stuck, but that's an OS-level failure mode we can't
 * paper over.
 */
actual class StoreUrlLauncher(
    private val context: Context,
) {
    actual fun open() {
        // Primary: Play Store deep-link via the market:// scheme. The
        // Play Store app catches this intent + opens its own UI on the
        // Skeinly listing.
        val deepLinkIntent =
            Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_DEEP_LINK))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(deepLinkIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // No Play Store app — fall through to web fallback below.
        } catch (_: Throwable) {
            // Other unexpected failure — fall through too.
        }
        // Fallback: open the web Play Store listing in the default
        // browser. Apparent on Amazon Fire / non-GMS devices.
        val webIntent =
            Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_WEB_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(webIntent)
        } catch (_: Throwable) {
            // Best-effort. User is on a force-update screen and can
            // navigate to Play / browser manually as a last resort.
        }
    }
}
