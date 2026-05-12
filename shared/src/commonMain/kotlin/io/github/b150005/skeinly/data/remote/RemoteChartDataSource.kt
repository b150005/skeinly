package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.data.sync.RemoteChartSyncOperations
import io.github.b150005.skeinly.domain.model.Chart
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartLayer
import io.github.b150005.skeinly.domain.model.CoordinateSystem
import io.github.b150005.skeinly.domain.model.CraftType
import io.github.b150005.skeinly.domain.model.ReadingConvention
import io.github.b150005.skeinly.domain.model.StorageVariant
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
    // Non-nullable with enum defaults so that:
    //  - rows missing the key deserialize to the default value
    //  - writes with the default enum value may be omitted by
    //    `encodeDefaults = false` kotlinx-serialization config, and the
    //    absence round-trips back to the same default — symmetric and safe
    //  - non-default values (CROCHET, ROUND) are always emitted regardless of
    //    the `encodeDefaults` setting
    @SerialName("craft_type") val craftType: CraftType = CraftType.KNIT,
    @SerialName("reading_convention") val readingConvention: ReadingConvention = ReadingConvention.KNIT_FLAT,
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
    fun toDomain(): Chart =
        Chart(
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
            craftType = document.craftType,
            readingConvention = document.readingConvention,
        )
}

private fun Chart.toRecord(): ChartDocumentRecord =
    ChartDocumentRecord(
        id = id,
        patternId = patternId,
        ownerId = ownerId,
        schemaVersion = schemaVersion,
        storageVariant = storageVariant,
        coordinateSystem = coordinateSystem,
        document =
            ChartDocumentPayload(
                extents = extents,
                layers = layers,
                craftType = craftType,
                readingConvention = readingConvention,
            ),
        revisionId = revisionId,
        parentRevisionId = parentRevisionId,
        contentHash = contentHash,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

class RemoteChartDataSource(
    private val supabaseClient: SupabaseClient,
) : RemoteChartSyncOperations {
    private val table get() = supabaseClient.postgrest["chart_documents"]

    suspend fun getById(id: String): Chart? =
        table
            .select {
                filter { eq("id", id) }
            }.decodeSingleOrNull<ChartDocumentRecord>()
            ?.toDomain()

    suspend fun getByPatternId(patternId: String): Chart? =
        table
            .select {
                filter { eq("pattern_id", patternId) }
            }.decodeSingleOrNull<ChartDocumentRecord>()
            ?.toDomain()

    override suspend fun upsert(chart: Chart): Chart =
        table
            .upsert(chart.toRecord()) {
                select()
            }.decodeSingle<ChartDocumentRecord>()
            .toDomain()

    override suspend fun update(chart: Chart): Chart =
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
