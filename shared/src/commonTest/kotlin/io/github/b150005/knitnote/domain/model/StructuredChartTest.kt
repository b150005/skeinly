package io.github.b150005.knitnote.domain.model

import io.github.b150005.knitnote.testJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StructuredChartTest {
    private val json = testJson
    private val now = Instant.parse("2026-04-17T10:00:00Z")

    private fun sampleCell(
        symbolId: String = "jis.k1",
        x: Int = 0,
        y: Int = 0,
    ) = ChartCell(symbolId = symbolId, x = x, y = y)

    private fun sampleLayer(cellCount: Int = 2): ChartLayer =
        ChartLayer(
            id = "layer-1",
            name = "Main",
            cells = (0 until cellCount).map { sampleCell(x = it, y = 0) },
        )

    private fun sampleChart(
        extents: ChartExtents = ChartExtents.Rect(minX = 0, maxX = 1, minY = 0, maxY = 0),
        coordinateSystem: CoordinateSystem = CoordinateSystem.RECT_GRID,
    ) = StructuredChart(
        id = "chart-1",
        patternId = "pat-1",
        ownerId = "user-1",
        schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
        storageVariant = StorageVariant.INLINE,
        coordinateSystem = coordinateSystem,
        extents = extents,
        layers = listOf(sampleLayer()),
        revisionId = "rev-1",
        parentRevisionId = null,
        contentHash = "hash-abc",
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `create chart with defaults on cell and layer`() {
        val cell = ChartCell(symbolId = "jis.k1", x = 3, y = 5)

        assertEquals(1, cell.width)
        assertEquals(1, cell.height)
        assertEquals(0, cell.rotation)
        assertNull(cell.colorId)

        val layer = ChartLayer(id = "l", name = "L")
        assertTrue(layer.visible)
        assertFalse(layer.locked)
        assertTrue(layer.cells.isEmpty())
    }

    @Test
    fun `copy produces a new instance without mutating`() {
        val chart = sampleChart()
        val updated = chart.copy(revisionId = "rev-2")

        assertEquals("rev-1", chart.revisionId)
        assertEquals("rev-2", updated.revisionId)
        assertNotEquals(chart, updated)
    }

    @Test
    fun `rect extents round-trip through json`() {
        val chart = sampleChart(extents = ChartExtents.Rect(0, 9, 0, 14))
        val encoded = json.encodeToString(StructuredChart.serializer(), chart)
        val decoded = json.decodeFromString(StructuredChart.serializer(), encoded)

        assertEquals(chart, decoded)
        assertTrue(decoded.extents is ChartExtents.Rect)
    }

    @Test
    fun `polar extents round-trip through json`() {
        val chart =
            sampleChart(
                coordinateSystem = CoordinateSystem.POLAR_ROUND,
                extents = ChartExtents.Polar(rings = 3, stitchesPerRing = listOf(6, 12, 18)),
            )
        val encoded = json.encodeToString(StructuredChart.serializer(), chart)
        val decoded = json.decodeFromString(StructuredChart.serializer(), encoded)

        assertEquals(chart, decoded)
        assertTrue(decoded.extents is ChartExtents.Polar)
    }

    @Test
    fun `coordinate system enum serializes to snake case`() {
        assertEquals(
            "rect_grid",
            json.encodeToString(CoordinateSystem.serializer(), CoordinateSystem.RECT_GRID).trim('"'),
        )
        assertEquals(
            "polar_round",
            json.encodeToString(CoordinateSystem.serializer(), CoordinateSystem.POLAR_ROUND).trim('"'),
        )
    }

    @Test
    fun `storage variant enum serializes to expected tokens`() {
        assertEquals(
            "inline",
            json.encodeToString(StorageVariant.serializer(), StorageVariant.INLINE).trim('"'),
        )
        assertEquals(
            "chunked",
            json.encodeToString(StorageVariant.serializer(), StorageVariant.CHUNKED).trim('"'),
        )
    }

    @Test
    fun `empty rect extents is a well formed range`() {
        val empty = ChartExtents.Rect.EMPTY

        assertEquals(0, empty.minX)
        assertEquals(-1, empty.maxX)
        assertTrue(empty.maxX < empty.minX, "empty rect has maxX < minX by convention")
    }

    @Test
    fun `symbol id validator accepts namespaced ids`() {
        assertTrue(StructuredChart.isValidSymbolId("jis.k1"))
        assertTrue(StructuredChart.isValidSymbolId("std.yo"))
        assertTrue(StructuredChart.isValidSymbolId("user.abc123.cable_6f"))
        assertTrue(StructuredChart.isValidSymbolId("ext.custom.shape"))
    }

    @Test
    fun `symbol id validator rejects malformed ids`() {
        assertFalse(StructuredChart.isValidSymbolId(""))
        assertFalse(StructuredChart.isValidSymbolId("jis"))
        assertFalse(StructuredChart.isValidSymbolId("JIS.k1"))
        assertFalse(StructuredChart.isValidSymbolId("jis..k1"))
        assertFalse(StructuredChart.isValidSymbolId(".jis.k1"))
        assertFalse(StructuredChart.isValidSymbolId("jis.k1."))
        assertFalse(StructuredChart.isValidSymbolId("jis.k 1"))
        assertFalse(StructuredChart.isValidSymbolId("jis-k1"))
    }

    @Test
    fun `layer cells preserve list order through copy`() {
        val layer = sampleLayer(cellCount = 3)
        val modified =
            layer.copy(
                cells = layer.cells + sampleCell(symbolId = "jis.p1", x = 3, y = 0),
            )

        assertEquals(3, layer.cells.size)
        assertEquals(4, modified.cells.size)
        assertEquals("jis.p1", modified.cells[3].symbolId)
    }

    @Test
    fun `serialized chart carries snake case keys for multi word fields`() {
        val chart = sampleChart()
        val encoded = json.encodeToString(StructuredChart.serializer(), chart)

        assertTrue(encoded.contains("\"pattern_id\""))
        assertTrue(encoded.contains("\"owner_id\""))
        assertTrue(encoded.contains("\"schema_version\""))
        assertTrue(encoded.contains("\"storage_variant\""))
        assertTrue(encoded.contains("\"coordinate_system\""))
        assertTrue(encoded.contains("\"revision_id\""))
        assertTrue(encoded.contains("\"content_hash\""))
    }
}
