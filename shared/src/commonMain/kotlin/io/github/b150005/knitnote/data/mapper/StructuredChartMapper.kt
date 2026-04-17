package io.github.b150005.knitnote.data.mapper

import io.github.b150005.knitnote.db.StructuredChartEntity
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Internal JSON body carried inside the SQLite `document` column.
 * Contains only the document-scoped fields; row-level fields (id, ownerId, timestamps,
 * revisionId, etc.) live in their own columns to keep them queryable without jsonb.
 */
private object DocumentEnvelope {
    val extentsSerializer = ChartExtents.serializer()
    val layersSerializer = ListSerializer(ChartLayer.serializer())
}

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
            extents to layers
        }
    return StructuredChart(
        id = id,
        patternId = pattern_id,
        ownerId = owner_id,
        schemaVersion = schema_version.toInt(),
        storageVariant = storage_variant.toStorageVariant(),
        coordinateSystem = coordinate_system.toCoordinateSystem(),
        extents = envelope.first,
        layers = envelope.second,
        revisionId = revision_id,
        parentRevisionId = parent_revision_id,
        contentHash = content_hash,
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )
}

internal fun StructuredChart.toDocumentJson(json: Json): String {
    val obj =
        buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("extents", json.encodeToJsonElement(DocumentEnvelope.extentsSerializer, extents))
            put("layers", json.encodeToJsonElement(DocumentEnvelope.layersSerializer, layers))
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

private fun String.toStorageVariant(): StorageVariant =
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

private fun String.toCoordinateSystem(): CoordinateSystem =
    when (this) {
        "rect_grid" -> CoordinateSystem.RECT_GRID
        "polar_round" -> CoordinateSystem.POLAR_ROUND
        else -> error("Unknown CoordinateSystem in database: '$this'")
    }
