package com.knitnote.ui.util

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
}
