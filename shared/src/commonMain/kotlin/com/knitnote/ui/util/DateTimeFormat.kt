package com.knitnote.ui.util

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Formats an [Instant] as "MM/dd HH:mm" in the given [timeZone].
 */
fun Instant.formatShort(timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val dt = toLocalDateTime(timeZone)
    val month =
        dt.month.number
            .toString()
            .padStart(2, '0')
    val day = dt.day.toString().padStart(2, '0')
    val hour = dt.hour.toString().padStart(2, '0')
    val minute = dt.minute.toString().padStart(2, '0')
    return "$month/$day $hour:$minute"
}

/**
 * Formats an [Instant] as "yyyy/MM/dd HH:mm" in the given [timeZone].
 */
fun Instant.formatFull(timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val dt = toLocalDateTime(timeZone)
    val year = dt.year.toString().padStart(4, '0')
    val month =
        dt.month.number
            .toString()
            .padStart(2, '0')
    val day = dt.day.toString().padStart(2, '0')
    val hour = dt.hour.toString().padStart(2, '0')
    val minute = dt.minute.toString().padStart(2, '0')
    return "$year/$month/$day $hour:$minute"
}
