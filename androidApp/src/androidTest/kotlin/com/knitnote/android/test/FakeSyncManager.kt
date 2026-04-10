package com.knitnote.android.test

import com.knitnote.data.sync.SyncEntityType
import com.knitnote.data.sync.SyncManagerOperations
import com.knitnote.data.sync.SyncOperation

class FakeSyncManager : SyncManagerOperations {
    override suspend fun syncOrEnqueue(
        entityType: SyncEntityType,
        entityId: String,
        operation: SyncOperation,
        payload: String,
    ) { /* no-op */ }
}
