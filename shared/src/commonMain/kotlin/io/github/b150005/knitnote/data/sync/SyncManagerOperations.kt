package io.github.b150005.knitnote.data.sync

/**
 * Interface for sync operations used by repositories.
 * Allows substitution with fakes in tests.
 */
interface SyncManagerOperations {
    suspend fun syncOrEnqueue(
        entityType: SyncEntityType,
        entityId: String,
        operation: SyncOperation,
        payload: String,
    )
}
