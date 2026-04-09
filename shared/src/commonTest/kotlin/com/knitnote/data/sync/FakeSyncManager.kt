package com.knitnote.data.sync

/**
 * Recording fake for SyncManager used in repository tests.
 * Records all syncOrEnqueue calls without performing actual sync.
 */
class FakeSyncManager : SyncManagerOperations {
    data class SyncCall(
        val entityType: String,
        val entityId: String,
        val operation: String,
        val payload: String,
    )

    val calls = mutableListOf<SyncCall>()

    override suspend fun syncOrEnqueue(
        entityType: String,
        entityId: String,
        operation: String,
        payload: String,
    ) {
        calls.add(SyncCall(entityType, entityId, operation, payload))
    }
}
