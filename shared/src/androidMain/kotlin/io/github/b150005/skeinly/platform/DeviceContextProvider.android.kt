@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.platform

import android.content.Context
import android.os.Build
import java.util.Locale

actual class DeviceContextProvider(
    context: Context,
) {
    actual val appVersion: String =
        run {
            runCatching {
                // PackageManager.getPackageInfo(name, 0) is the simplest path —
                // we don't need PackageInfoCompat's API-29+ flag chasing because
                // Skeinly's minSdk is well below 29 and the legacy 0-flag form
                // returns versionName on every supported API level. A failing
                // lookup (uninstalled mid-flow, manifest mangled) falls through
                // to "unknown" rather than crashing the report flow.
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                val name = info.versionName ?: "unknown"

                // Concatenate the build (versionCode) so triagers see both —
                // matches the iOS "1.0.0-beta1 (3)" shape.
                @Suppress("DEPRECATION")
                val code = info.versionCode
                "$name ($code)"
            }.getOrNull() ?: "unknown"
        }

    actual val osVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    actual val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    actual val platformName: String = "Android"

    actual val locale: String = Locale.getDefault().toLanguageTag()
}
