package io.github.b150005.knitnote.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

@Serializable
enum class CoordinateSystem {
    @SerialName("rect_grid")
    RECT_GRID,

    @SerialName("polar_round")
    POLAR_ROUND,
}

@Serializable
enum class StorageVariant {
    @SerialName("inline")
    INLINE,

    @SerialName("chunked")
    CHUNKED,
}

/**
 * Craft a chart describes. Distinct from rendering — drives which symbol
 * palette categories are surfaced by default and, together with
 * [ReadingConvention], disambiguates row-numbering direction conventions
 * when a chart is opened standalone (discovery, fork, collaboration).
 *
 * Phase 32.1 introduces this as schema v2 metadata inside the document
 * envelope; the editor UI does not expose a picker yet — it always uses
 * [CraftType.KNIT] as the default for newly-authored charts.
 */
@Serializable
enum class CraftType {
    @SerialName("knit")
    KNIT,

    @SerialName("crochet")
    CROCHET,
}

/**
 * How a reader is expected to traverse the chart. Independent from
 * [CoordinateSystem] (which is purely geometric) — two charts with the
 * same geometry can be read in different directions.
 *
 * Values:
 * - [KNIT_FLAT]: RS rows right→left, WS rows left→right (standard knit chart convention).
 * - [CROCHET_FLAT]: every row left→right (common crochet chart convention).
 * - [ROUND]: center-outward (or bottom-up spiral) — used for amigurumi, doilies, crochet rounds.
 *
 * Phase 32.1 stores this but the renderer does not yet act on it. Phase 34
 * per-segment progress and Phase 35 advanced editor will consume it for
 * row-number display and symmetry ops.
 */
@Serializable
enum class ReadingConvention {
    @SerialName("knit_flat")
    KNIT_FLAT,

    @SerialName("crochet_flat")
    CROCHET_FLAT,

    @SerialName("round")
    ROUND,
}

@Serializable
sealed interface ChartExtents {
    @Serializable
    @SerialName("rect")
    data class Rect(
        @SerialName("min_x") val minX: Int,
        @SerialName("max_x") val maxX: Int,
        @SerialName("min_y") val minY: Int,
        @SerialName("max_y") val maxY: Int,
    ) : ChartExtents {
        companion object {
            val EMPTY: Rect = Rect(minX = 0, maxX = -1, minY = 0, maxY = -1)
        }
    }

    @Serializable
    @SerialName("polar")
    data class Polar(
        val rings: Int,
        @SerialName("stitches_per_ring") val stitchesPerRing: List<Int>,
    ) : ChartExtents
}

@Serializable
data class ChartCell(
    @SerialName("symbol_id") val symbolId: String,
    val x: Int,
    val y: Int,
    val width: Int = 1,
    val height: Int = 1,
    val rotation: Int = 0,
    @SerialName("color_id") val colorId: String? = null,
    /**
     * Caller-supplied values for parametric symbols (e.g. "cast-on n stitches").
     * Keys must match [io.github.b150005.knitnote.domain.symbol.ParameterSlot.key] of the
     * resolved [io.github.b150005.knitnote.domain.symbol.SymbolDefinition]; unknown keys
     * are ignored by renderers.
     */
    @SerialName("symbol_parameters") val symbolParameters: Map<String, String> = emptyMap(),
)

@Serializable
data class ChartLayer(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val cells: List<ChartCell> = emptyList(),
)

@Serializable
data class StructuredChart(
    val id: String,
    @SerialName("pattern_id") val patternId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("storage_variant") val storageVariant: StorageVariant,
    @SerialName("coordinate_system") val coordinateSystem: CoordinateSystem,
    val extents: ChartExtents,
    val layers: List<ChartLayer>,
    @SerialName("revision_id") val revisionId: String,
    // Phase 36.2 audit (ADR-012 §2): no default value, so kotlinx-serialization
    // always emits this field on the wire regardless of `encodeDefaults`. That
    // structurally protects against the silent-overwrite-to-null footgun that
    // Phase 36.1 fixed on `Pattern.parentPatternId` via `@EncodeDefault(NEVER)`
    // — `Pattern` had `= null` as a backward-compat default for legacy clients,
    // `StructuredChart.parentRevisionId` has been a required field since
    // Phase 29 so legacy clients always carried it. Do NOT add a default here.
    //
    // Asymmetry note for the next reader: the neighboring `craftType` /
    // `readingConvention` fields below DO carry defaults. Those are deliberate
    // schema-v1 backward-compat anchors — under `encodeDefaults = false` (the
    // 1.11.0 runtime default used by `SyncModule.kt:34`'s Json instance), a v2
    // chart whose `craftType == KNIT` serializes WITHOUT the field, and a v1
    // remote row missing the field deserializes back to `KNIT` via the same
    // default. The round-trip is symmetric and intentional. Do NOT
    // `@EncodeDefault(NEVER)`-annotate those — that would change nothing for
    // them (they're already elided when matching the default) but would
    // entrench the asymmetry as if it were a footgun fix; it isn't.
    @SerialName("parent_revision_id") val parentRevisionId: String?,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
    // Phase 32.1 (schema v2): craft + reading metadata. Persisted inside the
    // document envelope. Defaults preserve behavior for any schema v1 row on
    // first read — the value is promoted to v2 on the next save.
    @SerialName("craft_type") val craftType: CraftType = CraftType.KNIT,
    @SerialName("reading_convention") val readingConvention: ReadingConvention = ReadingConvention.KNIT_FLAT,
) {
    companion object {
        /**
         * Document-envelope schema version.
         * - v1 (Phase 29): `extents` + `layers` only.
         * - v2 (Phase 32.1): adds `craft_type` + `reading_convention`. Backward
         *   compatible — v1 rows deserialize with defaults and are promoted to
         *   v2 on the next save.
         */
        const val CURRENT_SCHEMA_VERSION: Int = 2

        /**
         * Regex for valid symbol IDs. See ADR-008 §6.
         * Phase 30 extends the segment alphabet to accept hyphens so kebab-case
         * ids such as `jis.knit.k2tog-r` are valid alongside the underscore form.
         */
        private val SYMBOL_ID_REGEX = Regex("^[a-z]+(\\.[a-z0-9][a-z0-9_-]*)+$")

        fun isValidSymbolId(symbolId: String): Boolean = SYMBOL_ID_REGEX.matches(symbolId)

        /**
         * Compute a stable content hash for the draw-layer payload (extents + layers).
         * Phase 29 uses a simple deterministic hash adequate for idempotent sync and
         * drift detection. Phase 37 (Git-like collaboration) will replace this with a
         * cryptographic hash when collision resistance becomes load-bearing.
         */
        fun computeContentHash(
            extents: ChartExtents,
            layers: List<ChartLayer>,
            json: Json,
        ): String {
            val extentsJson = json.encodeToString(ChartExtents.serializer(), extents)
            val layersJson = json.encodeToString(ListSerializer(ChartLayer.serializer()), layers)
            val combined = "$extentsJson|$layersJson"
            val hash = combined.hashCode().toLong() and 0xFFFFFFFFL
            return "h1-" + hash.toString(16).padStart(8, '0')
        }
    }
}
