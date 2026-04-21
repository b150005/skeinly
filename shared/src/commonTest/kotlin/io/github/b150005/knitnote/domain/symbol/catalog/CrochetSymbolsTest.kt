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
    fun `entries cite JIS L 0201 Table 2 unless JIS is silent on the glyph`() {
        // Phase 30.4 — `solomon-knot` and `bullion` are publisher-convention
        // only (Vogue / Bunka / Interweave). JIS L 0201 Table 2 does not
        // enumerate them, so their jisReference must stay null per the
        // knitter advisory — see `jis_silent_glyphs_leave_jisReference_null`
        // below. Every other `jis.crochet.*` entry still cites the JIS
        // reference.
        val jisSilent = setOf("jis.crochet.solomon-knot", "jis.crochet.bullion")
        for (def in CrochetSymbols.all) {
            if (def.id in jisSilent) {
                assertEquals(
                    null,
                    def.jisReference,
                    "${def.id} is JIS-silent; jisReference must stay null",
                )
                continue
            }
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

    @Test
    fun `phase 30_3 top-3 glyphs are present`() {
        // Phase 30.3 — close the commercial-JP crochet coverage gap flagged by
        // the Knitter agent in phase-30.2.md §4. Adding reverse-sc (crab
        // stitch, ubiquitous amigurumi edging), puff (パフ編み, no honest
        // substitute via existing glyphs), and hdc-cluster-3 (中長編み3目の
        // 玉編み) brings the catalog to ~90% coverage of commonly-seen JP
        // crochet patterns.
        val ids = CrochetSymbols.all.map { it.id }.toSet()
        val required =
            listOf(
                "jis.crochet.reverse-sc",
                "jis.crochet.puff",
                "jis.crochet.hdc-cluster-3",
            )
        for (req in required) {
            assertTrue(req in ids, "Phase 30.3 must include: $req")
        }
    }

    @Test
    fun `reverse-sc exposes crab stitch alias for cross-convention lookup`() {
        // EN pattern books use "crab stitch" as the common name. A dictionary
        // search for "crab stitch" must still resolve to jis.crochet.reverse-sc.
        val reverseSc =
            assertNotNull(
                CrochetSymbols.all.find { it.id == "jis.crochet.reverse-sc" },
                "reverse-sc must be present in the crochet catalog",
            )
        assertTrue(
            "crab stitch" in reverseSc.aliases,
            "reverse-sc aliases must include 'crab stitch' for EN lookup",
        )
    }

    @Test
    fun `hdc-cluster-3 declares no parameter slots and single-cell width`() {
        // Mirrors dc-cluster-3's geometry contract: 1×1 cell, no parametric
        // inputs. Accidentally marking it multi-cell would silently break the
        // editor palette layout.
        val hdcCluster =
            assertNotNull(
                CrochetSymbols.all.find { it.id == "jis.crochet.hdc-cluster-3" },
                "hdc-cluster-3 must be present in the crochet catalog",
            )
        assertTrue(hdcCluster.widthUnits == 1, "hdc-cluster-3 should be single-cell")
        assertTrue(hdcCluster.parameterSlots.isEmpty(), "hdc-cluster-3 takes no parameters")
    }

    @Test
    fun `sl-st is rendered with fill per JIS publisher convention`() {
        // Phase 30.2-fix: JIS L 0201 Table 2 + every JP publisher render
        // 引き抜き編み as a solid filled dot. Stroke-rendering reads as a donut
        // and JP knitters won't recognise it. fill must stay true on this glyph.
        val slSt =
            assertNotNull(
                CrochetSymbols.all.find { it.id == "jis.crochet.sl-st" },
                "sl-st must be present in the crochet catalog",
            )
        assertTrue(slSt.fill, "jis.crochet.sl-st must set fill = true")
    }

    @Test
    fun `non-fill crochet glyphs default to stroked rendering`() {
        // Regression guard: Phase 30.2-fix introduced fill as an opt-in field.
        // Every glyph other than sl-st (and any future filled variants) must
        // remain stroked — flipping a glyph to fill silently would change the
        // rendered chart geometry across both platforms.
        val filledIds = setOf("jis.crochet.sl-st")
        for (def in CrochetSymbols.all) {
            if (def.id in filledIds) continue
            assertTrue(
                !def.fill,
                "${def.id} unexpectedly has fill = true; update the test allowlist if intentional",
            )
        }
    }

    @Test
    fun `phase 30_4 bundle glyphs are present`() {
        // Phase 30.4 — opportunistic top-ups from phase-30.3 §4: hdc-cluster-5,
        // crossed 2-dc (aran-style), bullion (doily), Solomon's knot (open-work
        // shawl), plus picot-4/5/6 as a discrete family per ADR-009 §8.
        val ids = CrochetSymbols.all.map { it.id }.toSet()
        val required =
            listOf(
                "jis.crochet.hdc-cluster-5",
                "jis.crochet.dc-crossed-2",
                "jis.crochet.bullion",
                "jis.crochet.solomon-knot",
                "jis.crochet.picot-4",
                "jis.crochet.picot-5",
                "jis.crochet.picot-6",
            )
        for (req in required) {
            assertTrue(req in ids, "Phase 30.4 must include: $req")
        }
    }

    @Test
    fun `picot family is discrete not parametric per ADR-009 section 8`() {
        // ADR-009 §8 — picot-N geometry varies with N (chart convention is
        // loop-size, not rendered digit) → discrete family. Each member is
        // its own SymbolDefinition with no parameterSlots. Regression guard
        // against a future well-meaning edit trying to collapse the family
        // into a single parametric entry.
        val picotIds = setOf("jis.crochet.picot-3", "jis.crochet.picot-4", "jis.crochet.picot-5", "jis.crochet.picot-6")
        for (def in CrochetSymbols.all) {
            if (def.id in picotIds) {
                assertTrue(
                    def.parameterSlots.isEmpty(),
                    "${def.id} is a discrete family member; must not declare parameterSlots (ADR-009 §8)",
                )
            }
        }
    }

    @Test
    fun `picot-3 carries bare picot alias per ADR-009 section 4`() {
        // ADR-009 §4 — the shortest family member carries the bare stem in
        // aliases so dictionary search on "picot" resolves to this entry.
        val picot3 =
            assertNotNull(
                CrochetSymbols.all.find { it.id == "jis.crochet.picot-3" },
                "picot-3 must be present",
            )
        assertTrue(
            "picot" in picot3.aliases,
            "picot-3 aliases must include the bare 'picot' stem (ADR-009 §4)",
        )
    }

    @Test
    fun `picot-6 spans two columns per ADR-009 section 8 clause`() {
        // ADR-009 §8 clause — picot-6 loop overflows a single cell, so
        // widthUnits=2 is the honest rendering. Regression guard against
        // silently dropping to widthUnits=1 which would squash the arch.
        val picot6 =
            assertNotNull(
                CrochetSymbols.all.find { it.id == "jis.crochet.picot-6" },
                "picot-6 must be present",
            )
        assertEquals(2, picot6.widthUnits, "picot-6 widthUnits must be 2 per ADR-009 §8")
    }

    @Test
    fun `hdc-cluster-5 mirrors dc-cluster-5 width`() {
        // Mirrors dc-cluster-5's widthUnits=2 contract. Accidentally marking
        // it single-cell would squash the 5-stem bundle into one column.
        val hdcCluster5 =
            assertNotNull(
                CrochetSymbols.all.find { it.id == "jis.crochet.hdc-cluster-5" },
                "hdc-cluster-5 must be present",
            )
        assertEquals(2, hdcCluster5.widthUnits, "hdc-cluster-5 must span 2 columns like dc-cluster-5")
    }

    @Test
    fun `dc-crossed-2 spans two columns`() {
        // Crossed 2-dc is a two-stitch stitch — widthUnits=2 is semantic,
        // not just visual. Keep this test to prevent column-arithmetic bugs
        // in future editor placement logic.
        val crossed =
            assertNotNull(
                CrochetSymbols.all.find { it.id == "jis.crochet.dc-crossed-2" },
                "dc-crossed-2 must be present",
            )
        assertEquals(2, crossed.widthUnits, "dc-crossed-2 must span 2 columns (two-stitch stitch)")
    }

    @Test
    fun `solomon-knot and bullion leave jisReference null because JIS is silent`() {
        // JIS L 0201 Table 2 does not enumerate these glyphs — they are
        // publisher-convention only. Setting a jisReference would mislead
        // readers into thinking JIS authority covers the rendering. Regression
        // guard against a well-meaning "fill in the reference" edit.
        val silentIds = listOf("jis.crochet.solomon-knot", "jis.crochet.bullion")
        for (silentId in silentIds) {
            val def =
                assertNotNull(
                    CrochetSymbols.all.find { it.id == silentId },
                    "$silentId must be present",
                )
            assertEquals(
                null,
                def.jisReference,
                "$silentId must set jisReference = null; JIS is silent on this stitch",
            )
        }
    }

    @Test
    fun `hdc-cluster-3 stem spacing widened to disambiguate parallel stems`() {
        // Phase 30.4 — without slashes the three hdc-cluster-3 stems can read
        // as one thick line on low-DPI Android at 24dp. Outer stems moved
        // from 0.25/0.75 → 0.22/0.78 for a 6% wider inter-stem gap. Regression
        // guard so a future "tidy the numbers" edit does not re-collapse them.
        val hdcCluster3 =
            assertNotNull(
                CrochetSymbols.all.find { it.id == "jis.crochet.hdc-cluster-3" },
                "hdc-cluster-3 must be present",
            )
        assertTrue(
            hdcCluster3.pathData.contains("0.22") && hdcCluster3.pathData.contains("0.78"),
            "hdc-cluster-3 must use widened 0.22/0.78 outer stem positions (Phase 30.4)",
        )
    }
}
