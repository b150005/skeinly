package io.github.b150005.skeinly.data.sync

import io.github.b150005.skeinly.domain.model.ChartBranch

class FakeRemoteChartBranchDataSource : RemoteChartBranchSyncOperations {
    val upsertedBranches = mutableListOf<ChartBranch>()
    val deletedIds = mutableListOf<String>()
    var shouldFail = false

    override suspend fun upsert(branch: ChartBranch): ChartBranch {
        if (shouldFail) throw RuntimeException("Fake remote branch upsert failure")
        upsertedBranches.add(branch)
        return branch
    }

    override suspend fun delete(id: String) {
        if (shouldFail) throw RuntimeException("Fake remote branch delete failure")
        deletedIds.add(id)
    }
}
