package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.local.LocalStructuredChartDataSource
import io.github.b150005.knitnote.data.remote.RemoteStructuredChartDataSource
import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncManagerOperations
import io.github.b150005.knitnote.data.sync.SyncOperation
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class StructuredChartRepositoryImpl(
    private val local: LocalStructuredChartDataSource,
    private val remote: RemoteStructuredChartDataSource?,
    private val isOnline: StateFlow<Boolean>,
    private val syncManager: SyncManagerOperations,
    private val json: Json,
) : StructuredChartRepository {
    override suspend fun getByPatternId(patternId: String): StructuredChart? {
        val localChart = local.getByPatternId(patternId)
        if (localChart != null || remote == null || !isOnline.value) return localChart

        return try {
            remote.getByPatternId(patternId)?.also { local.upsert(it) }
        } catch (_: Exception) {
            localChart
        }
    }

    override fun observeByPatternId(patternId: String): Flow<StructuredChart?> = local.observeByPatternId(patternId)

    override suspend fun existsByPatternId(patternId: String): Boolean = local.existsByPatternId(patternId)

    override suspend fun create(chart: StructuredChart): StructuredChart {
        local.insert(chart)
        syncManager.syncOrEnqueue(
            SyncEntityType.STRUCTURED_CHART,
            chart.id,
            SyncOperation.INSERT,
            json.encodeToString(chart),
        )
        return chart
    }

    override suspend fun update(chart: StructuredChart): StructuredChart {
        local.update(chart)
        syncManager.syncOrEnqueue(
            SyncEntityType.STRUCTURED_CHART,
            chart.id,
            SyncOperation.UPDATE,
            json.encodeToString(chart),
        )
        return chart
    }

    override suspend fun delete(id: String) {
        local.delete(id)
        syncManager.syncOrEnqueue(SyncEntityType.STRUCTURED_CHART, id, SyncOperation.DELETE, "")
    }
}
