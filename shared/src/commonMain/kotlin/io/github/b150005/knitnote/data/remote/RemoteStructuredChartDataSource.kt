package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.data.sync.RemoteStructuredChartSyncOperations
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * jsonb payload of `chart_documents.document`.
 * Routing metadata (id, ownerId, schemaVersion, etc.) live on dedicated columns of
 * the row, not inside the jsonb. This keeps RLS / FK / indexing cheap and leaves the
 * jsonb column to the chart's actual drawable content.
 */
@Serializable
private data class ChartDocumentPayload(
    val extents: ChartExtents,
    val layers: List<ChartLayer>,
)

@Serializable
private data class ChartDocumentRecord(
    val id: String,
    @SerialName("pattern_id") val patternId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("storage_variant") val storageVariant: StorageVariant,
    @SerialName("coordinate_system") val coordinateSystem: CoordinateSystem,
    val document: ChartDocumentPayload,
    @SerialName("revision_id") val revisionId: String,
    @SerialName("parent_revision_id") val parentRevisionId: String?,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
) {
    fun toDomain(): StructuredChart =
        StructuredChart(
            id = id,
            patternId = patternId,
            ownerId = ownerId,
            schemaVersion = schemaVersion,
            storageVariant = storageVariant,
            coordinateSystem = coordinateSystem,
            extents = document.extents,
            layers = document.layers,
            revisionId = revisionId,
            parentRevisionId = parentRevisionId,
            contentHash = contentHash,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}

private fun StructuredChart.toRecord(): ChartDocumentRecord =
    ChartDocumentRecord(
        id = id,
        patternId = patternId,
        ownerId = ownerId,
        schemaVersion = schemaVersion,
        storageVariant = storageVariant,
        coordinateSystem = coordinateSystem,
        document = ChartDocumentPayload(extents = extents, layers = layers),
        revisionId = revisionId,
        parentRevisionId = parentRevisionId,
        contentHash = contentHash,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

class RemoteStructuredChartDataSource(
    private val supabaseClient: SupabaseClient,
) : RemoteStructuredChartSyncOperations {
    private val table get() = supabaseClient.postgrest["chart_documents"]

    suspend fun getById(id: String): StructuredChart? =
        table
            .select {
                filter { eq("id", id) }
            }.decodeSingleOrNull<ChartDocumentRecord>()
            ?.toDomain()

    suspend fun getByPatternId(patternId: String): StructuredChart? =
        table
            .select {
                filter { eq("pattern_id", patternId) }
            }.decodeSingleOrNull<ChartDocumentRecord>()
            ?.toDomain()

    override suspend fun upsert(chart: StructuredChart): StructuredChart =
        table
            .upsert(chart.toRecord()) {
                select()
            }.decodeSingle<ChartDocumentRecord>()
            .toDomain()

    override suspend fun update(chart: StructuredChart): StructuredChart =
        table
            .update(chart.toRecord()) {
                select()
                filter { eq("id", chart.id) }
            }.decodeSingle<ChartDocumentRecord>()
            .toDomain()

    override suspend fun delete(id: String) {
        table.delete {
            filter { eq("id", id) }
        }
    }
}
