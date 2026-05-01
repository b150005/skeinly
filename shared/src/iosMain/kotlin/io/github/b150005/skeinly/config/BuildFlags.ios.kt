package io.github.b150005.skeinly.config

import platform.Foundation.NSBundle

/**
 * iOS actual: reads `IsBetaBuild` from Info.plist as a String comparison.
 *
 * The xcconfig macro `IS_BETA = YES|NO` in `iosApp/project.yml` is
 * substituted into the plist as `<string>YES</string>` /
 * `<string>NO</string>` (xcconfig variable substitution always produces
 * strings). `NSBundle.objectForInfoDictionaryKey` therefore returns an
 * NSString — `as? Boolean` would always evaluate to null. The comparison
 * `== "YES"` is the documented Apple convention for build-time boolean
 * flags driven through xcconfig, mirrored from
 * `SupabaseConfig.ios.kt`'s String-based reading of `SUPABASE_URL` /
 * `SUPABASE_PUBLISHABLE_KEY`.
 */
actual object BuildFlags {
    actual val isBeta: Boolean
        get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("IsBetaBuild") as? String) == "YES"
}
