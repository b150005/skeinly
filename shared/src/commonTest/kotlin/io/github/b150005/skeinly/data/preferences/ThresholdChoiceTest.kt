package io.github.b150005.skeinly.data.preferences

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 26.6 (ADR-022 §6.5) — locks the bounded-set coercion on
 * [ThresholdChoice.fromSeconds] so a future caller passing an
 * arbitrary Long (UI bug, stale Settings row from a beta-era schema
 * drift) cannot persist an out-of-range threshold.
 */
class ThresholdChoiceTest {
    @Test
    fun `fromSeconds maps each canonical value back to its enum`() {
        assertEquals(ThresholdChoice.OneMinute, ThresholdChoice.fromSeconds(60))
        assertEquals(ThresholdChoice.FiveMinutes, ThresholdChoice.fromSeconds(300))
        assertEquals(ThresholdChoice.FifteenMinutes, ThresholdChoice.fromSeconds(900))
        assertEquals(ThresholdChoice.OneHour, ThresholdChoice.fromSeconds(3600))
    }

    @Test
    fun `fromSeconds coerces out-of-range values to FiveMinutes default`() {
        assertEquals(ThresholdChoice.FiveMinutes, ThresholdChoice.fromSeconds(0))
        assertEquals(ThresholdChoice.FiveMinutes, ThresholdChoice.fromSeconds(-1))
        assertEquals(ThresholdChoice.FiveMinutes, ThresholdChoice.fromSeconds(120))
        assertEquals(ThresholdChoice.FiveMinutes, ThresholdChoice.fromSeconds(86_400))
    }
}
