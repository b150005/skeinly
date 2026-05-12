@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.platform

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Pre-alpha A34 — Android impl. Opens the user's mail composer via an
 * implicit `Intent(ACTION_SENDTO, mailto:...)` so Android picks up the
 * user's preferred mail client (Gmail / Outlook / Spark / etc.) via
 * the standard "Open with" chooser if multiple are installed.
 *
 * `ACTION_SENDTO` (vs `ACTION_VIEW`) is the correct intent for mailto:
 * URLs on Android — `VIEW` may surface non-mail intent handlers as
 * options (browsers etc.).
 *
 * `FLAG_ACTIVITY_NEW_TASK` is required because the injected [Context]
 * is the application Context, not an Activity. Same pattern as the
 * other platform launchers.
 */
actual class SupportContactLauncher(
    private val context: Context,
) {
    actual fun openSupportEmail(deviceContext: DeviceContextProvider) {
        val mailtoUrl = composeSupportMailtoUrl(deviceContext)
        val intent =
            Intent(Intent.ACTION_SENDTO, Uri.parse(mailtoUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            // Best-effort. The user has no mail client installed
            // (rare). They can copy the support email from the
            // Privacy Policy contact section as a fallback.
        }
    }
}
