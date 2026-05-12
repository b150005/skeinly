package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.data.sync.RemoteChartVersionSyncOperations
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartLayer
import io.github.b150005.skeinly.domain.model.ChartVersion
import io.github.b150005.skeinly.domain.model.CoordinateSystem
import io.github.b150005.skeinly.domain.model.CraftType
import io.github.b150005.skeinly.domain.model.ReadingConvention
import io.github.b150005.skeinly.domain.model.StorageVariant
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * jsonb payload of `chart_versions.document`. Mirrors the chart_documents
 * envelope intentionally — every version row carries the same drawing
 * payload shape that the tip pointer carries (ADR-013 §3).
 */
@Serializable
private data class RevisionDocumentPayload(
    val extents: ChartExtents,
    val layers: List<ChartLayer>,
    @SerialName("craft_type") val craftType: CraftType = CraftType.KNIT,
    @SerialName("reading_convention") val readingConvention: ReadingConvention = ReadingConvention.KNIT_FLAT,
)

@Serializable
private data class ChartVersionRecord(
    val id: String,
    @SerialName("pattern_id") val patternId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("author_id") val authorId: String?,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("storage_variant") val storageVariant: StorageVariant,
    @SerialName("coordinate_system") val coordinateSystem: CoordinateSystem,
    val document: RevisionDocumentPayload,
    @SerialName("revision_id") val revisionId: String,
    @SerialName("parent_revision_id") val parentRevisionId: String?,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("commit_message") val commitMessage: String?,
    @SerialName("created_at") val createdAt: Instant,
) {
    fun toDomain(): ChartVersion =
        ChartVersion(
            id = id,
            patternId = patternId,
            ownerId = ownerId,
            authorId = authorId,
            schemaVersion = schemaVersion,
            storageVariant = storageVariant,
            coordinateSystem = coordinateSystem,
            extents = document.extents,
            layers = document.layers,
            revisionId = revisionId,
            parentRevisionId = parentRevisionId,
            contentHash = contentHash,
            commitMessage = commitMessage,
            createdAt = createdAt,
            craftType = document.craftType,
            readingConvention = document.readingConvention,
        )
}

private fun ChartVersion.toRecord(): ChartVersionRecord =
    ChartVersionRecord(
        id = id,
        patternId = patternId,
        ownerId = ownerId,
        authorId = authorId,
        schemaVersion = schemaVersion,
        storageVariant = storageVariant,
        coordinateSystem = coordinateSystem,
        document =
            RevisionDocumentPayload(
                extents = extents,
                layers = layers,
                craftType = craftType,
                readingConvention = readingConvention,
            ),
        revisionId = revisionId,
        parentRevisionId = parentRevisionId,
        contentHash = contentHash,
        commitMessage = commitMessage,
        createdAt = createdAt,
    )

class RemoteChartVersionDataSource(
    private val supabaseClient: SupabaseClient,
) : RemoteChartVersionSyncOperations {
    private val table get() = supabaseClient.postgrest["chart_versions"]

    suspend fun getByRevisionId(revisionId: String): ChartVersion? =
        table
            .select {
                filter { eq("revision_id", revisionId) }
            }.decodeSingleOrNull<ChartVersionRecord>()
            ?.toDomain()

    suspend fun getHistoryForPattern(
        patternId: String,
        limit: Int,
        offset: Int,
    ): List<ChartVersion> =
        table
            .select {
                filter { eq("pattern_id", patternId) }
                order("created_at", Order.DESCENDING)
                range(offset.toLong(), (offset + limit - 1).toLong())
            }.decodeList<ChartVersionRecord>()
            .map { it.toDomain() }

    /**
     * Append the new revision. RLS forbids UPDATE/DELETE so this is the only
     * write path; SyncExecutor maps INSERT to this method.
     *
     * Implemented as an `upsert` with `ignoreDuplicates = true` and
     * `onConflict = "revision_id"` so a re-enqueued append on the same
     * revision_id (PendingSync retry after the request landed but the response
     * was lost) is a silent no-op rather than surfacing as a 23505
     * unique-violation that the sync layer would mark FAILED. Revisions are
     * immutable once written (RLS forbids UPDATE), so `ignoreDuplicates` is
     * the semantically correct shape — we never want to overwrite an existing
     * revision row, only insert it once.
     *
     * The decode path uses `decodeSingleOrNull` so the no-op-on-duplicate
     * case (which returns an empty representation) doesn't throw. We fall
     * back to the input revision since the on-disk row is byte-identical
     * to it by virtue of being the same revision_id.
     */
    override suspend fun append(revision: ChartVersion): ChartVersion {
        val result =
            table
                .upsert(revision.toRecord()) {
                    onConflict = "revision_id"
                    ignoreDuplicates = true
                    select()
                }.decodeSingleOrNull<ChartVersionRecord>()
        return result?.toDomain() ?: revision
    }
}
