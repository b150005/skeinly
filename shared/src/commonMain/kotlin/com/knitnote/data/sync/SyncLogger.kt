package com.knitnote.data.sync

/**
 * Minimal logger for sync subsystem diagnostics.
 * Platform-specific implementations use native logging (Android: Log.d, iOS: NSLog).
 * Tests can supply a capturing implementation.
 */
interface SyncLogger {
    fun log(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )
}

/**
 * Platform-specific default logger for the sync subsystem.
 * - Android: uses android.util.Log
 * - iOS: uses platform.Foundation.NSLog
 *
 * Note: When adding a new KMP target (JVM, desktop, etc.), provide an `actual val`
 * in the corresponding source set or the build will fail to resolve this expect.
 */
internal expect val DefaultSyncLogger: SyncLogger
