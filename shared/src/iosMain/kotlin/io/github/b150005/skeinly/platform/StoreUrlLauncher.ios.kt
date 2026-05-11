@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Phase 39 (W4 / 2026-05-11) — iOS impl: opens the App Store URL via
 * `UIApplication.openURL`. iOS routes `https://apps.apple.com/...`
 * URLs to the App Store app when installed; the same URL falls back
 * to Safari with the web App Store listing on simulators or devices
 * without the App Store app (rare).
 *
 * `openURL:options:completionHandler:` with empty options + null
 * completion handler matches the fire-and-forget contract on the
 * commonMain `expect` — caller does not suspend or receive a result.
 */
actual class StoreUrlLauncher {
    actual fun open() {
        val url = NSURL(string = APPLE_APP_STORE_URL)
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
    }
}
