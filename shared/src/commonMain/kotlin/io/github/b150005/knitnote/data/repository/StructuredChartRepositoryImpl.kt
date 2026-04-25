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
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun forkFor(
        sourcePatternId: String,
        newPatternId: String,
        newOwnerId: String,
    ): StructuredChart? {
        // getByPatternId() resolves local-first then falls back to remote when online,
        // so a forker who has not yet visited the source pattern still hits a fresh
        // copy. Returns null when the source has no chart at all — caller (ADR-012 §3)
        // treats this as "nothing to clone" and proceeds with a metadata-only fork.
        val source = getByPatternId(sourcePatternId) ?: return null
        val now = Clock.System.now()
        val cloned =
            source.copy(
                id = Uuid.random().toString(),
                patternId = newPatternId,
                ownerId = newOwnerId,
                revisionId = Uuid.random().toString(),
                // ADR-012 §2: commit-rooted lineage. The fork's first revision points
                // back at the source's revision so Phase 37 collaboration can walk
                // ancestry without retroactive inference.
                parentRevisionId = source.revisionId,
                // contentHash carried verbatim — drawing identity is unchanged on a
                // fork per ADR-008 §7 (`content_hash` describes drawable content,
                // not ownership/lineage).
                createdAt = now,
                updatedAt = now,
            )
        local.insert(cloned)
        syncManager.syncOrEnqueue(
            SyncEntityType.STRUCTURED_CHART,
            cloned.id,
            SyncOperation.INSERT,
            json.encodeToString(cloned),
        )
        return cloned
    }
}
