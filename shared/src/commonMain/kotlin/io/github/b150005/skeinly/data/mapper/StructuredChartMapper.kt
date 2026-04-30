package io.github.b150005.skeinly.data.mapper

import io.github.b150005.skeinly.db.StructuredChartEntity
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartLayer
import io.github.b150005.skeinly.domain.model.CoordinateSystem
import io.github.b150005.skeinly.domain.model.CraftType
import io.github.b150005.skeinly.domain.model.ReadingConvention
import io.github.b150005.skeinly.domain.model.StorageVariant
import io.github.b150005.skeinly.domain.model.StructuredChart
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.time.Instant

/**
 * Internal JSON body carried inside the SQLite `document` column.
 * Contains only the document-scoped fields; row-level fields (id, ownerId, timestamps,
 * revisionId, etc.) live in their own columns to keep them queryable without jsonb.
 *
 * Schema v2 (Phase 32.1) adds `craft_type` + `reading_convention`. Reads are
 * backward compatible — missing keys (and explicit JSON `null`s) fall back to
 * `CraftType.KNIT` / `ReadingConvention.KNIT_FLAT`.
 */
private object DocumentEnvelope {
    val extentsSerializer = ChartExtents.serializer()
    val layersSerializer = ListSerializer(ChartLayer.serializer())
    val craftTypeSerializer = CraftType.serializer()
    val readingConventionSerializer = ReadingConvention.serializer()
}

private data class DocumentEnvelopeValues(
    val extents: ChartExtents,
    val layers: List<ChartLayer>,
    val craftType: CraftType,
    val readingConvention: ReadingConvention,
)

/**
 * Decode an optional envelope field, returning [default] for both missing
 * keys AND explicit JSON `null` values. Bare `json.decodeFromJsonElement`
 * on a non-nullable serializer throws on `JsonNull` — this helper keeps
 * both absence shapes symmetric.
 */
private fun <T> JsonElement?.decodeOrDefault(
    json: Json,
    serializer: KSerializer<T>,
    default: T,
): T = if (this == null || this is JsonNull) default else json.decodeFromJsonElement(serializer, this)

internal fun StructuredChartEntity.toDomain(json: Json): StructuredChart {
    val envelope =
        json.parseToJsonElement(document).let { element ->
            val obj =
                element as? kotlinx.serialization.json.JsonObject
                    ?: error("StructuredChart.document is not a JSON object")
            val extentsElement = obj["extents"] ?: error("StructuredChart.document missing 'extents'")
            val layersElement = obj["layers"] ?: error("StructuredChart.document missing 'layers'")
            val extents = json.decodeFromJsonElement(DocumentEnvelope.extentsSerializer, extentsElement)
            val layers = json.decodeFromJsonElement(DocumentEnvelope.layersSerializer, layersElement)
            val craftType =
                obj["craft_type"].decodeOrDefault(
                    json,
                    DocumentEnvelope.craftTypeSerializer,
                    CraftType.KNIT,
                )
            val readingConvention =
                obj["reading_convention"].decodeOrDefault(
                    json,
                    DocumentEnvelope.readingConventionSerializer,
                    ReadingConvention.KNIT_FLAT,
                )
            DocumentEnvelopeValues(extents, layers, craftType, readingConvention)
        }
    return StructuredChart(
        id = id,
        patternId = pattern_id,
        ownerId = owner_id,
        schemaVersion = schema_version.toInt(),
        storageVariant = storage_variant.toStorageVariant(),
        coordinateSystem = coordinate_system.toCoordinateSystem(),
        extents = envelope.extents,
        layers = envelope.layers,
        revisionId = revision_id,
        parentRevisionId = parent_revision_id,
        contentHash = content_hash,
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
        craftType = envelope.craftType,
        readingConvention = envelope.readingConvention,
    )
}

internal fun StructuredChart.toDocumentJson(json: Json): String {
    val obj =
        buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("extents", json.encodeToJsonElement(DocumentEnvelope.extentsSerializer, extents))
            put("layers", json.encodeToJsonElement(DocumentEnvelope.layersSerializer, layers))
            put("craft_type", json.encodeToJsonElement(DocumentEnvelope.craftTypeSerializer, craftType))
            put(
                "reading_convention",
                json.encodeToJsonElement(DocumentEnvelope.readingConventionSerializer, readingConvention),
            )
        }
    return json.encodeToString(
        kotlinx.serialization.json.JsonObject
            .serializer(),
        kotlinx.serialization.json.JsonObject(obj),
    )
}

internal fun StorageVariant.toDbString(): String =
    when (this) {
        StorageVariant.INLINE -> "inline"
        StorageVariant.CHUNKED -> "chunked"
    }

internal fun String.toStorageVariant(): StorageVariant =
    when (this) {
        "inline" -> StorageVariant.INLINE
        "chunked" -> StorageVariant.CHUNKED
        else -> error("Unknown StorageVariant in database: '$this'")
    }

internal fun CoordinateSystem.toDbString(): String =
    when (this) {
        CoordinateSystem.RECT_GRID -> "rect_grid"
        CoordinateSystem.POLAR_ROUND -> "polar_round"
    }

internal fun String.toCoordinateSystem(): CoordinateSystem =
    when (this) {
        "rect_grid" -> CoordinateSystem.RECT_GRID
        "polar_round" -> CoordinateSystem.POLAR_ROUND
        else -> error("Unknown CoordinateSystem in database: '$this'")
    }
