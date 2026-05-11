package io.github.b150005.skeinly.config

import io.github.b150005.skeinly.domain.model.AppPlatform
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

    /**
     * Phase 39 (W4 / 2026-05-11) — marketing version name read from
     * `CFBundleShortVersionString`. xcconfig macro `MARKETING_VERSION`
     * (set in `iosApp/project.yml` and overridden by CI via fastlane
     * `xcargs`) substitutes into this Info.plist key at build time.
     *
     * Empty-string fallback shields the force-update gate against the
     * pathological case where Info.plist is corrupt — the semver parser
     * in [io.github.b150005.skeinly.domain.model.compareSemver] degrades
     * to null (fail-open) when given an empty string, so the gate stays
     * closed (no false force-update).
     */
    actual val versionName: String
        get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String) ?: ""

    actual val platform: AppPlatform = AppPlatform.IOS
}
