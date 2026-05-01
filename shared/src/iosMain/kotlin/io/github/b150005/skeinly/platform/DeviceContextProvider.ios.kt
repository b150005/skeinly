@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.platform

import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.UIKit.UIDevice

actual class DeviceContextProvider {
    actual val appVersion: String =
        run {
            val short =
                NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
            val build =
                NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String
            when {
                short != null && build != null -> "$short ($build)"
                short != null -> short
                else -> "unknown"
            }
        }

    actual val osVersion: String = "iOS ${UIDevice.currentDevice.systemVersion}"

    // UIDevice.model returns "iPhone" / "iPad" / "iPhone Simulator" etc.
    // — coarse but safe (no PII, no entitlement required). The hardware
    // identifier (e.g. "iPhone15,2") would be more useful for triage but
    // requires reading `utsname` via uname() which involves cinterop;
    // promotable when telemetry surfaces a need.
    actual val deviceModel: String = UIDevice.currentDevice.model

    actual val platformName: String = "iOS"

    // NSLocale.localeIdentifier uses underscore separators (e.g. "ja_JP");
    // BCP 47 uses hyphens. Replace to match the Android side which calls
    // `Locale.toLanguageTag()` (BCP 47-native).
    actual val locale: String = NSLocale.currentLocale.localeIdentifier.replace("_", "-")
}
