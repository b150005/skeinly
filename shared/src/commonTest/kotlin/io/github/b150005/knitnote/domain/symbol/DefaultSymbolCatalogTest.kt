package io.github.b150005.knitnote.domain.symbol

import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.symbol.catalog.DefaultSymbolCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultSymbolCatalogTest {
    private val catalog = DefaultSymbolCatalog.INSTANCE

    @Test
    fun `all bundled ids pass the symbol id regex`() {
        val invalid = catalog.all().filterNot { StructuredChart.isValidSymbolId(it.id) }
        assertTrue(invalid.isEmpty(), "invalid ids: ${invalid.map { it.id }}")
    }

    @Test
    fun `ids are unique`() {
        val ids = catalog.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate id in bundled catalog")
    }

    @Test
    fun `all bundled path data parses`() {
        for (def in catalog.all()) {
            val commands = SvgPathParser.parse(def.pathData)
            assertTrue(commands.isNotEmpty(), "${def.id} produced no commands")
        }
    }

    @Test
    fun `knit category contains the basic stitches`() {
        val ids = catalog.listByCategory(SymbolCategory.KNIT).map { it.id }

        assertTrue("jis.knit.k" in ids)
        assertTrue("jis.knit.p" in ids)
        assertTrue("jis.knit.yo" in ids)
        assertTrue("jis.knit.k2tog-r" in ids)
        assertTrue("jis.knit.k2tog-l" in ids)
    }

    @Test
    fun `get returns null for unknown id`() {
        assertNull(catalog.get("jis.knit.does-not-exist"))
    }

    @Test
    fun `contains matches get semantics`() {
        assertTrue(catalog.contains("jis.knit.k"))
        assertTrue(!catalog.contains("jis.knit.does-not-exist"))
    }

    @Test
    fun `cast-on exposes a count parameter slot`() {
        val castOn = assertNotNull(catalog.get("jis.knit.cast-on"))
        val slot = assertNotNull(castOn.parameterSlots.singleOrNull())

        assertEquals("count", slot.key)
        assertTrue(slot.x in 0.0..1.0)
        assertTrue(slot.y in 0.0..1.0)
    }

    @Test
    fun `bind-off exposes a count parameter slot`() {
        val bindOff = assertNotNull(catalog.get("jis.knit.bind-off"))
        assertTrue(bindOff.parameterSlots.any { it.key == "count" })
    }

    @Test
    fun `cables declare widthUnits matching their visual span`() {
        val cable2x2 = assertNotNull(catalog.get("jis.knit.cable-2x2-r"))
        val cable3x3 = assertNotNull(catalog.get("jis.knit.cable-3x3-r"))

        assertEquals(2, cable2x2.widthUnits)
        assertEquals(3, cable3x3.widthUnits)
    }

    @Test
    fun `catalog bundles the std cyc kfb overlay`() {
        val kfb = assertNotNull(catalog.get("std.cyc.kfb"))
        assertEquals(SymbolCategory.KNIT, kfb.category)
        assertEquals("kfb", kfb.cycName)
        assertNull(kfb.jisReference, "std.cyc.* entries must not claim a JIS reference")
    }

    @Test
    fun `legacy jis knit kfb id is no longer present`() {
        assertNull(catalog.get("jis.knit.kfb"))
    }

    @Test
    fun `twist-r exposes the nejiri-mashime alias`() {
        val twist = assertNotNull(catalog.get("jis.knit.twist-r"))
        assertTrue("ねじり増し目" in twist.aliases)
    }

    @Test
    fun `create rejects duplicate ids`() {
        val def =
            SymbolDefinition(
                id = "jis.knit.k",
                category = SymbolCategory.KNIT,
                pathData = "M 0 0 L 1 1",
                jaLabel = "dup",
                enLabel = "dup",
            )
        assertFailsWith<IllegalArgumentException> {
            DefaultSymbolCatalog.create(listOf(def, def))
        }
    }

    @Test
    fun `create rejects ids that fail the regex`() {
        val def =
            SymbolDefinition(
                id = "JIS.Bad",
                category = SymbolCategory.KNIT,
                pathData = "M 0 0 L 1 1",
                jaLabel = "x",
                enLabel = "x",
            )
        assertFailsWith<IllegalArgumentException> {
            DefaultSymbolCatalog.create(listOf(def))
        }
    }

    @Test
    fun `create rejects malformed path data`() {
        val def =
            SymbolDefinition(
                id = "jis.knit.bad-path",
                category = SymbolCategory.KNIT,
                pathData = "M 0 0 A 1 1 0 0 1 1 1",
                jaLabel = "x",
                enLabel = "x",
            )
        assertFailsWith<IllegalArgumentException> {
            DefaultSymbolCatalog.create(listOf(def))
        }
    }
}
