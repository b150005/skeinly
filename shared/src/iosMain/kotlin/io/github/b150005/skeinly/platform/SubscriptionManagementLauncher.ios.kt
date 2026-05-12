@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Pre-alpha A30 — iOS impl. Opens
 * `https://apps.apple.com/account/subscriptions` via `UIApplication.openURL`.
 * iOS routes this universal URL to the App Store app's subscription-
 * management screen (or Settings.app's Subscriptions panel on newer iOS
 * versions). Behaviour is the same regardless of whether the user holds
 * a Skeinly Pro subscription or not — the destination is the user's full
 * subscription list.
 *
 * Fire-and-forget contract mirrors [StoreUrlLauncher.ios].
 */
actual class SubscriptionManagementLauncher {
    actual fun open() {
        val url = NSURL(string = APPLE_SUBSCRIPTIONS_URL)
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
    }
}
