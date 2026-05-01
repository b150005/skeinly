package io.github.b150005.skeinly.platform

/**
 * Phase 39.5 (ADR-015 §3) — read-only DTO carrying the OS / device /
 * version / locale fields that go into a bug report's "Reproduction
 * context" block.
 *
 * Pulled out of [DeviceContextProvider] so consumer code (the
 * `BugReportPreviewViewModel` and the
 * [io.github.b150005.skeinly.data.bug.formatBugReportBody] helper) can
 * depend on a plain Kotlin data class rather than the
 * `expect class`. Tests construct fixtures directly without standing
 * up the platform-specific provider.
 */
data class DeviceContext(
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val platformName: String,
    val locale: String,
)

/** Snapshot the platform provider into a plain DTO at the DI boundary. */
fun DeviceContextProvider.snapshotContext(): DeviceContext =
    DeviceContext(
        appVersion = appVersion,
        osVersion = osVersion,
        deviceModel = deviceModel,
        platformName = platformName,
        locale = locale,
    )
