package io.github.b150005.skeinly.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 39 (W4 / 2026-05-11) — locks the semver comparison + evaluation
 * contract for the force-update gate. `compareSemver` is `internal` so
 * this test sits in the same package on commonTest.
 */
class AppConfigTest {
    // ---------- compareSemver ----------

    @Test
    fun `compareSemver equal versions returns zero`() {
        assertEquals(0, compareSemver("1.2.3", "1.2.3"))
        assertEquals(0, compareSemver("0.1.0", "0.1.0"))
    }

    @Test
    fun `compareSemver lower current returns negative`() {
        assertTrue((compareSemver("0.1.0", "0.2.0") ?: 0) < 0)
        assertTrue((compareSemver("0.1.0", "0.1.1") ?: 0) < 0)
        assertTrue((compareSemver("0.1.0", "1.0.0") ?: 0) < 0)
        // Major bump trumps minor / patch difference.
        assertTrue((compareSemver("0.99.99", "1.0.0") ?: 0) < 0)
    }

    @Test
    fun `compareSemver higher current returns positive`() {
        assertTrue((compareSemver("0.2.0", "0.1.0") ?: 0) > 0)
        assertTrue((compareSemver("1.0.0", "0.9.9") ?: 0) > 0)
    }

    @Test
    fun `compareSemver malformed returns null fail-open`() {
        assertNull(compareSemver("not-a-version", "1.0.0"))
        assertNull(compareSemver("1.0.0", "abc"))
        assertNull(compareSemver("", "1.0.0"))
        // Hyphenated prerelease deliberately unsupported (CFBundleShortVersionString rejects).
        assertNull(compareSemver("1.0.0-beta1", "1.0.0"))
    }

    @Test
    fun `compareSemver partial versions tolerate missing minor patch`() {
        // `1` and `1.0` and `1.0.0` should all be treated as equal.
        assertEquals(0, compareSemver("1", "1.0.0"))
        assertEquals(0, compareSemver("1.0", "1.0.0"))
        assertEquals(0, compareSemver("1.0.0", "1"))
    }

    @Test
    fun `compareSemver four-segment version rejected`() {
        // Spec is MAJOR.MINOR.PATCH only — defensively reject 4-segment.
        assertNull(compareSemver("1.0.0.0", "1.0.0"))
    }

    // ---------- AppConfig.evaluate ----------

    private val config =
        AppConfig(
            minRequiredVersionAndroid = "0.2.0",
            minRequiredVersionIos = "0.2.0",
            forceUpdateMessageEn = "Custom EN message",
            forceUpdateMessageJa = "カスタム JA メッセージ",
            maintenanceModeActive = false,
            maintenanceMessageEn = null,
            maintenanceMessageJa = null,
        )

    @Test
    fun `evaluate current above floor returns Ok`() {
        assertEquals(AppGateRequirement.Ok, config.evaluate("0.3.0", AppPlatform.ANDROID))
        assertEquals(AppGateRequirement.Ok, config.evaluate("1.0.0", AppPlatform.IOS))
    }

    @Test
    fun `evaluate current equal to floor returns Ok`() {
        assertEquals(AppGateRequirement.Ok, config.evaluate("0.2.0", AppPlatform.ANDROID))
        assertEquals(AppGateRequirement.Ok, config.evaluate("0.2.0", AppPlatform.IOS))
    }

    @Test
    fun `evaluate current below floor returns UpdateRequired with custom messages`() {
        val result = config.evaluate("0.1.0", AppPlatform.ANDROID)
        assertTrue(result is AppGateRequirement.UpdateRequired)
        assertEquals("Custom EN message", result.customMessageEn)
        assertEquals("カスタム JA メッセージ", result.customMessageJa)
    }

