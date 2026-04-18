package io.github.b150005.knitnote.domain.symbol.catalog

import io.github.b150005.knitnote.domain.symbol.SvgPathParser
import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrochetSymbolsTest {
    @Test
    fun `all entries live under the jis crochet namespace`() {
        for (def in CrochetSymbols.all) {
            assertTrue(
                def.id.startsWith("jis.crochet."),
                "CrochetSymbols entry must be namespaced under jis.crochet.*: ${def.id}",
            )
        }
    }

    @Test
    fun `all entries target the CROCHET category`() {
        for (def in CrochetSymbols.all) {
            assertEquals(
                SymbolCategory.CROCHET,
                def.category,
                "Crochet catalog must be CROCHET category: ${def.id}",
            )
        }
    }

    @Test
    fun `all entries declare a JIS reference to Table 2`() {
        for (def in CrochetSymbols.all) {
            val ref = assertNotNull(def.jisReference, "${def.id} must cite JIS L 0201 Table 2")
            assertTrue(
                ref.contains("L 0201"),
                "jisReference should reference JIS L 0201: '$ref' for ${def.id}",
            )
        }
    }

    @Test
    fun `all entries ship parseable path data`() {
        for (def in CrochetSymbols.all) {
            val commands = SvgPathParser.parse(def.pathData)
            assertTrue(commands.isNotEmpty(), "${def.id} produced no commands")
        }
    }

    @Test
    fun `all entries carry both JA and EN labels`() {
        for (def in CrochetSymbols.all) {
            assertTrue(def.jaLabel.isNotBlank(), "${def.id} missing jaLabel")
            assertTrue(def.enLabel.isNotBlank(), "${def.id} missing enLabel")
        }
    }

    @Test
    fun `basic stitches ch sl-st sc hdc dc tr dtr are present`() {
        val ids = CrochetSymbols.all.map { it.id }.toSet()
        val required =
            listOf(
                "jis.crochet.ch",
                "jis.crochet.sl-st",
                "jis.crochet.sc",
                "jis.crochet.hdc",
                "jis.crochet.dc",
                "jis.crochet.tr",
                "jis.crochet.dtr",
            )
        for (req in required) {
            assertTrue(req in ids, "Missing basic crochet glyph: $req")
        }
    }

    @Test
    fun `cluster glyph with widthUnits greater than 1 is declared`() {
        val cluster5 =
            assertNotNull(
                CrochetSymbols.all.find { it.id == "jis.crochet.dc-cluster-5" },
                "dc-cluster-5 must be present with widthUnits > 1",
            )
        assertTrue(
            cluster5.widthUnits > 1,
            "dc-cluster-5 widthUnits must span multiple columns; got ${cluster5.widthUnits}",
        )
    }

    @Test
    fun `ch-space exposes a count parameter slot`() {
        val chSpace =
            assertNotNull(
                CrochetSymbols.all.find { it.id == "jis.crochet.ch-space" },
                "ch-space must expose a count parameter",
            )
        assertTrue(
            chSpace.parameterSlots.any { it.key == "count" },
            "ch-space parameterSlots must contain 'count'",
        )
    }

    @Test
    fun `first-pass coverage includes at least 20 entries`() {
        assertTrue(
            CrochetSymbols.all.size >= 20,
            "Phase 30.2 target is 25-35 glyphs; first pass minimum 20. Got ${CrochetSymbols.all.size}",
        )
    }
}
