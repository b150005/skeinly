package com.knitnote.data.sync

data class RealtimeConfig(
    val maxRetries: Int = 5,
    val baseDelayMs: Long = 2_000,
    val maxDelayMs: Long = 60_000,
    val jitterFactor: Double = 0.2,
)
