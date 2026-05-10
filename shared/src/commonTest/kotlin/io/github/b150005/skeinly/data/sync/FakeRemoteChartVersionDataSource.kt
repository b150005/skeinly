package io.github.b150005.skeinly.data.sync

import io.github.b150005.skeinly.domain.model.ChartVersion

/**
 * Recording fake for the chart-revisions remote sync surface. Append-only
 * per ADR-013 §1, mirroring the production [RemoteChartVersionSyncOperations]
 * contract — no UPDATE / DELETE methods to wire here.
 */
class FakeRemoteChartVersionDataSource : RemoteChartVersionSyncOperations {
    val appendedRevisions = mutableListOf<ChartVersion>()
    var shouldFail = false

    override suspend fun append(revision: ChartVersion): ChartVersion {
        if (shouldFail) throw RuntimeException("Fake remote append failure")
        appendedRevisions.add(revision)
        return revision
    }
}
