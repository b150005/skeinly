package io.github.b150005.knitnote.domain.symbol.catalog

import io.github.b150005.knitnote.domain.symbol.SvgPathParser
import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CycSymbolsTest {
    @Test
    fun `all entries live under the std cyc namespace`() {
        for (def in CycSymbols.all) {
            assertTrue(
                def.id.startsWith("std.cyc."),
                "CycSymbols entry must be namespaced under std.cyc.*: ${def.id}",
            )
        }
    }

    @Test
    fun `all entries target the KNIT category for Phase 30`() {
        for (def in CycSymbols.all) {
            assertEquals(
                SymbolCategory.KNIT,
                def.category,
                "Phase 30 CYC overlays are knit-only: ${def.id}",
            )
        }
    }

    @Test
    fun `all entries omit a JIS reference`() {
        for (def in CycSymbols.all) {
            assertNull(def.jisReference, "std.cyc.* must not claim a JIS reference: ${def.id}")
        }
    }

    @Test
    fun `all entries ship parseable path data`() {
        for (def in CycSymbols.all) {
            val commands = SvgPathParser.parse(def.pathData)
            assertTrue(commands.isNotEmpty(), "${def.id} produced no commands")
        }
    }

    @Test
    fun `kfb is present and labelled for the gallery`() {
        val kfb = assertNotNull(CycSymbols.all.find { it.id == "std.cyc.kfb" })
        assertEquals("kfb", kfb.cycName)
        assertTrue(kfb.jaLabel.isNotBlank())
        assertTrue(kfb.enLabel.isNotBlank())
    }
}
