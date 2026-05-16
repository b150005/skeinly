package io.github.b150005.skeinly.ui.util

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DateTimeFormatTest {
    private val utc = TimeZone.UTC

    @Test
    fun `formatShort pads single-digit month and day`() {
        // 2024-01-05T03:07:00Z → "01/05 03:07"
        val instant = Instant.parse("2024-01-05T03:07:00Z")
        assertEquals("01/05 03:07", instant.formatShort(utc))
    }

    @Test
    fun `formatShort formats double-digit values`() {
        // 2024-12-25T14:30:00Z → "12/25 14:30"
        val instant = Instant.parse("2024-12-25T14:30:00Z")
        assertEquals("12/25 14:30", instant.formatShort(utc))
    }

    @Test
    fun `formatFull includes year with padding`() {
        // 2024-01-05T03:07:00Z → "2024/01/05 03:07"
        val instant = Instant.parse("2024-01-05T03:07:00Z")
        assertEquals("2024/01/05 03:07", instant.formatFull(utc))
    }

    @Test
    fun `formatFull formats end of year`() {
        // 2024-12-31T23:59:00Z → "2024/12/31 23:59"
        val instant = Instant.parse("2024-12-31T23:59:00Z")
        assertEquals("2024/12/31 23:59", instant.formatFull(utc))
    }

    @Test
    fun `formatShort handles midnight`() {
        val instant = Instant.parse("2024-06-15T00:00:00Z")
        assertEquals("06/15 00:00", instant.formatShort(utc))
    }

    @Test
    fun `formatDateStamp emits digit-only yyyyMMdd with padding`() {
        // A20 export filename stamp. 2026-05-06 → "20260506".
        val instant = Instant.parse("2026-05-06T09:00:00Z")
        assertEquals("20260506", instant.formatDateStamp(utc))
    }

    @Test
    fun `formatDateStamp handles double-digit month and day`() {
        val instant = Instant.parse("2026-12-25T23:59:00Z")
        assertEquals("20261225", instant.formatDateStamp(utc))
    }

    @Test
    fun `formatDateStamp pads month and day independently`() {
        // Mixed boundary: double-digit month (11) + single-digit day
        // (3) — exercises the per-component padStart that the two tests
        // above don't isolate.
        assertEquals(
            "20261103",
            Instant.parse("2026-11-03T00:00:00Z").formatDateStamp(utc),
        )
        // ...and the converse: single-digit month (1) + double-digit
        // day (31), at year/day boundaries.
        assertEquals(
            "20260131",
            Instant.parse("2026-01-31T12:00:00Z").formatDateStamp(utc),
        )
    }
}
