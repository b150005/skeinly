package com.knitnote.data.sync

import com.knitnote.domain.model.Progress

class FakeRemoteProgressDataSource : RemoteProgressSyncOperations {
    val insertedProgress = mutableListOf<Progress>()
    val deletedIds = mutableListOf<String>()
    var shouldFail = false

    override suspend fun insert(progress: Progress): Progress {
        if (shouldFail) throw RuntimeException("Remote insert failed")
        insertedProgress.add(progress)
        return progress
    }

    override suspend fun delete(id: String) {
        if (shouldFail) throw RuntimeException("Remote delete failed")
        deletedIds.add(id)
    }
}
