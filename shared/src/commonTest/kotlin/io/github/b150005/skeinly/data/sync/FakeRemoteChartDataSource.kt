package io.github.b150005.skeinly.data.sync

import io.github.b150005.skeinly.domain.model.Chart

class FakeRemoteChartDataSource : RemoteChartSyncOperations {
    val upsertedCharts = mutableListOf<Chart>()
    val updatedCharts = mutableListOf<Chart>()
    val deletedIds = mutableListOf<String>()
    var shouldFail = false

    override suspend fun upsert(chart: Chart): Chart {
        if (shouldFail) throw RuntimeException("Fake remote upsert failure")
        upsertedCharts.add(chart)
        return chart
    }

    override suspend fun update(chart: Chart): Chart {
        if (shouldFail) throw RuntimeException("Fake remote update failure")
        updatedCharts.add(chart)
        return chart
    }

    override suspend fun delete(id: String) {
        if (shouldFail) throw RuntimeException("Fake remote delete failure")
        deletedIds.add(id)
    }
}
