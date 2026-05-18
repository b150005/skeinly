package io.github.b150005.skeinly.domain.chart

import io.github.b150005.skeinly.domain.chart.ChartAccessibility.A11yStrings
import io.github.b150005.skeinly.domain.chart.ChartAccessibility.CellA11yStrings
import io.github.b150005.skeinly.domain.chart.ChartAccessibility.RowProgress
import io.github.b150005.skeinly.domain.chart.ChartAccessibility.SymbolRun
import io.github.b150005.skeinly.domain.model.ChartCell
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartLayer
import io.github.b150005.skeinly.domain.model.SegmentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhaustive spec for the pure shared chart-accessibility model (ADR-025
 * R1a). Mirrors the GridHitTest / WcagTargetSize test style: boundary-first,
 * no platform deps. Locks down row indexing, run-length boundaries,
 * progress-state mapping (kept in lockstep with MarkRowSegmentsDoneUseCase's
 * visible/hidden cell-set predicate), empty/degenerate extents, off-by-one at
 * row 1 / row N, and the parity-by-construction `spokenLabel` formatter.
 */
class ChartAccessibilityTest {
    private fun layer(
        id: String,
        cells: List<ChartCell>,
        visible: Boolean = true,
        locked: Boolean = false,
    ) = ChartLayer(id = id, name = id, visible = visible, locked = locked, cells = cells)

    private fun cell(
        symbolId: String,
        x: Int,
        y: Int,
    ) = ChartCell(symbolId = symbolId, x = x, y = y)

    private val strings =
        A11yStrings(
            rowPositionFormat = "Row %1\$d of %2\$d",
            symbolRunFormat = "%1\$s x%2\$d",
            blankCellsName = "blank",
            runSeparator = ", ",
            sectionSeparator = " - ",
            progressNotStarted = "not started",
            progressDone = "done",
            progressInProgressFormat = "%1\$d of %2\$d done",
        )

    // ---- Row indexing + ordering ----

    @Test
    fun `descriptors are ordered row 1 bottom to row N top with chartY mapping`() {
        // 1-wide, 3-tall grid, y origin offset by 5 to exercise non-zero minY.
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 5, maxY = 7)
        val layers =
            listOf(
                layer(
                    "L1",
                    listOf(cell("a", 0, 5), cell("b", 0, 6), cell("c", 0, 7)),
                ),
            )

        val descriptors = ChartAccessibility.rowDescriptors(extents, layers)

