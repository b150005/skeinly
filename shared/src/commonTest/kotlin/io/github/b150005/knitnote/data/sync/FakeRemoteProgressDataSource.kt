package io.github.b150005.knitnote.data.sync

import io.github.b150005.knitnote.domain.model.Progress

class FakeRemoteProgressDataSource : RemoteProgressSyncOperations {
    val upsertedProgress = mutableListOf<Progress>()
    val deletedIds = mutableListOf<String>()
    var shouldFail = false

    override suspend fun upsert(progress: Progress): Progress {
        if (shouldFail) throw RuntimeException("Remote upsert failed")
        upsertedProgress.add(progress)
        return progress
    }

    override suspend fun delete(id: String) {
        if (shouldFail) throw RuntimeException("Remote delete failed")
        deletedIds.add(id)
    }
}
