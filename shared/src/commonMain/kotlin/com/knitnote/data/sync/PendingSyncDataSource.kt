package com.knitnote.data.sync

/**
 * Interface for pending sync queue operations.
 * Implemented by LocalPendingSyncDataSource (SQLDelight) and fakes for testing.
 */
interface PendingSyncDataSource {
    suspend fun enqueue(
        entityType: String,
        entityId: String,
        operation: String,
        payload: String,
        createdAt: Long,
    )
    suspend fun getAllPending(): List<PendingSyncEntry>
    suspend fun getById(id: Long): PendingSyncEntry?
    suspend fun getByEntityId(entityId: String): List<PendingSyncEntry>
    suspend fun updatePayload(id: Long, payload: String)
    suspend fun incrementRetry(id: Long)
    suspend fun markFailed(id: Long)
    suspend fun delete(id: Long)
    suspend fun countPending(): Long
}
