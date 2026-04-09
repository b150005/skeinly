package com.knitnote.data.sync

/**
 * Recording fake for SyncManager used in repository tests.
 * Records all syncOrEnqueue calls without performing actual sync.
 */
class FakeSyncManager : SyncManagerOperations {
    data class SyncCall(
        val entityType: SyncEntityType,
        val entityId: String,
        val operation: SyncOperation,
        val payload: String,
    )

    val calls = mutableListOf<SyncCall>()

    override suspend fun syncOrEnqueue(
        entityType: SyncEntityType,
        entityId: String,
        operation: SyncOperation,
        payload: String,
    ) {
        calls.add(SyncCall(entityType, entityId, operation, payload))
    }
}
