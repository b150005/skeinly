package com.knitnote.ui.util

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Formats an [Instant] as "MM/dd HH:mm" in the system default timezone.
 */
fun Instant.formatShort(): String {
    val dt = toLocalDateTime(TimeZone.currentSystemDefault())
    val month = dt.monthNumber.toString().padStart(2, '0')
    val day = dt.dayOfMonth.toString().padStart(2, '0')
    val hour = dt.hour.toString().padStart(2, '0')
    val minute = dt.minute.toString().padStart(2, '0')
    return "$month/$day $hour:$minute"
}

/**
 * Formats an [Instant] as "yyyy/MM/dd HH:mm" in the system default timezone.
 */
fun Instant.formatFull(): String {
    val dt = toLocalDateTime(TimeZone.currentSystemDefault())
    val year = dt.year.toString().padStart(4, '0')
    val month = dt.monthNumber.toString().padStart(2, '0')
    val day = dt.dayOfMonth.toString().padStart(2, '0')
    val hour = dt.hour.toString().padStart(2, '0')
    val minute = dt.minute.toString().padStart(2, '0')
    return "$year/$month/$day $hour:$minute"
}
