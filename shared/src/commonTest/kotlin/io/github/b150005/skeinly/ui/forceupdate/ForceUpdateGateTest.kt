package io.github.b150005.skeinly.ui.forceupdate

import io.github.b150005.skeinly.domain.model.AppConfig
import io.github.b150005.skeinly.domain.model.ForceUpdateRequirement
import io.github.b150005.skeinly.domain.repository.AppConfigState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 39 (W4 / 2026-05-11) — locks the pure helpers of the
 * force-update gate. The Composable itself is exercised via the
 * Compose UI test surface; this file covers the boundary semantics
 * around state→requirement mapping and locale-aware message selection.
 */
class ForceUpdateGateTest {
    private val sampleConfig =
        AppConfig(
            // Both versions far ahead of any plausible BuildFlags.versionName
            // in this test environment so toRequirement triggers UpdateRequired.
            // (Tests run on the testAndroidHostTest JVM, where the Android
            // BuildFlags actual is generated from version.properties at
            // build time; current is 0.1.0.)
            minRequiredVersionAndroid = "99.0.0",
            minRequiredVersionIos = "99.0.0",
            forceUpdateMessageEn = "EN msg",
            forceUpdateMessageJa = "JA msg",
        )

    // ---------- toRequirement ----------

    @Test
    fun `toRequirement Loading returns Unknown`() {
        assertEquals(ForceUpdateRequirement.Unknown, AppConfigState.Loading.toRequirement())
    }

    @Test
    fun `toRequirement Unavailable returns Unknown`() {
        assertEquals(ForceUpdateRequirement.Unknown, AppConfigState.Unavailable.toRequirement())
    }

    @Test
    fun `toRequirement Live below floor returns UpdateRequired`() {
        // testAndroidHostTest runs with BuildFlags.versionName=0.1.0 +
        // platform=ANDROID (generated from version.properties); a floor
        // of 99.0.0 will trigger UpdateRequired.
        val req = AppConfigState.Live(sampleConfig).toRequirement()
        assertTrue(req is ForceUpdateRequirement.UpdateRequired)
        assertEquals("EN msg", req.customMessageEn)
        assertEquals("JA msg", req.customMessageJa)
    }

    @Test
    fun `toRequirement Cached below floor returns UpdateRequired`() {
        val req = AppConfigState.Cached(sampleConfig).toRequirement()
        assertTrue(req is ForceUpdateRequirement.UpdateRequired)
    }

    @Test
    fun `toRequirement Live above floor returns Ok`() {
        val belowFloor =
            sampleConfig.copy(
                minRequiredVersionAndroid = "0.0.1",
                minRequiredVersionIos = "0.0.1",
            )
        assertEquals(ForceUpdateRequirement.Ok, AppConfigState.Live(belowFloor).toRequirement())
    }

    // ---------- selectMessageForLocale ----------

    @Test
    fun `selectMessageForLocale ja-prefix picks ja message`() {
        assertEquals(
            "JA msg",
            selectMessageForLocale(
                customMessageEn = "EN msg",
                customMessageJa = "JA msg",
                locale = "ja-JP",
            ),
        )
        assertEquals(
            "JA msg",
            selectMessageForLocale(
                customMessageEn = "EN msg",
                customMessageJa = "JA msg",
                locale = "ja",
            ),
        )
        // Defensive: BCP 47 sub-tag variants.
        assertEquals(
            "JA msg",
            selectMessageForLocale(
                customMessageEn = "EN msg",
                customMessageJa = "JA msg",
                locale = "ja-Hrkt-JP",
            ),
        )
    }

    @Test
    fun `selectMessageForLocale non-ja picks en message`() {
        assertEquals(
            "EN msg",
            selectMessageForLocale(
                customMessageEn = "EN msg",
                customMessageJa = "JA msg",
                locale = "en-US",
            ),
        )
        assertEquals(
            "EN msg",
            selectMessageForLocale(
                customMessageEn = "EN msg",
                customMessageJa = "JA msg",
                locale = "en",
            ),
        )
        // Non-ja non-en: fall back to en (alpha rubric is en + ja only).
        assertEquals(
            "EN msg",
            selectMessageForLocale(
                customMessageEn = "EN msg",
                customMessageJa = "JA msg",
                locale = "fr-FR",
            ),
        )
    }

    @Test
    fun `selectMessageForLocale null message returns null`() {
        assertNull(
            selectMessageForLocale(
                customMessageEn = null,
                customMessageJa = null,
                locale = "en-US",
            ),
        )
        assertNull(
            selectMessageForLocale(
                customMessageEn = null,
                customMessageJa = "JA only",
                locale = "en-US",
            ),
        )
    }

    @Test
    fun `selectMessageForLocale blank message returns null`() {
        // Blank strings (from a misconfigured server row) MUST be
        // treated as null so the gate falls back to the bundled
        // default copy. A blank dialog message would surface as a
        // visual artifact otherwise.
        assertNull(
            selectMessageForLocale(
                customMessageEn = "   ",
                customMessageJa = null,
                locale = "en-US",
            ),
        )
        assertNull(
            selectMessageForLocale(
                customMessageEn = null,
                customMessageJa = "",
                locale = "ja-JP",
            ),
        )
    }

    @Test
    fun `selectMessageForLocale case-insensitive ja prefix match`() {
        // Defensive: Some platforms uppercase the language code (e.g.
        // legacy Android locale conventions).
        assertEquals(
            "JA msg",
            selectMessageForLocale(
                customMessageEn = "EN msg",
                customMessageJa = "JA msg",
                locale = "JA-JP",
            ),
        )
    }
}
