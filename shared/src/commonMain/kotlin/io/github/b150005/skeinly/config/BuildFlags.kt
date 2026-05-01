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
}
