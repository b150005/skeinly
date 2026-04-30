package io.github.b150005.skeinly.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.b150005.skeinly.data.mapper.toDbString
import io.github.b150005.skeinly.data.mapper.toDocumentJson
import io.github.b150005.skeinly.data.mapper.toDomain
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.domain.model.ChartRevision
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class LocalChartRevisionDataSource(
    private val db: SkeinlyDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val json: Json,
) {
    private val queries get() = db.chartRevisionQueries

    suspend fun getById(id: String): ChartRevision? =
        withContext(ioDispatcher) {
            queries.getById(id).executeAsOneOrNull()?.safeToDomain()
        }

    suspend fun getByRevisionId(revisionId: String): ChartRevision? =
        withContext(ioDispatcher) {
            queries.getByRevisionId(revisionId).executeAsOneOrNull()?.safeToDomain()
        }

    suspend fun getHistoryForPattern(
        patternId: String,
        limit: Int,
        offset: Int,
    ): List<ChartRevision> =
        withContext(ioDispatcher) {
            queries
                .getHistoryForPattern(patternId, limit.toLong(), offset.toLong())
                .executeAsList()
                .mapNotNull { it.safeToDomain() }
        }

    fun observeHistoryForPattern(patternId: String): Flow<List<ChartRevision>> =
        queries
            .observeHistoryForPattern(patternId)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.mapNotNull { it.safeToDomain() } }

    /**
     * One bad row must not kill the flow — downstream callers read this as
     * "revision unrecoverable" and the next remote sync round can re-decode
     * a fresh copy. Mirrors the [LocalStructuredChartDataSource.safeToDomain]
     * idiom.
     */
    private fun io.github.b150005.skeinly.db.ChartRevisionEntity.safeToDomain(): ChartRevision? = runCatching { toDomain(json) }.getOrNull()

    suspend fun insert(revision: ChartRevision): ChartRevision =
        withContext(ioDispatcher) {
            queries.insert(
                id = revision.id,
                pattern_id = revision.patternId,
                owner_id = revision.ownerId,
                schema_version = revision.schemaVersion.toLong(),
                storage_variant = revision.storageVariant.toDbString(),
                coordinate_system = revision.coordinateSystem.toDbString(),
                document = revision.toDocumentJson(json),
                revision_id = revision.revisionId,
                parent_revision_id = revision.parentRevisionId,
                content_hash = revision.contentHash,
                commit_message = revision.commitMessage,
                author_id = revision.authorId,
                created_at = revision.createdAt.toString(),
            )
            revision
        }

    /**
     * Idempotent INSERT used by the Realtime backfill path: the same revision
     * may arrive locally first (self-authored append) and again via Realtime
     * a moment later. UNIQUE(pattern_id, revision_id) is the dedup key, so
     * INSERT OR IGNORE silently no-ops on the second arrival.
     */
    suspend fun upsert(revision: ChartRevision): Unit =
        withContext(ioDispatcher) {
            queries.upsert(
                id = revision.id,
                pattern_id = revision.patternId,
                owner_id = revision.ownerId,
                schema_version = revision.schemaVersion.toLong(),
                storage_variant = revision.storageVariant.toDbString(),
                coordinate_system = revision.coordinateSystem.toDbString(),
                document = revision.toDocumentJson(json),
                revision_id = revision.revisionId,
                parent_revision_id = revision.parentRevisionId,
                content_hash = revision.contentHash,
                commit_message = revision.commitMessage,
                author_id = revision.authorId,
                created_at = revision.createdAt.toString(),
            )
        }

    suspend fun countForPattern(patternId: String): Long =
        withContext(ioDispatcher) {
            queries.countForPattern(patternId).executeAsOne()
        }

    suspend fun deleteByPatternId(patternId: String): Unit =
        withContext(ioDispatcher) {
            queries.deleteByPatternId(patternId)
        }
}
