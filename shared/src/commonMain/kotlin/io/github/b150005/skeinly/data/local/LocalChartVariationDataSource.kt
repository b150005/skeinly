package io.github.b150005.skeinly.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.b150005.skeinly.data.mapper.toDomain
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.domain.model.ChartVariation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LocalChartVariationDataSource(
    private val db: SkeinlyDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val queries get() = db.chartVariationQueries

    suspend fun getById(id: String): ChartVariation? =
        withContext(ioDispatcher) {
            queries.getById(id).executeAsOneOrNull()?.toDomain()
        }

    suspend fun getByPatternIdAndName(
        patternId: String,
        branchName: String,
    ): ChartVariation? =
        withContext(ioDispatcher) {
            queries.getByPatternIdAndName(patternId, branchName).executeAsOneOrNull()?.toDomain()
        }

    suspend fun getByPatternId(patternId: String): List<ChartVariation> =
        withContext(ioDispatcher) {
            queries.getByPatternId(patternId).executeAsList().map { it.toDomain() }
        }

    fun observeByPatternId(patternId: String): Flow<List<ChartVariation>> =
        queries
            .observeByPatternId(patternId)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun upsert(branch: ChartVariation): ChartVariation =
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
