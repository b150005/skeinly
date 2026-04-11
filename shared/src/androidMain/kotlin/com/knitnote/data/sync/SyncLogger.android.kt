package com.knitnote.data.sync

import android.util.Log

internal actual val DefaultSyncLogger: SyncLogger =
    object : SyncLogger {
        override fun log(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.d(tag, message)
            }
        }
    }
