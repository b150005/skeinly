package com.knitnote.data.sync

/**
 * Minimal logger for sync subsystem diagnostics.
 * Uses println by default; tests can supply a capturing implementation.
 */
interface SyncLogger {
    fun log(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )
}

internal val DefaultSyncLogger =
    object : SyncLogger {
        override fun log(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            println("[$tag] $message")
            throwable?.let { println("[$tag] ${it.stackTraceToString()}") }
        }
    }
