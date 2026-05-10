package io.github.b150005.skeinly.data.sync

import io.github.b150005.skeinly.domain.model.ChartRevision

/**
 * Recording fake for the chart-revisions remote sync surface. Append-only
 * per ADR-013 §1, mirroring the production [RemoteChartRevisionSyncOperations]
 * contract — no UPDATE / DELETE methods to wire here.
 */
class FakeRemoteChartRevisionDataSource : RemoteChartRevisionSyncOperations {
    val appendedRevisions = mutableListOf<ChartRevision>()
    var shouldFail = false

    override suspend fun append(revision: ChartRevision): ChartRevision {
        if (shouldFail) throw RuntimeException("Fake remote append failure")
        appendedRevisions.add(revision)
        return revision
    }
}