    @Test
    fun `evaluate platform-specific floor picks correct field`() {
        val asymmetric =
            AppConfig(
                minRequiredVersionAndroid = "0.1.0",
                minRequiredVersionIos = "0.5.0",
                forceUpdateMessageEn = null,
                forceUpdateMessageJa = null,
                maintenanceModeActive = false,
                maintenanceMessageEn = null,
                maintenanceMessageJa = null,
            )
        // Android floor is 0.1.0 — current 0.3.0 is above.
        assertEquals(AppGateRequirement.Ok, asymmetric.evaluate("0.3.0", AppPlatform.ANDROID))
        // iOS floor is 0.5.0 — current 0.3.0 is below.
        assertTrue(asymmetric.evaluate("0.3.0", AppPlatform.IOS) is AppGateRequirement.UpdateRequired)
    }

    @Test
    fun `evaluate unparseable currentVersion fails-open to Ok`() {
        // The gate must NEVER block users on a parser error — only on a
        // deliberate kill-switch trigger.
        assertEquals(AppGateRequirement.Ok, config.evaluate("garbage", AppPlatform.ANDROID))
        assertEquals(AppGateRequirement.Ok, config.evaluate("", AppPlatform.IOS))
    }

    // ---------- A15 maintenance-mode evaluation ----------

    @Test
    fun `evaluate maintenance mode active returns MaintenanceMode regardless of version`() {
        val maintenanceConfig =
            AppConfig(
                minRequiredVersionAndroid = "0.2.0",
                minRequiredVersionIos = "0.2.0",
                forceUpdateMessageEn = null,
                forceUpdateMessageJa = null,
                maintenanceModeActive = true,
                maintenanceMessageEn = "Down for maintenance",
                maintenanceMessageJa = "メンテナンス中",
            )
        // Above floor — still gated by maintenance.
        val above = maintenanceConfig.evaluate("0.3.0", AppPlatform.ANDROID)
        assertTrue(above is AppGateRequirement.MaintenanceMode)
        assertEquals("Down for maintenance", above.customMessageEn)
        assertEquals("メンテナンス中", above.customMessageJa)

        // Below floor — maintenance still takes priority over UpdateRequired.
        val below = maintenanceConfig.evaluate("0.1.0", AppPlatform.IOS)
        assertTrue(below is AppGateRequirement.MaintenanceMode)
    }

    @Test
    fun `evaluate maintenance mode active with null messages returns MaintenanceMode with null fields`() {
        // When server has not set custom messages, the data-class fields
        // carry null; the screen falls back to bundled defaults.
        val maintenanceConfig =
            AppConfig(
                minRequiredVersionAndroid = "0.2.0",
                minRequiredVersionIos = "0.2.0",
                forceUpdateMessageEn = null,
                forceUpdateMessageJa = null,
                maintenanceModeActive = true,
                maintenanceMessageEn = null,
                maintenanceMessageJa = null,
            )
        val result = maintenanceConfig.evaluate("0.5.0", AppPlatform.ANDROID)
        assertTrue(result is AppGateRequirement.MaintenanceMode)
        assertEquals(null, result.customMessageEn)
        assertEquals(null, result.customMessageJa)
    }

    @Test
    fun `evaluate maintenance mode inactive falls through to version-floor check`() {
        // Verify the priority logic: maintenance off ⇒ behave exactly as
        // the pre-A15 version-floor-only contract.
        val cfg =
            AppConfig(
                minRequiredVersionAndroid = "0.2.0",
                minRequiredVersionIos = "0.2.0",
                forceUpdateMessageEn = null,
                forceUpdateMessageJa = null,
                maintenanceModeActive = false,
                maintenanceMessageEn = "Should be ignored",
                maintenanceMessageJa = "無視されるべき",
            )
        // Above floor → Ok.
        assertEquals(AppGateRequirement.Ok, cfg.evaluate("0.3.0", AppPlatform.ANDROID))
        // Below floor → UpdateRequired (NOT MaintenanceMode because flag is off).
        assertTrue(cfg.evaluate("0.1.0", AppPlatform.IOS) is AppGateRequirement.UpdateRequired)
    }
}
