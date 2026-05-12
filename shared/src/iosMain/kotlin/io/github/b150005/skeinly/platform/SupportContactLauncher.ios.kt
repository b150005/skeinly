@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Pre-alpha A34 — iOS impl. Opens the user's default mail composer via
 * `UIApplication.openURL` with the `mailto:` URL. iOS routes mailto
 * URLs to Mail.app (or the user's configured default mail app, if
 * set) automatically.
 *
 * Fire-and-forget contract mirrors [StoreUrlLauncher.ios] /
 * [SubscriptionManagementLauncher.ios]. If no mail account is
 * configured on the device, iOS displays its own "No Mail Account"
 * alert — Skeinly does not need to detect this case ahead of time.
 */
actual class SupportContactLauncher {
    actual fun openSupportEmail(deviceContext: DeviceContextProvider) {
        val mailtoUrl = composeSupportMailtoUrl(deviceContext)
        val url = NSURL(string = mailtoUrl) ?: return
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
    }
}
