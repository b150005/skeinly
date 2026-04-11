package com.knitnote.data.sync

class CapturingSyncLogger : SyncLogger {
    data class LogEntry(val tag: String, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<LogEntry>()

    override fun log(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        entries.add(LogEntry(tag, message, throwable))
    }
}
