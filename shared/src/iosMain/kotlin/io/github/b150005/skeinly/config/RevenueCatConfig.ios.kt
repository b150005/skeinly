package io.github.b150005.skeinly.config

import platform.Foundation.NSBundle

actual object RevenueCatConfig {
    actual val apiKey: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("REVENUECAT_API_KEY") as? String ?: ""
}
