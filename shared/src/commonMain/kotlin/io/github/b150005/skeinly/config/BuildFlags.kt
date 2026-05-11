package io.github.b150005.skeinly.config

/**
 * Phase 39.2: build-flavor flag exposed to shared code.
 *
 * `true` when the running binary is a beta build (Phase 39 closed-beta
 * channel via TestFlight Internal / Play Internal Testing). `false` for
 * v1.0 production releases (Phase 40+).
 *
 * Resolved at build time on Android (generated from the
 * `version.properties` `VERSION_NAME` `-beta` suffix by
 * `shared/build.gradle.kts` `generateBuildFlagsAndroid`) and at runtime
 * on iOS (read from the `IsBetaBuild` Info.plist key, populated via the
 * xcconfig macro `IS_BETA` per `iosApp/project.yml`).
 *
 * Used by F2 (ADR-015) to gate the bug-reporter gesture detection,
 * Settings → Beta section, onboarding consent page, and PostHog SDK
 * initialization. Production binaries skip all of these surfaces
 * entirely.
 *
 * Swift call sites read the Info.plist key directly via
 * `Bundle.main.object(forInfoDictionaryKey: "IsBetaBuild")` rather than
 * bridging through this Kotlin object — see
 * `iosApp/iosApp/Core/BuildFlags.swift`.
 */
expect object BuildFlags {
    val isBeta: Boolean

    /**
     * Phase 39 (W4 / 2026-05-11) — marketing version name (`X.Y.Z`),
     * matched against `app_config.min_required_version_*` in the force-
     * update gate ([io.github.b150005.skeinly.domain.repository.AppConfigRepository]).
     *
     * Android: generated at build time from `version.properties` `VERSION_NAME`
     * by `generateBuildFlagsAndroid` (the same task that derives `isBeta`).
     * iOS: read at runtime from `CFBundleShortVersionString` in Info.plist
     * (populated by xcconfig macro `MARKETING_VERSION` in `iosApp/project.yml`).
     *
     * Guarantees `X.Y.Z` semver format (digits + dots only) because:
     *   - iOS `CFBundleShortVersionString` is enforced by App Store Connect
     *     to accept only digits and dots (no hyphens / prerelease tags).
     *   - Android `VERSION_NAME` is constrained by the same convention in
     *     `version.properties` (commented in the file header).
     *
     * Empty string fallback only fires if both the Android codegen and
     * the iOS plist read fail — in that case the force-update gate's
     * semver parser degrades to fail-open per its KDoc.
     */
    val versionName: String

    /**
     * Phase 39 (W4 / 2026-05-11) — compile-time platform identity. Used
     * by the force-update gate to pick between
     * `app_config.min_required_version_android` and `_ios`. Each actual
     * hard-codes its own value (the codegen / static path means the
     * compiler erases the branch at the call site).
     */
    val platform: io.github.b150005.skeinly.domain.model.AppPlatform
}
