package com.knitnote.data.sync

data class PendingSyncEntry(
    val id: Long,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: String,
    val createdAt: Long,
    val retryCount: Int,
    val status: String,
)
