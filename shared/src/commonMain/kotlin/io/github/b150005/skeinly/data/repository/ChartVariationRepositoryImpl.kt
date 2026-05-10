package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.local.LocalChartVariationDataSource
import io.github.b150005.skeinly.data.sync.SyncEntityType
import io.github.b150005.skeinly.data.sync.SyncManagerOperations
import io.github.b150005.skeinly.data.sync.SyncOperation
import io.github.b150005.skeinly.domain.model.ChartVariation
import io.github.b150005.skeinly.domain.repository.ChartVariationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

class ChartVariationRepositoryImpl(
    private val local: LocalChartVariationDataSource,
    private val syncManager: SyncManagerOperations,
    private val json: Json,
) : ChartVariationRepository {
    override suspend fun getByPatternIdAndName(
        patternId: String,
        branchName: String,
    ): ChartVariation? = local.getByPatternIdAndName(patternId, branchName)

    override suspend fun getByPatternId(patternId: String): List<ChartVariation> = local.getByPatternId(patternId)

    override fun observeBranchesForPattern(patternId: String): Flow<List<ChartVariation>> = local.observeByPatternId(patternId)

    override suspend fun createBranch(branch: ChartVariation): ChartVariation {
        // Idempotent re-create: if a branch with the same (pattern_id,
        // branch_name) already exists, return it unchanged. The local
        // upsert is INSERT OR IGNORE so it would silently no-op anyway,
        // but reading first keeps us from enqueuing a duplicate INSERT
        // through the sync layer (same defense as `ensureDefaultBranch`
        // in ChartRepositoryImpl).
        val existing = local.getByPatternIdAndName(branch.patternId, branch.branchName)
        if (existing != null) return existing

        local.upsert(branch)
        syncManager.syncOrEnqueue(
            SyncEntityType.CHART_BRANCH,
            branch.id,
            SyncOperation.INSERT,
            json.encodeToString(branch),
        )
        return branch
    }

    override suspend fun advanceTip(
        patternId: String,
        branchName: String,
        tipRevisionId: String,
    ) {
        val existing = local.getByPatternIdAndName(patternId, branchName) ?: return
        val now = Clock.System.now()
        local.updateTip(patternId, branchName, tipRevisionId, now)
        // UPDATE keeps the same row id so sync is targeted at the existing
        // server-side row. SyncExecutor maps UPDATE → upsert with onConflict
        // on (pattern_id, branch_name).
        val advanced = existing.copy(tipRevisionId = tipRevisionId, updatedAt = now)
        syncManager.syncOrEnqueue(
            SyncEntityType.CHART_BRANCH,
            existing.id,
            SyncOperation.UPDATE,
            json.encodeToString(advanced),
        )
    }

    override suspend fun deleteBranch(branchId: String) {
        local.deleteById(branchId)
        syncManager.syncOrEnqueue(
            SyncEntityType.CHART_BRANCH,
            branchId,
            SyncOperation.DELETE,
            "",
        )
    }
}
