package io.github.b150005.knitnote.data.mapper

import io.github.b150005.knitnote.db.StructuredChartEntity
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.CraftType
import io.github.b150005.knitnote.domain.model.ReadingConvention
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.testJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the document envelope written to and read from the SQLite
 * `document` text column (see [StructuredChartMapper]). Phase 32.1 adds
 * `craft_type` + `reading_convention` to the envelope; these tests lock in
 * both forward serialization and backward deserialization of v1 payloads.
 */
class StructuredChartMapperTest {
    private val json = testJson
    private val nowIso = "2026-04-17T10:00:00Z"

    private fun entity(document: String): StructuredChartEntity =
        StructuredChartEntity(
            id = "chart-1",
            pattern_id = "pat-1",
            owner_id = "user-1",
            schema_version = 1L,
            storage_variant = "inline",
            coordinate_system = "rect_grid",
            document = document,
            revision_id = "rev-1",
            parent_revision_id = null,
            content_hash = "h1-00000000",
            created_at = nowIso,
            updated_at = nowIso,
        )

    private fun sampleChart(
        craftType: CraftType = CraftType.KNIT,
        readingConvention: ReadingConvention = ReadingConvention.KNIT_FLAT,
    ) = StructuredChart(
        id = "chart-1",
        patternId = "pat-1",
        ownerId = "user-1",
        schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
        storageVariant = StorageVariant.INLINE,
        coordinateSystem = CoordinateSystem.RECT_GRID,
        extents = ChartExtents.Rect(0, 1, 0, 0),
        layers = listOf(ChartLayer(id = "l", name = "L")),
        revisionId = "rev-1",
        parentRevisionId = null,
        contentHash = "h1-00000000",
        createdAt = kotlin.time.Instant.parse(nowIso),
        updatedAt = kotlin.time.Instant.parse(nowIso),
        craftType = craftType,
        readingConvention = readingConvention,
    )

    @Test
    fun `v1 envelope without craft_type decodes to KNIT and KNIT_FLAT`() {
        val v1Document =
            """
            {
              "extents": { "type": "rect", "min_x": 0, "max_x": 1, "min_y": 0, "max_y": 0 },
              "layers": []
            }
            """.trimIndent()
        val domain = entity(v1Document).toDomain(json)

        assertEquals(CraftType.KNIT, domain.craftType)
        assertEquals(ReadingConvention.KNIT_FLAT, domain.readingConvention)
    }

    @Test
    fun `v2 envelope preserves explicit craft_type and reading_convention`() {
        val v2Document =
            """
            {
              "extents": { "type": "rect", "min_x": 0, "max_x": 1, "min_y": 0, "max_y": 0 },
              "layers": [],
              "craft_type": "crochet",
              "reading_convention": "round"
            }
            """.trimIndent()
        val domain = entity(v2Document).toDomain(json)

        assertEquals(CraftType.CROCHET, domain.craftType)
        assertEquals(ReadingConvention.ROUND, domain.readingConvention)
    }

    @Test
    fun `encoded document contains snake_case craft and reading keys`() {
        val chart =
            sampleChart(
                craftType = CraftType.CROCHET,
                readingConvention = ReadingConvention.CROCHET_FLAT,
            )
        val encoded = chart.toDocumentJson(json)

        assertTrue(encoded.contains("\"craft_type\":\"crochet\""), "expected craft_type in envelope: $encoded")
        assertTrue(
            encoded.contains("\"reading_convention\":\"crochet_flat\""),
            "expected reading_convention in envelope: $encoded",
        )
    }

    @Test
    fun `encode then decode round-trips craft and reading through envelope`() {
        val original =
            sampleChart(
                craftType = CraftType.CROCHET,
                readingConvention = ReadingConvention.ROUND,
            )
        val documentJson = original.toDocumentJson(json)
        val restored = entity(documentJson).toDomain(json)

        assertEquals(CraftType.CROCHET, restored.craftType)
        assertEquals(ReadingConvention.ROUND, restored.readingConvention)
    }

    @Test
    fun `envelope with explicit JSON null values falls back to defaults`() {
        val envelopeWithNulls =
            """
            {
              "extents": { "type": "rect", "min_x": 0, "max_x": 1, "min_y": 0, "max_y": 0 },
              "layers": [],
              "craft_type": null,
              "reading_convention": null
            }
            """.trimIndent()
        val domain = entity(envelopeWithNulls).toDomain(json)

        // Explicit JSON null must not throw — it should collapse to the defaults,
        // matching the behavior of a missing key.
        assertEquals(CraftType.KNIT, domain.craftType)
        assertEquals(ReadingConvention.KNIT_FLAT, domain.readingConvention)
    }

    @Test
    fun `StructuredChart serializer round-trip preserves non-default craft and reading`() {
        // Mirrors the sync payload path used by StructuredChartRepositoryImpl, which
        // calls `json.encodeToString(chart)` on the full domain instance.
        val original =
            sampleChart(
                craftType = CraftType.CROCHET,
                readingConvention = ReadingConvention.ROUND,
            )
        val encoded = json.encodeToString(StructuredChart.serializer(), original)
        val decoded = json.decodeFromString(StructuredChart.serializer(), encoded)

        assertEquals(CraftType.CROCHET, decoded.craftType)
        assertEquals(ReadingConvention.ROUND, decoded.readingConvention)
    }
}
