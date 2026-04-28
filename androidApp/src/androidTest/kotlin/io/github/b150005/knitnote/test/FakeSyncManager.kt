package io.github.b150005.knitnote.test

import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncManagerOperations
import io.github.b150005.knitnote.data.sync.SyncOperation

class FakeSyncManager : SyncManagerOperations {
    override suspend fun syncOrEnqueue(
        entityType: SyncEntityType,
        entityId: String,
        operation: SyncOperation,
        payload: String,
    ) { /* no-op */ }
}
