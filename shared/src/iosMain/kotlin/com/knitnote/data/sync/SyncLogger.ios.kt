package com.knitnote.data.sync

import platform.Foundation.NSLog

internal actual val DefaultSyncLogger: SyncLogger =
    object : SyncLogger {
        override fun log(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            NSLog("[$tag] $message")
            throwable?.let { NSLog("[$tag] ${it.stackTraceToString()}") }
        }
    }
