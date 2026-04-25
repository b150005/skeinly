package io.github.b150005.knitnote.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.b150005.knitnote.data.mapper.toDomain
import io.github.b150005.knitnote.db.KnitNoteDatabase
import io.github.b150005.knitnote.domain.model.ChartBranch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LocalChartBranchDataSource(
    private val db: KnitNoteDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val queries get() = db.chartBranchQueries

    suspend fun getById(id: String): ChartBranch? =
        withContext(ioDispatcher) {
            queries.getById(id).executeAsOneOrNull()?.toDomain()
        }

    suspend fun getByPatternIdAndName(
        patternId: String,
        branchName: String,
    ): ChartBranch? =
        withContext(ioDispatcher) {
            queries.getByPatternIdAndName(patternId, branchName).executeAsOneOrNull()?.toDomain()
        }

    suspend fun getByPatternId(patternId: String): List<ChartBranch> =
        withContext(ioDispatcher) {
            queries.getByPatternId(patternId).executeAsList().map { it.toDomain() }
        }

    fun observeByPatternId(patternId: String): Flow<List<ChartBranch>> =
        queries
            .observeByPatternId(patternId)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun upsert(branch: ChartBranch): ChartBranch =
        withContext(ioDispatcher) {
            queries.upsert(
                id = branch.id,
                pattern_id = branch.patternId,
                owner_id = branch.ownerId,
                branch_name = branch.branchName,
                tip_revision_id = branch.tipRevisionId,
                created_at = branch.createdAt.toString(),
                updated_at = branch.updatedAt.toString(),
            )
            branch
        }

    suspend fun updateTip(
        patternId: String,
        branchName: String,
        tipRevisionId: String,
        updatedAt: kotlin.time.Instant,
    ): Unit =
        withContext(ioDispatcher) {
            queries.updateTip(
                tip_revision_id = tipRevisionId,
                updated_at = updatedAt.toString(),
                pattern_id = patternId,
                branch_name = branchName,
            )
        }

    suspend fun deleteByPatternId(patternId: String): Unit =
        withContext(ioDispatcher) {
            queries.deleteByPatternId(patternId)
        }

    suspend fun deleteById(id: String): Unit =
        withContext(ioDispatcher) {
            queries.deleteById(id)
        }
}
