package io.github.b150005.knitnote.domain.model

import io.github.b150005.knitnote.testJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChartCellParametersTest {
    private val json = testJson

    @Test
    fun `default symbolParameters is empty`() {
        val cell = ChartCell(symbolId = "jis.knit.k", x = 0, y = 0)
        assertTrue(cell.symbolParameters.isEmpty())
    }

    @Test
    fun `symbolParameters round-trip through json`() {
        val cell =
            ChartCell(
                symbolId = "jis.knit.cast-on",
                x = 1,
                y = 2,
                symbolParameters = mapOf("count" to "12"),
            )
        val encoded = json.encodeToString(ChartCell.serializer(), cell)
        val decoded = json.decodeFromString(ChartCell.serializer(), encoded)

        assertEquals(cell, decoded)
        assertEquals("12", decoded.symbolParameters["count"])
        assertTrue(encoded.contains("\"symbol_parameters\""))
    }

    @Test
    fun `legacy cell without symbolParameters deserializes with empty map`() {
        val legacy =
            """{"symbol_id":"jis.knit.k","x":0,"y":0}""".trimIndent()

        val cell = json.decodeFromString(ChartCell.serializer(), legacy)
        assertTrue(cell.symbolParameters.isEmpty())
    }

    @Test
    fun `kebab case ids pass validator`() {
        assertTrue(StructuredChart.isValidSymbolId("jis.knit.k2tog-r"))
        assertTrue(StructuredChart.isValidSymbolId("jis.knit.cable-1x1-r-p"))
        assertTrue(StructuredChart.isValidSymbolId("jis.knit.cast-on"))
    }

    @Test
    fun `segment starting with hyphen is rejected`() {
        assertFalse(StructuredChart.isValidSymbolId("jis.knit.-bad"))
        assertFalse(StructuredChart.isValidSymbolId("jis.-knit.k"))
    }
}
