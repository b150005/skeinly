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
        )

    @Test
    fun `evaluate current above floor returns Ok`() {
        assertEquals(ForceUpdateRequirement.Ok, config.evaluate("0.3.0", AppPlatform.ANDROID))
        assertEquals(ForceUpdateRequirement.Ok, config.evaluate("1.0.0", AppPlatform.IOS))
    }

    @Test
    fun `evaluate current equal to floor returns Ok`() {
        assertEquals(ForceUpdateRequirement.Ok, config.evaluate("0.2.0", AppPlatform.ANDROID))
        assertEquals(ForceUpdateRequirement.Ok, config.evaluate("0.2.0", AppPlatform.IOS))
    }

    @Test
    fun `evaluate current below floor returns UpdateRequired with custom messages`() {
        val result = config.evaluate("0.1.0", AppPlatform.ANDROID)
        assertTrue(result is ForceUpdateRequirement.UpdateRequired)
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
            )
        // Android floor is 0.1.0 — current 0.3.0 is above.
        assertEquals(ForceUpdateRequirement.Ok, asymmetric.evaluate("0.3.0", AppPlatform.ANDROID))
        // iOS floor is 0.5.0 — current 0.3.0 is below.
        assertTrue(asymmetric.evaluate("0.3.0", AppPlatform.IOS) is ForceUpdateRequirement.UpdateRequired)
    }

    @Test
    fun `evaluate unparseable currentVersion fails-open to Ok`() {
        // The gate must NEVER block users on a parser error — only on a
        // deliberate kill-switch trigger.
        assertEquals(ForceUpdateRequirement.Ok, config.evaluate("garbage", AppPlatform.ANDROID))
        assertEquals(ForceUpdateRequirement.Ok, config.evaluate("", AppPlatform.IOS))
    }
}
