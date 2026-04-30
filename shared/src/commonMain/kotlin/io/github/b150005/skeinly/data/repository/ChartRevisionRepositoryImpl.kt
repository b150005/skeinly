package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.local.LocalChartRevisionDataSource
import io.github.b150005.skeinly.data.remote.RemoteChartRevisionDataSource
import io.github.b150005.skeinly.data.sync.SyncEntityType
import io.github.b150005.skeinly.data.sync.SyncManagerOperations
import io.github.b150005.skeinly.data.sync.SyncOperation
import io.github.b150005.skeinly.domain.model.ChartRevision
import io.github.b150005.skeinly.domain.repository.ChartRevisionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ChartRevisionRepositoryImpl(
    private val local: LocalChartRevisionDataSource,
    private val remote: RemoteChartRevisionDataSource?,
    private val isOnline: StateFlow<Boolean>,
    private val syncManager: SyncManagerOperations,
    private val json: Json,
) : ChartRevisionRepository {
    override suspend fun getRevision(revisionId: String): ChartRevision? {
        val localRow = local.getByRevisionId(revisionId)
        if (localRow != null || remote == null || !isOnline.value) return localRow

        return try {
            remote.getByRevisionId(revisionId)?.also { local.upsert(it) }
        } catch (_: Exception) {
            // Network failure on a cache miss — caller treats as "no such
            // revision" and the next Realtime backfill heals.
            null
        }
    }

    override suspend fun getHistoryForPattern(
        patternId: String,
        limit: Int,
        offset: Int,
    ): List<ChartRevision> {
        val localRows = local.getHistoryForPattern(patternId, limit, offset)
        // Trust the local cache when it has data: Realtime keeps it warm
        // (chart-revisions-<ownerId>) so a hit here is the freshest read.
        // Only reach for the remote when local is empty AND we're online —
        // matches StructuredChartRepositoryImpl.getByPatternId's fallback.
        if (localRows.isNotEmpty() || remote == null || !isOnline.value) return localRows

        return try {
            val remoteRows = remote.getHistoryForPattern(patternId, limit, offset)
            // Hydrate the local cache so subsequent reads — and the
            // observeHistoryForPattern Flow — pick up these rows.
            remoteRows.forEach { local.upsert(it) }
            remoteRows
        } catch (_: Exception) {
            localRows
        }
    }

    override fun observeHistoryForPattern(patternId: String): Flow<List<ChartRevision>> = local.observeHistoryForPattern(patternId)

    override suspend fun append(revision: ChartRevision): ChartRevision {
        local.insert(revision)
        // Enqueue INSERT — append-only per ADR-013 §1. SyncExecutor maps INSERT
        // to remote.append(...). UNIQUE(pattern_id, revision_id) makes retries
        // idempotent at the database layer.
        syncManager.syncOrEnqueue(
            SyncEntityType.CHART_REVISION,
            revision.id,
            SyncOperation.INSERT,
            json.encodeToString(revision),
        )
        return revision
    }
}
