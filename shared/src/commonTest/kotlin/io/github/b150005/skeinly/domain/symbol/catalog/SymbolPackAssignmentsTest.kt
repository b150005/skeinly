package io.github.b150005.skeinly.domain.symbol.catalog

import io.github.b150005.skeinly.domain.model.SymbolPackTier
import io.github.b150005.skeinly.domain.symbol.SymbolCategory
import io.github.b150005.skeinly.domain.symbol.SymbolDefinition
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SymbolPackAssignmentsTest {
    private fun def(
        id: String,
        category: SymbolCategory,
    ): SymbolDefinition =
        SymbolDefinition(
            id = id,
            category = category,
            pathData = "M0,0 L1,0 L1,1 L0,1 Z",
            jaLabel = "test-ja-$id",
            enLabel = "test-en-$id",
        )

    @Test
    fun `KNIT category symbols flow into the knit beginner pack with FREE tier`() {
        val knitA = def("jis.knit.k", SymbolCategory.KNIT)
        val knitB = def("jis.knit.p", SymbolCategory.KNIT)

        val payloads = SymbolPackAssignments.assignToBeginnerPacks(listOf(knitA, knitB), version = 1)

        val knitPack = payloads.single { it.packId == SymbolPackAssignments.SymbolPackId.KNIT_BEGINNER }
        assertEquals(2, knitPack.symbols.size)
        assertTrue(knitPack.symbols.all { it.tier == SymbolPackTier.FREE })
        assertEquals(setOf("jis.knit.k", "jis.knit.p"), knitPack.symbols.map { it.id }.toSet())
    }

    @Test
    fun `CROCHET category symbols flow into the crochet beginner pack with FREE tier`() {
        val crochetSc = def("jis.crochet.sc", SymbolCategory.CROCHET)
        val crochetDc = def("jis.crochet.dc", SymbolCategory.CROCHET)

        val payloads = SymbolPackAssignments.assignToBeginnerPacks(listOf(crochetSc, crochetDc), version = 1)

        val crochetPack = payloads.single { it.packId == SymbolPackAssignments.SymbolPackId.CROCHET_BEGINNER }
        assertEquals(2, crochetPack.symbols.size)
        assertTrue(crochetPack.symbols.all { it.tier == SymbolPackTier.FREE })
        assertEquals(setOf("jis.crochet.sc", "jis.crochet.dc"), crochetPack.symbols.map { it.id }.toSet())
    }

    @Test
    fun `AFGHAN and MACHINE category symbols are silently dropped from beginner packs`() {
        val knit = def("jis.knit.k", SymbolCategory.KNIT)
        val afghan = def("jis.afghan.basic", SymbolCategory.AFGHAN)
        val machine = def("jis.machine.tuck", SymbolCategory.MACHINE)

        val payloads =
            SymbolPackAssignments.assignToBeginnerPacks(listOf(knit, afghan, machine), version = 1)

        // Only the KNIT pack ships — AFGHAN + MACHINE belong to packs not yet defined.
        assertEquals(1, payloads.size)
        assertEquals(SymbolPackAssignments.SymbolPackId.KNIT_BEGINNER, payloads.single().packId)
        assertNull(SymbolPackAssignments.beginnerPackIdForCategory(SymbolCategory.AFGHAN))
        assertNull(SymbolPackAssignments.beginnerPackIdForCategory(SymbolCategory.MACHINE))
    }

    @Test
    fun `payload symbols are sorted by id so byte-shape is deterministic across regenerations`() {
        val knitOutOfOrder =
            listOf(
                def("jis.knit.yo", SymbolCategory.KNIT),
                def("jis.knit.k", SymbolCategory.KNIT),
                def("jis.knit.p", SymbolCategory.KNIT),
            )

        val payloads = SymbolPackAssignments.assignToBeginnerPacks(knitOutOfOrder, version = 1)
        val knitPack = payloads.single()

        assertContentEquals(
            listOf("jis.knit.k", "jis.knit.p", "jis.knit.yo"),
            knitPack.symbols.map { it.id },
            "symbols must be sorted by id; expected lexicographic order",
        )
    }

    @Test
    fun `version is propagated to every emitted payload and rejected when non-positive`() {
        val knit = def("jis.knit.k", SymbolCategory.KNIT)
        val crochet = def("jis.crochet.sc", SymbolCategory.CROCHET)

        val payloads =
            SymbolPackAssignments.assignToBeginnerPacks(listOf(knit, crochet), version = 7)
        assertTrue(payloads.size == 2)
        assertTrue(payloads.all { it.version == 7 })
        assertTrue(payloads.all { it.schemaVersion == 1 })

        assertFailsWith<IllegalArgumentException> {
            SymbolPackAssignments.assignToBeginnerPacks(listOf(knit), version = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SymbolPackAssignments.assignToBeginnerPacks(listOf(knit), version = -1)
        }
    }

    @Test
    fun `empty input produces an empty pack list and well-formed pack ids match the regex`() {
        assertTrue(SymbolPackAssignments.assignToBeginnerPacks(emptyList(), version = 1).isEmpty())

        // Regression anchor — if a future refactor breaks the regex,
        // these constants would silently bypass the JSON file write.
        assertTrue(SymbolPackAssignments.SymbolPackId.isWellFormed(SymbolPackAssignments.SymbolPackId.KNIT_BEGINNER))
        assertTrue(SymbolPackAssignments.SymbolPackId.isWellFormed(SymbolPackAssignments.SymbolPackId.CROCHET_BEGINNER))
        // Negative cases.
        assertTrue(!SymbolPackAssignments.SymbolPackId.isWellFormed("missing-namespace-dot"))
        assertTrue(!SymbolPackAssignments.SymbolPackId.isWellFormed("UPPERCASE.bad"))
    }
}
