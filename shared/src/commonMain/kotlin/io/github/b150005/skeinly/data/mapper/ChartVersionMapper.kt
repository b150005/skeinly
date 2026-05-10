package io.github.b150005.skeinly.data.mapper

import io.github.b150005.skeinly.db.ChartRevisionEntity
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartLayer
import io.github.b150005.skeinly.domain.model.ChartRevision
import io.github.b150005.skeinly.domain.model.CraftType
import io.github.b150005.skeinly.domain.model.ReadingConvention
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Document envelope shared with [StructuredChartMapper] — same shape lives
 * inside `chart_revisions.document` jsonb. Kept private to this file so the
 * two mappers can evolve independently if envelope semantics ever diverge
 * (none today; ADR-013 §3 preserves the shape on purpose).
 */
private object RevisionEnvelope {
    val extentsSerializer = ChartExtents.serializer()
    val layersSerializer = ListSerializer(ChartLayer.serializer())
    val craftTypeSerializer = CraftType.serializer()
    val readingConventionSerializer = ReadingConvention.serializer()
}

private data class RevisionEnvelopeValues(
    val extents: ChartExtents,
    val layers: List<ChartLayer>,
    val craftType: CraftType,
    val readingConvention: ReadingConvention,
)

private fun <T> JsonElement?.decodeOrDefault(
    json: Json,
    serializer: KSerializer<T>,
    default: T,
): T = if (this == null || this is JsonNull) default else json.decodeFromJsonElement(serializer, this)

internal fun ChartRevisionEntity.toDomain(json: Json): ChartRevision {
    val envelope =
        json.parseToJsonElement(document).let { element ->
            val obj =
                element as? JsonObject
                    ?: error("ChartRevision.document is not a JSON object")
            val extentsElement = obj["extents"] ?: error("ChartRevision.document missing 'extents'")
            val layersElement = obj["layers"] ?: error("ChartRevision.document missing 'layers'")
            val extents = json.decodeFromJsonElement(RevisionEnvelope.extentsSerializer, extentsElement)
            val layers = json.decodeFromJsonElement(RevisionEnvelope.layersSerializer, layersElement)
            val craftType =
                obj["craft_type"].decodeOrDefault(
                    json,
                    RevisionEnvelope.craftTypeSerializer,
                    CraftType.KNIT,
                )
            val readingConvention =
                obj["reading_convention"].decodeOrDefault(
                    json,
                    RevisionEnvelope.readingConventionSerializer,
                    ReadingConvention.KNIT_FLAT,
                )
            RevisionEnvelopeValues(extents, layers, craftType, readingConvention)
        }
    return ChartRevision(
        id = id,
        patternId = pattern_id,
        ownerId = owner_id,
        authorId = author_id,
        schemaVersion = schema_version.toInt(),
        storageVariant = storage_variant.toStorageVariant(),
        coordinateSystem = coordinate_system.toCoordinateSystem(),
        extents = envelope.extents,
        layers = envelope.layers,
        revisionId = revision_id,
        parentRevisionId = parent_revision_id,
        contentHash = content_hash,
        commitMessage = commit_message,
        createdAt = Instant.parse(created_at),
        craftType = envelope.craftType,
        readingConvention = envelope.readingConvention,
    )
}

internal fun ChartRevision.toDocumentJson(json: Json): String {
    val obj =
        buildMap<String, JsonElement> {
            put("extents", json.encodeToJsonElement(RevisionEnvelope.extentsSerializer, extents))
            put("layers", json.encodeToJsonElement(RevisionEnvelope.layersSerializer, layers))
            put("craft_type", json.encodeToJsonElement(RevisionEnvelope.craftTypeSerializer, craftType))
            put(
                "reading_convention",
                json.encodeToJsonElement(RevisionEnvelope.readingConventionSerializer, readingConvention),
            )
        }
    return json.encodeToString(JsonObject.serializer(), JsonObject(obj))
}
