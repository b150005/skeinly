package io.github.b150005.skeinly.platform

/**
 * Phase 39.5 (ADR-015 §3) — read-only access to the OS / device / locale /
 * app version fields that go into a bug report's "Reproduction context"
 * block.
 *
 * Each property is computed at construction (idempotent — these values do
 * not change at runtime within a single process), so reads are free at the
 * call site.
 *
 * Provided by Koin as a `single` (the Android actual takes a `Context`
 * which is itself injected). Tests can substitute a hand-built
 * `FakeDeviceContext` data class implementing the same property surface
 * directly — no expect-class dependency injection needed.
 */
expect class DeviceContextProvider {
    /** Marketing version, e.g. `1.0.0-beta1` on Android, `1.0.0-beta1 (3)` on iOS. */
    val appVersion: String

    /** OS family + version, e.g. `Android 14 (API 34)` or `iOS 18.0`. */
    val osVersion: String

    /** Device manufacturer/model, e.g. `Google Pixel 8` or `iPhone15,2`. */
    val deviceModel: String

    /** Platform family — `Android` or `iOS`. Used for the body's "Platform:" line. */
    val platformName: String

    /** BCP 47 tag, e.g. `en-US` or `ja-JP`. */
    val locale: String
}
