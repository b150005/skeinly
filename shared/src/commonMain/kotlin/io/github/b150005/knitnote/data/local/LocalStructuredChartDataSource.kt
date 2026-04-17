package io.github.b150005.knitnote.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.github.b150005.knitnote.data.mapper.toDbString
import io.github.b150005.knitnote.data.mapper.toDocumentJson
import io.github.b150005.knitnote.data.mapper.toDomain
import io.github.b150005.knitnote.db.KnitNoteDatabase
import io.github.b150005.knitnote.domain.model.StructuredChart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class LocalStructuredChartDataSource(
    private val db: KnitNoteDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val json: Json,
) {
    private val queries get() = db.structuredChartQueries

    suspend fun getById(id: String): StructuredChart? =
        withContext(ioDispatcher) {
            queries.getById(id).executeAsOneOrNull()?.safeToDomain()
        }

    suspend fun getByPatternId(patternId: String): StructuredChart? =
        withContext(ioDispatcher) {
            queries.getByPatternId(patternId).executeAsOneOrNull()?.safeToDomain()
        }

    fun observeByPatternId(patternId: String): Flow<StructuredChart?> =
        queries
            .observeByPatternId(patternId)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { entity -> entity?.safeToDomain() }

    /**
     * Decode one row to domain, or `null` if the stored `document` jsonb is corrupt
     * or encodes a schema we do not recognise. A single bad row must not kill the
     * flow — downstream callers read this as "no chart" and will heal on next
     * remote sync.
     */
    private fun io.github.b150005.knitnote.db.StructuredChartEntity.safeToDomain(): StructuredChart? =
        runCatching { toDomain(json) }.getOrNull()

    suspend fun existsByPatternId(patternId: String): Boolean =
        withContext(ioDispatcher) {
            queries.existsByPatternId(patternId).executeAsOne()
        }

    suspend fun insert(chart: StructuredChart): StructuredChart =
        withContext(ioDispatcher) {
            queries.insert(
                id = chart.id,
                pattern_id = chart.patternId,
                owner_id = chart.ownerId,
                schema_version = chart.schemaVersion.toLong(),
                storage_variant = chart.storageVariant.toDbString(),
                coordinate_system = chart.coordinateSystem.toDbString(),
                document = chart.toDocumentJson(json),
                revision_id = chart.revisionId,
                parent_revision_id = chart.parentRevisionId,
                content_hash = chart.contentHash,
                created_at = chart.createdAt.toString(),
                updated_at = chart.updatedAt.toString(),
            )
            chart
        }

    suspend fun update(chart: StructuredChart): StructuredChart =
        withContext(ioDispatcher) {
            queries.update(
                schema_version = chart.schemaVersion.toLong(),
                storage_variant = chart.storageVariant.toDbString(),
                coordinate_system = chart.coordinateSystem.toDbString(),
                document = chart.toDocumentJson(json),
                revision_id = chart.revisionId,
                parent_revision_id = chart.parentRevisionId,
                content_hash = chart.contentHash,
                updated_at = chart.updatedAt.toString(),
                id = chart.id,
            )
            chart
        }

    /**
     * Replace-or-insert by `pattern_id`. The remote row may have a different `id`
     * than the locally cached one (e.g., re-created upstream) while referring to
     * the same pattern, so we key the reconciliation on the UNIQUE `pattern_id`
     * column rather than on `id`.
     */
    suspend fun upsert(chart: StructuredChart): Unit =
        withContext(ioDispatcher) {
            db.transaction {
                queries.deleteByPatternId(chart.patternId)
                queries.insert(
                    id = chart.id,
                    pattern_id = chart.patternId,
                    owner_id = chart.ownerId,
                    schema_version = chart.schemaVersion.toLong(),
                    storage_variant = chart.storageVariant.toDbString(),
                    coordinate_system = chart.coordinateSystem.toDbString(),
                    document = chart.toDocumentJson(json),
                    revision_id = chart.revisionId,
                    parent_revision_id = chart.parentRevisionId,
                    content_hash = chart.contentHash,
                    created_at = chart.createdAt.toString(),
                    updated_at = chart.updatedAt.toString(),
                )
            }
        }

    suspend fun delete(id: String): Unit =
        withContext(ioDispatcher) {
            queries.deleteById(id)
        }

    suspend fun deleteByPatternId(patternId: String): Unit =
        withContext(ioDispatcher) {
            queries.deleteByPatternId(patternId)
        }
}
