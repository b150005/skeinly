package io.github.b150005.skeinly.data.mapper

import io.github.b150005.skeinly.db.ChartVersionEntity
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartLayer
import io.github.b150005.skeinly.domain.model.ChartVersion
import io.github.b150005.skeinly.domain.model.CraftType
import io.github.b150005.skeinly.domain.model.ReadingConvention
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Document envelope shared with [ChartMapper] — same shape lives
 * inside `chart_versions.document` jsonb. Kept private to this file so the
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

internal fun ChartVersionEntity.toDomain(json: Json): ChartVersion {
    val envelope =
        json.parseToJsonElement(document).let { element ->
            val obj =
                element as? JsonObject
                    ?: error("ChartVersion.document is not a JSON object")
            val extentsElement = obj["extents"] ?: error("ChartVersion.document missing 'extents'")
            val layersElement = obj["layers"] ?: error("ChartVersion.document missing 'layers'")
            val craftTypeElement =
                obj["craft_type"] ?: error("ChartVersion.document missing 'craft_type'")
            val readingConventionElement =
                obj["reading_convention"] ?: error("ChartVersion.document missing 'reading_convention'")
            val extents = json.decodeFromJsonElement(RevisionEnvelope.extentsSerializer, extentsElement)
            val layers = json.decodeFromJsonElement(RevisionEnvelope.layersSerializer, layersElement)
            val craftType =
                json.decodeFromJsonElement(RevisionEnvelope.craftTypeSerializer, craftTypeElement)
            val readingConvention =
                json.decodeFromJsonElement(RevisionEnvelope.readingConventionSerializer, readingConventionElement)
            RevisionEnvelopeValues(extents, layers, craftType, readingConvention)
        }
    return ChartVersion(
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

internal fun ChartVersion.toDocumentJson(json: Json): String {
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
