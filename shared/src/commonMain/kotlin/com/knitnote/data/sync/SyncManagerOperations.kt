package com.knitnote.data.sync

/**
 * Interface for sync operations used by repositories.
 * Allows substitution with fakes in tests.
 */
interface SyncManagerOperations {
    suspend fun syncOrEnqueue(
        entityType: String,
        entityId: String,
        operation: String,
        payload: String,
    )
}
