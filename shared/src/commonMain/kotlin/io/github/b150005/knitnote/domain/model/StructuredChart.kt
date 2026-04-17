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
    @SerialName("parent_revision_id") val parentRevisionId: String?,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

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
