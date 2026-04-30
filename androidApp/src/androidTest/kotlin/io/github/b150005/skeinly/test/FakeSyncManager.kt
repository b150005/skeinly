package io.github.b150005.skeinly.test

import io.github.b150005.skeinly.data.sync.SyncEntityType
import io.github.b150005.skeinly.data.sync.SyncManagerOperations
import io.github.b150005.skeinly.data.sync.SyncOperation

class FakeSyncManager : SyncManagerOperations {
    override suspend fun syncOrEnqueue(
        entityType: SyncEntityType,
        entityId: String,
        operation: SyncOperation,
        payload: String,
    ) { /* no-op */ }
}