        assertEquals(3, descriptors.size)
        // Row 1 = bottom = chart y = minY (5).
        assertEquals(1, descriptors[0].rowNumber)
        assertEquals(3, descriptors[0].rowCount)
        assertEquals(5, descriptors[0].chartY)
        assertEquals(listOf(SymbolRun("a", 1)), descriptors[0].runs)
        // Row 2 = chart y 6.
        assertEquals(2, descriptors[1].rowNumber)
        assertEquals(6, descriptors[1].chartY)
        assertEquals(listOf(SymbolRun("b", 1)), descriptors[1].runs)
        // Row N (3) = top = chart y = maxY (7).
        assertEquals(3, descriptors[2].rowNumber)
        assertEquals(7, descriptors[2].chartY)
        assertEquals(listOf(SymbolRun("c", 1)), descriptors[2].runs)
    }

    @Test
    fun `off-by-one row 1 and row N never collide and cover the whole grid`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 9)
        val layers = listOf(layer("L1", (0..9).map { cell("s$it", 0, it) }))

        val d = ChartAccessibility.rowDescriptors(extents, layers)

        assertEquals(10, d.size)
        assertEquals(1, d.first().rowNumber)
        assertEquals(0, d.first().chartY)
        assertEquals(10, d.last().rowNumber)
        assertEquals(9, d.last().chartY)
        // Every rowNumber 1..10 present exactly once; every chartY 0..9 once.
        assertEquals((1..10).toList(), d.map { it.rowNumber })
        assertEquals((0..9).toList(), d.map { it.chartY })
        assertTrue(d.all { it.rowCount == 10 })
    }

    // ---- Run-length boundaries ----

    @Test
    fun `runs collapse consecutive identical symbols and split at boundaries`() {
        // Row y=0, x 0..6: a a a b b c (gap) -> a(3) b(2) c(1) blank(1)
        val extents = ChartExtents.Rect(minX = 0, maxX = 6, minY = 0, maxY = 0)
        val layers =
            listOf(
                layer(
                    "L1",
                    listOf(
                        cell("a", 0, 0),
                        cell("a", 1, 0),
                        cell("a", 2, 0),
                        cell("b", 3, 0),
                        cell("b", 4, 0),
                        cell("c", 5, 0),
                        // x=6 intentionally empty
                    ),
                ),
            )

        val runs = ChartAccessibility.rowDescriptors(extents, layers).single().runs

        assertEquals(
            listOf(
                SymbolRun("a", 3),
                SymbolRun("b", 2),
                SymbolRun("c", 1),
                SymbolRun(null, 1),
            ),
            runs,
        )
    }

    @Test
    fun `a fully empty row is one blank run spanning the grid width`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 4, minY = 0, maxY = 0)
        val runs = ChartAccessibility.rowDescriptors(extents, emptyList()).single().runs
        assertEquals(listOf(SymbolRun(null, 5)), runs)
    }

    @Test
    fun `topmost visible layer wins per column mirroring topmostLayerAt`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 1, minY = 0, maxY = 0)
        val layers =
            listOf(
                layer("base", listOf(cell("under", 0, 0), cell("under", 1, 0))),
                // top layer only covers x=1 -> x=0 falls through to base.
                layer("top", listOf(cell("over", 1, 0))),
            )

        val runs = ChartAccessibility.rowDescriptors(extents, layers).single().runs

        assertEquals(listOf(SymbolRun("under", 1), SymbolRun("over", 1)), runs)
    }

    @Test
    fun `hidden and invisible layers are excluded from the run projection`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 0)
        val layers =
            listOf(
                layer("base", listOf(cell("base-sym", 0, 0))),
                layer("invisible", listOf(cell("inv", 0, 0)), visible = false),
                layer("hidden", listOf(cell("hid", 0, 0))),
            )

        val runs =
            ChartAccessibility
                .rowDescriptors(extents, layers, hiddenLayerIds = setOf("hidden"))
                .single()
                .runs

        // Neither the invisible nor the UI-hidden layer's cell wins; base shows.
        assertEquals(listOf(SymbolRun("base-sym", 1)), runs)
    }

    @Test
    fun `locked layers still contribute to the run projection`() {
        // MarkRowSegmentsDoneUseCase does NOT exclude locked layers; the a11y
        // projection must match so the spoken row reflects what mark-row-done
        // would touch.
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 0)
        val layers = listOf(layer("L", listOf(cell("lck", 0, 0)), locked = true))

        val runs = ChartAccessibility.rowDescriptors(extents, layers).single().runs
        assertEquals(listOf(SymbolRun("lck", 1)), runs)
    }

    // ---- Progress mapping (lockstep with MarkRowSegmentsDoneUseCase) ----

    @Test
    fun `no progress lambda yields null progress for every row`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 1, minY = 0, maxY = 1)
        val layers = listOf(layer("L", listOf(cell("a", 0, 0), cell("a", 1, 1))))

        val d = ChartAccessibility.rowDescriptors(extents, layers, progressAt = null)
        assertTrue(d.all { it.progress == null })
    }

    @Test
    fun `progress maps to NotStarted InProgress Done across the visible row cells`() {
        // Row y=0 has 3 cells across two visible layers; row y=1 has 0 cells.
        val extents = ChartExtents.Rect(minX = 0, maxX = 2, minY = 0, maxY = 1)
        val layers =
            listOf(
                layer("L1", listOf(cell("a", 0, 0), cell("a", 1, 0))),
                layer("L2", listOf(cell("b", 2, 0))),
            )
        // (L1,0,0)=DONE, (L1,1,0)=WIP, (L2,2,0)=null(todo) -> 1 of 3 done.
        val progress: (String, Int, Int) -> SegmentState? = { l, x, y ->
            when {
                l == "L1" && x == 0 && y == 0 -> SegmentState.DONE
                l == "L1" && x == 1 && y == 0 -> SegmentState.WIP
                else -> null
            }
        }

        val d = ChartAccessibility.rowDescriptors(extents, layers, progressAt = progress)

        // Row 1 (y=0): InProgress(1, 3).
        assertEquals(RowProgress.InProgress(done = 1, total = 3), d[0].progress)
        // Row 2 (y=1): no cells -> NotStarted (total 0 collapses to NotStarted).
        assertEquals(RowProgress.NotStarted, d[1].progress)
    }

    @Test
    fun `all done row maps to Done`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 1, minY = 0, maxY = 1)
        val layers =
            listOf(
                layer("L", listOf(cell("a", 0, 0), cell("a", 1, 0), cell("a", 0, 1))),
            )
        val allDone: (String, Int, Int) -> SegmentState? = { _, _, _ -> SegmentState.DONE }

        val done = ChartAccessibility.rowDescriptors(extents, layers, progressAt = allDone)
        assertEquals(RowProgress.Done, done[0].progress) // row y=0 (2 cells, both DONE)
        assertEquals(RowProgress.Done, done[1].progress) // row y=1 (1 cell, DONE)
    }

    @Test
    fun `all WIP row is InProgress 0 of N not NotStarted`() {
        // A row the user has started (WIP) but completed none must read
        // "0 of N done", never "not started" — parity with the distinct
        // WIP visual stroke (Differentiate-Without-Color).
        val extents = ChartExtents.Rect(minX = 0, maxX = 1, minY = 0, maxY = 0)
        val layers = listOf(layer("L", listOf(cell("a", 0, 0), cell("a", 1, 0))))
        val allWip: (String, Int, Int) -> SegmentState? = { _, _, _ -> SegmentState.WIP }

        val d = ChartAccessibility.rowDescriptors(extents, layers, progressAt = allWip).single()
        assertEquals(RowProgress.InProgress(done = 0, total = 2), d.progress)
    }

    @Test
    fun `all todo row is NotStarted`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 1, minY = 0, maxY = 0)
        val layers = listOf(layer("L", listOf(cell("a", 0, 0), cell("a", 1, 0))))
        // progressAt returns null (implicit todo) for every cell.
        val allTodo: (String, Int, Int) -> SegmentState? = { _, _, _ -> null }

        val d = ChartAccessibility.rowDescriptors(extents, layers, progressAt = allTodo).single()
        assertEquals(RowProgress.NotStarted, d.progress)
    }

    @Test
    fun `WIP plus DONE mix counts only DONE toward the done tally`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 2, minY = 0, maxY = 0)
        val layers =
            listOf(layer("L", listOf(cell("a", 0, 0), cell("a", 1, 0), cell("a", 2, 0))))
        // x=0 DONE, x=1 WIP, x=2 todo -> 1 of 3 done, started.
        val mixed: (String, Int, Int) -> SegmentState? = { _, x, _ ->
            when (x) {
                0 -> SegmentState.DONE
                1 -> SegmentState.WIP
                else -> null
            }
        }

        val d = ChartAccessibility.rowDescriptors(extents, layers, progressAt = mixed).single()
        assertEquals(RowProgress.InProgress(done = 1, total = 3), d.progress)
    }

    @Test
    fun `progress tally excludes hidden and invisible layers like MarkRowSegmentsDone`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 0)
        val layers =
            listOf(
                layer("vis", listOf(cell("a", 0, 0))),
                layer("inv", listOf(cell("a", 0, 0)), visible = false),
                layer("hid", listOf(cell("a", 0, 0))),
            )
        // Only the visible 'vis' cell is DONE; inv/hid cells report DONE too but
        // must not inflate the tally (they are not in the mark-row-done set).
        val progress: (String, Int, Int) -> SegmentState? = { _, _, _ -> SegmentState.DONE }

        val d =
            ChartAccessibility
                .rowDescriptors(extents, layers, hiddenLayerIds = setOf("hid"), progressAt = progress)
                .single()

        assertEquals(RowProgress.Done, d.progress) // 1 of 1 (only 'vis' counted)
    }

    // ---- Empty / degenerate extents ----

    @Test
    fun `degenerate extents return no descriptors`() {
        val emptyRect = ChartExtents.Rect.EMPTY // minX=0,maxX=-1,minY=0,maxY=-1
        assertTrue(ChartAccessibility.rowDescriptors(emptyRect, emptyList()).isEmpty())

        val invertedY = ChartExtents.Rect(minX = 0, maxX = 2, minY = 3, maxY = 1)
        assertTrue(ChartAccessibility.rowDescriptors(invertedY, emptyList()).isEmpty())
    }

    @Test
    fun `single cell grid yields one row one run`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 0)
        val d = ChartAccessibility.rowDescriptors(extents, listOf(layer("L", listOf(cell("k", 0, 0)))))
        assertEquals(1, d.size)
        assertEquals(1, d[0].rowNumber)
        assertEquals(1, d[0].rowCount)
        assertEquals(0, d[0].chartY)
        assertEquals(listOf(SymbolRun("k", 1)), d[0].runs)
    }

    // ---- spokenLabel formatter (parity-by-construction) ----

    @Test
    fun `spokenLabel composes position runs and progress with count-1 suppression`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 4, minY = 0, maxY = 0)
        val layers =
            listOf(
                layer(
                    "L",
                    listOf(
                        cell("knit", 0, 0),
                        cell("knit", 1, 0),
                        cell("knit", 2, 0),
                        cell("purl", 3, 0),
                        // x=4 empty
                    ),
                ),
            )
        val progress: (String, Int, Int) -> SegmentState? = { _, x, _ ->
            if (x == 0) SegmentState.DONE else null
        }
        val d =
            ChartAccessibility
                .rowDescriptors(extents, layers, progressAt = progress)
                .single()

        val spoken =
            ChartAccessibility.spokenLabel(d, strings) { id ->
                when (id) {
                    "knit" -> "knit"
                    "purl" -> "purl"
                    else -> id
                }
            }

        // count==1 suppresses the "xN" suffix; blank uses blankCellsName.
        // Progress total counts actual cells only (knit x3 + purl = 4); the
        // x=4 blank column has no ChartCell so it is not in the
        // MarkRowSegmentsDone set — total is 4, not 5.
        assertEquals(
            "Row 1 of 1 - knit x3, purl, blank - 1 of 4 done",
            spoken,
        )
    }

    @Test
    fun `spokenLabel omits the progress section when progress is null`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 1, minY = 0, maxY = 0)
        val layers = listOf(layer("L", listOf(cell("k", 0, 0), cell("k", 1, 0))))
        val d = ChartAccessibility.rowDescriptors(extents, layers, progressAt = null).single()

        val spoken = ChartAccessibility.spokenLabel(d, strings) { it }

        assertEquals("Row 1 of 1 - k x2", spoken)
        assertNull(d.progress)
    }

    @Test
    fun `spokenLabel renders NotStarted and Done state words`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 1)
        val layers = listOf(layer("L", listOf(cell("a", 0, 0), cell("a", 0, 1))))
        val allDone: (String, Int, Int) -> SegmentState? = { _, _, _ -> SegmentState.DONE }

        val d = ChartAccessibility.rowDescriptors(extents, layers, progressAt = allDone)
        // Row 1 (y=0) all done.
        assertEquals(
            "Row 1 of 2 - a - done",
            ChartAccessibility.spokenLabel(d[0], strings) { it },
        )

        val emptyExtents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 0)
        val notStarted =
            ChartAccessibility
                .rowDescriptors(emptyExtents, emptyList(), progressAt = allDone)
                .single()
        assertEquals(
            "Row 1 of 1 - blank - not started",
            ChartAccessibility.spokenLabel(notStarted, strings) { it },
        )
    }

    @Test
    fun `symbolName resolver fallback to id is honored verbatim`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 0)
        val d =
            ChartAccessibility
                .rowDescriptors(extents, listOf(layer("L", listOf(cell("jis.knit.unknown", 0, 0)))))
                .single()
        // Resolver returns the id unchanged (R2-not-landed fallback path).
        val spoken = ChartAccessibility.spokenLabel(d, strings) { it }
        assertEquals("Row 1 of 1 - jis.knit.unknown", spoken)
    }

    @Test
    fun `spokenLabel handles the production multiplication sign template verbatim`() {
        // The other tests use an ASCII "x" for assertion legibility; this one
        // uses the real production '×' (U+00D7) template so a regression in
        // multibyte-safe replacement would be caught. Kotlin String.replace
        // is codepoint-correct, so '×' must round-trip intact.
        val prodStrings =
            strings.copy(
                symbolRunFormat = "%1\$s ×%2\$d",
                sectionSeparator = " — ",
            )
        val extents = ChartExtents.Rect(minX = 0, maxX = 2, minY = 0, maxY = 0)
        val layers = listOf(layer("L", listOf(cell("k", 0, 0), cell("k", 1, 0), cell("k", 2, 0))))
        val d = ChartAccessibility.rowDescriptors(extents, layers).single()

        assertEquals(
            "Row 1 of 1 — k ×3",
            ChartAccessibility.spokenLabel(d, prodStrings) { it },
        )
    }

    // ---------------------------------------------------------------------
    // R1b — Editor cell-cursor descriptor (ADR-025 §c Editor row +
    // in-row adjustable cursor; §d M5 forward layout, no inverse transform).
    // ---------------------------------------------------------------------

    private val cellStrings =
        CellA11yStrings(
            cellSymbolFormat = "Row %1\$d of %2\$d, col %3\$d of %4\$d, %5\$s",
            cellBlank = "blank",
            actionPlaceFormat = "Place %1\$s",
            actionErase = "Erase",
        )

    @Test
    fun `cellDescriptor maps exact cursor coordinates to 1-based col and row numbers`() {
        // 5-wide × 3-tall grid offset to (minX=10, minY=20) to catch any
        // accidental `cursorX - 0` shortcut. Cursor at (chartX=12, chartY=21)
        // ⇒ col = 12 - 10 + 1 = 3; row = 21 - 20 + 1 = 2.
        val extents = ChartExtents.Rect(minX = 10, maxX = 14, minY = 20, maxY = 22)
        val layers = listOf(layer("L", listOf(cell("k", 12, 21))))

        val d = ChartAccessibility.cellDescriptor(extents, layers, cursorX = 12, cursorY = 21)

        assertNotNull(d)
        assertEquals(12, d.chartX)
        assertEquals(21, d.chartY)
        assertEquals(3, d.colNumber)
        assertEquals(5, d.colCount)
        assertEquals(2, d.rowNumber)
        assertEquals(3, d.rowCount)
        assertEquals("k", d.symbolIdAt)
    }

    @Test
    fun `cellDescriptor clamps cursorX below minX to col 1 at the left edge`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 4, minY = 0, maxY = 0)

        val d = ChartAccessibility.cellDescriptor(extents, emptyList(), cursorX = -3, cursorY = 0)

        assertNotNull(d)
        assertEquals(0, d.chartX)
        assertEquals(1, d.colNumber)
    }

    @Test
    fun `cellDescriptor clamps cursorX above maxX to col N at the right edge`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 4, minY = 0, maxY = 0)

        val d = ChartAccessibility.cellDescriptor(extents, emptyList(), cursorX = 99, cursorY = 0)

        assertNotNull(d)
        assertEquals(4, d.chartX)
        assertEquals(5, d.colNumber)
        assertEquals(5, d.colCount)
    }

    @Test
    fun `cellDescriptor clamps cursorY below minY to row 1 and above maxY to row N`() {
        // minY=5 to confirm the clamp is to minY, not to 0.
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 5, maxY = 9)

        val low = ChartAccessibility.cellDescriptor(extents, emptyList(), cursorX = 0, cursorY = -100)
        val high = ChartAccessibility.cellDescriptor(extents, emptyList(), cursorX = 0, cursorY = 100)

        assertNotNull(low)
        assertEquals(5, low.chartY)
        assertEquals(1, low.rowNumber)

        assertNotNull(high)
        assertEquals(9, high.chartY)
        assertEquals(5, high.rowNumber)
        assertEquals(5, high.rowCount)
    }

    @Test
    fun `cellDescriptor symbolIdAt picks topmost visible layer at cursor mirroring topmostLayerAt`() {
        // Two visible layers stacked at (0, 0); the second declared wins.
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 0)
        val layers =
            listOf(
                layer("base", listOf(cell("under", 0, 0))),
                layer("top", listOf(cell("over", 0, 0))),
            )

        val d = ChartAccessibility.cellDescriptor(extents, layers, cursorX = 0, cursorY = 0)

        assertNotNull(d)
        assertEquals("over", d.symbolIdAt)
    }

    @Test
    fun `cellDescriptor excludes invisible and UI-hidden layers at cursor`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 0)
        val layers =
            listOf(
                layer("base", listOf(cell("base-sym", 0, 0))),
                layer("invisible", listOf(cell("inv", 0, 0)), visible = false),
                layer("hidden", listOf(cell("hid", 0, 0))),
            )

        val d =
            ChartAccessibility.cellDescriptor(
                extents,
                layers,
                hiddenLayerIds = setOf("hidden"),
                cursorX = 0,
                cursorY = 0,
            )

        assertNotNull(d)
        assertEquals("base-sym", d.symbolIdAt)
    }

    @Test
    fun `cellDescriptor blank cursor cell yields null symbolIdAt`() {
        // 2x2 grid with a cell only at (0, 0); cursor lands on the empty (1, 1).
        val extents = ChartExtents.Rect(minX = 0, maxX = 1, minY = 0, maxY = 1)
        val layers = listOf(layer("L", listOf(cell("k", 0, 0))))

        val d = ChartAccessibility.cellDescriptor(extents, layers, cursorX = 1, cursorY = 1)

        assertNotNull(d)
        assertNull(d.symbolIdAt)
        assertEquals(2, d.colNumber)
        assertEquals(2, d.rowNumber)
    }

    @Test
    fun `cellDescriptor returns null on degenerate extents`() {
        val degenerate = ChartExtents.Rect(minX = 0, maxX = -1, minY = 0, maxY = -1)
        assertNull(ChartAccessibility.cellDescriptor(degenerate, emptyList(), cursorX = 0, cursorY = 0))

        val invertedY = ChartExtents.Rect(minX = 0, maxX = 2, minY = 5, maxY = 3)
        assertNull(ChartAccessibility.cellDescriptor(invertedY, emptyList(), cursorX = 0, cursorY = 0))
    }

    @Test
    fun `spokenCellLabel formats symbol cells using the resolver`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 3, minY = 0, maxY = 2)
        val layers = listOf(layer("L", listOf(cell("knit", 1, 1))))
        val d = ChartAccessibility.cellDescriptor(extents, layers, cursorX = 1, cursorY = 1)

        val spoken =
            ChartAccessibility.spokenCellLabel(
                requireNotNull(d),
                cellStrings,
            ) { id -> if (id == "knit") "knit" else id }

        assertEquals("Row 2 of 3, col 2 of 4, knit", spoken)
    }

    @Test
    fun `spokenCellLabel uses cellBlank when symbolIdAt is null`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 0)
        val d = ChartAccessibility.cellDescriptor(extents, emptyList(), cursorX = 0, cursorY = 0)

        val spoken =
            ChartAccessibility.spokenCellLabel(requireNotNull(d), cellStrings) { id -> id }

        // Resolver is never consulted for a blank cell — the format string
        // gets `cellBlank` substituted into the symbol slot.
        assertEquals("Row 1 of 1, col 1 of 1, blank", spoken)
    }

    @Test
    fun `spokenCellLabel honors the symbolName resolver fallback to id`() {
        val extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 0)
        val layers = listOf(layer("L", listOf(cell("jis.knit.unknown", 0, 0))))
        val d = ChartAccessibility.cellDescriptor(extents, layers, cursorX = 0, cursorY = 0)

        // Resolver returns id verbatim (R2-not-landed fallback per ADR-025 §f).
        val spoken =
            ChartAccessibility.spokenCellLabel(requireNotNull(d), cellStrings) { it }

        assertEquals("Row 1 of 1, col 1 of 1, jis.knit.unknown", spoken)
    }

    @Test
    fun `placeOrEraseActionLabel returns actionErase verbatim when selectedSymbolId is null`() {
        val label =
            ChartAccessibility.placeOrEraseActionLabel(
                cellStrings,
                selectedSymbolId = null,
            ) { id -> id }
        assertEquals("Erase", label)
    }

    @Test
    fun `placeOrEraseActionLabel substitutes the resolved symbol name into actionPlaceFormat`() {
        val label =
            ChartAccessibility.placeOrEraseActionLabel(
                cellStrings,
                selectedSymbolId = "knit",
            ) { id -> if (id == "knit") "knit" else id }
        assertEquals("Place knit", label)
    }
}
