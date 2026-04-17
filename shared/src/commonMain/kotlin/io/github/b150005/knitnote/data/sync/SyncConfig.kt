package io.github.b150005.knitnote.data.sync

data class SyncConfig(
    val maxRetries: Int = 5,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 60_000,
    val jitterFactor: Double = 0.2,
)
