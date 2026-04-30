package io.github.b150005.skeinly.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * One immutable point in a chart's commit history (ADR-013 §1, §3).
 *
 * Mirrors [StructuredChart] in shape but is conceptually the inverse of the
 * tip pointer: every save appends one of these, and the most recent row's
 * `revisionId` is what `chart_documents.revision_id` reflects.
 *
 * Lineage:
 * - [revisionId] is the canonical commit identifier (UUID, ADR-008 §6).
 * - [parentRevisionId] points at the predecessor revision in the same chart
 *   (chart-level ancestry). Distinct from [Pattern.parentPatternId] which
 *   is pattern-level fork ancestry (ADR-012 §1).
 * - [contentHash] is drawing-identity per ADR-008 §7 — two revisions with
 *   byte-equal documents share a hash, so the diff algorithm can short-
 *   circuit when both sides match.
 *
 * Authorship:
 * - [authorId] is **nullable** to mirror the Postgres `ON DELETE SET NULL`
 *   FK to `profiles` — revision rows outlive author account deletion.
 *   INSERT-time RLS still enforces `author_id = auth.uid()`, so null only
 *   ever appears on rows where the original author has since been deleted.
 *   Phase 37 always writes `authorId == ownerId`; Phase 38 PR/merge is
 *   where they diverge.
 */
@Serializable
data class ChartRevision(
    val id: String,
    @SerialName("pattern_id") val patternId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("author_id") val authorId: String?,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("storage_variant") val storageVariant: StorageVariant,
    @SerialName("coordinate_system") val coordinateSystem: CoordinateSystem,
    val extents: ChartExtents,
    val layers: List<ChartLayer>,
    @SerialName("revision_id") val revisionId: String,
    @SerialName("parent_revision_id") val parentRevisionId: String?,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("commit_message") val commitMessage: String?,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("craft_type") val craftType: CraftType = CraftType.KNIT,
    @SerialName("reading_convention") val readingConvention: ReadingConvention = ReadingConvention.KNIT_FLAT,
)

/**
 * Reconstruct a tip-shaped [StructuredChart] from this revision so the
 * existing `ChartCanvas` renderers (Phase 31, 35) can render historical
 * revisions without a separate render path.
 *
 * `updatedAt` is set to `createdAt` because a revision is immutable —
 * the timestamp at which the historical row was authored serves both
 * roles for tip-shaped consumption.
 */
fun ChartRevision.toStructuredChart(): StructuredChart =
    StructuredChart(
        id = id,
        patternId = patternId,
        ownerId = ownerId,
        schemaVersion = schemaVersion,
        storageVariant = storageVariant,
        coordinateSystem = coordinateSystem,
        extents = extents,
        layers = layers,
        revisionId = revisionId,
        parentRevisionId = parentRevisionId,
        contentHash = contentHash,
        createdAt = createdAt,
        updatedAt = createdAt,
        craftType = craftType,
        readingConvention = readingConvention,
    )
