package io.github.b150005.skeinly.domain.chart

import io.github.b150005.skeinly.domain.model.ChartCell
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartLayer
import io.github.b150005.skeinly.domain.model.CoordinateSystem
import io.github.b150005.skeinly.domain.model.StorageVariant
import io.github.b150005.skeinly.domain.model.StructuredChart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Phase 38.4 (ADR-014 §4) coverage matrix for [ConflictDetector]:
 *
 *  1. identical theirs/mine versus ancestor → empty report, isClean true
 *  2. only theirs changed a cell → autoFromTheirs, no conflict
 *  3. only mine changed a cell → autoFromMine, no conflict
 *  4. both changed same cell to different values → CellConflict
 *  5. both changed same cell to the same value → auto-resolved, not surfaced as conflict
 *  6. cell added in theirs only → autoFromTheirs CellAdded
 *  7. cell added in both at same coord with different symbols → CellConflict
 *  8. one side removed, other side modified same cell → CellConflict
 *  9. parametric symbol param edit on both sides differently → CellConflict
 * 10. layer added on theirs, untouched on mine → autoLayerFromTheirs
 * 11. layer renamed differently on both sides → LayerConflict
 * 12. layer renamed identically on both sides → auto-resolved, not in layerConflicts
 * 13. polar charts diff identically (cell.x = stitch, cell.y = ring)
 * 14. multi-layer mixed changes — independent partitioning per layer
 */
class ConflictDetectorTest {
    private val now = Instant.parse("2026-04-26T10:00:00Z")

    private fun chart(
        id: String,
        layers: List<ChartLayer>,
        coordinateSystem: CoordinateSystem = CoordinateSystem.RECT_GRID,
        extents: ChartExtents = ChartExtents.Rect(minX = 0, maxX = 8, minY = 0, maxY = 8),
        revisionId: String = id,
        parentRevisionId: String? = null,
    ): StructuredChart =
        StructuredChart(
            id = id,
            patternId = "pat-1",
            ownerId = "user-1",
            schemaVersion = 2,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = coordinateSystem,
            extents = extents,
            layers = layers,
            revisionId = revisionId,
            parentRevisionId = parentRevisionId,
            contentHash = "h1-$revisionId",
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun `identical theirs and mine produce empty clean report`() {
        val layers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0))))
        val ancestor = chart("a", layers)
        val theirs = chart("t", layers, parentRevisionId = "a")
        val mine = chart("m", layers, parentRevisionId = "a")

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertTrue(report.isClean)
        assertEquals(0, report.autoFromTheirs.size)
        assertEquals(0, report.autoFromMine.size)
        assertEquals(0, report.conflicts.size)
        assertEquals(0, report.layerConflicts.size)
    }

    @Test
    fun `only theirs changed a cell auto applies from theirs`() {
        val ancestor = chart("a", listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))))
        val theirs =
            chart(
                "t",
                listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 0, 0)))),
                parentRevisionId = "a",
            )
        val mine = chart("m", ancestor.layers, parentRevisionId = "a")

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertTrue(report.isClean)
        assertEquals(1, report.autoFromTheirs.size)
        assertEquals(0, report.autoFromMine.size)
        assertEquals(0, report.conflicts.size)
    }

    @Test
    fun `only mine changed a cell auto keeps mine`() {
        val ancestor = chart("a", listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))))
        val theirs = chart("t", ancestor.layers, parentRevisionId = "a")
        val mine =
            chart(
                "m",
                listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.yo", 0, 0)))),
                parentRevisionId = "a",
            )

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertTrue(report.isClean)
        assertEquals(0, report.autoFromTheirs.size)
        assertEquals(1, report.autoFromMine.size)
        assertEquals(0, report.conflicts.size)
    }

    @Test
    fun `both sides modify same cell to different values surfaces a conflict`() {
        val ancestor = chart("a", listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 2, 3)))))
        val theirs =
            chart(
                "t",
                listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 2, 3)))),
                parentRevisionId = "a",
            )
        val mine =
            chart(
                "m",
                listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.yo", 2, 3)))),
                parentRevisionId = "a",
            )

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertFalse(report.isClean)
        assertEquals(1, report.conflicts.size)
        val conflict = report.conflicts.first()
        assertEquals("L1", conflict.layerId)
        assertEquals(2, conflict.x)
        assertEquals(3, conflict.y)
        assertEquals("jis.k1", conflict.ancestor?.symbolId)
        assertEquals("jis.p1", conflict.theirs?.symbolId)
        assertEquals("jis.yo", conflict.mine?.symbolId)
    }

    @Test
    fun `both sides agree on same target cell value auto resolves with no conflict`() {
        val ancestor = chart("a", listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))))
        // Both sides decided to draw the same symbol at the same coordinate.
        val sharedAfter = ChartCell("jis.p1", 0, 0)
        val theirs = chart("t", listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(sharedAfter))), parentRevisionId = "a")
        val mine = chart("m", listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(sharedAfter))), parentRevisionId = "a")

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertTrue(report.isClean)
        assertEquals(0, report.conflicts.size)
        // Both sides agree → surfaced as autoFromTheirs (either side would render the same).
        assertEquals(1, report.autoFromTheirs.size)
    }

    @Test
    fun `cell added in theirs only is autoFromTheirs`() {
        val ancestor = chart("a", listOf(ChartLayer(id = "L1", name = "Main", cells = emptyList())))
        val theirs =
            chart(
                "t",
                listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 1, 1)))),
                parentRevisionId = "a",
            )
        val mine = chart("m", ancestor.layers, parentRevisionId = "a")

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertTrue(report.isClean)
        assertEquals(1, report.autoFromTheirs.size)
    }

    @Test
    fun `cell added in both at same coord with different symbols surfaces conflict`() {
        val ancestor = chart("a", listOf(ChartLayer(id = "L1", name = "Main", cells = emptyList())))
        val theirs =
            chart(
                "t",
                listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                parentRevisionId = "a",
            )
        val mine =
            chart(
                "m",
                listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 0, 0)))),
                parentRevisionId = "a",
            )

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertFalse(report.isClean)
        assertEquals(1, report.conflicts.size)
        // Ancestor cell did not exist; conflict reflects that.
        val conflict = report.conflicts.first()
        assertNull(conflict.ancestor)
        assertNotNull(conflict.theirs)
        assertNotNull(conflict.mine)
    }

    @Test
    fun `theirs removes while mine modifies same cell surfaces a conflict`() {
        val ancestor = chart("a", listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))))
        val theirs = chart("t", listOf(ChartLayer(id = "L1", name = "Main", cells = emptyList())), parentRevisionId = "a")
        val mine =
            chart(
                "m",
                listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 0, 0)))),
                parentRevisionId = "a",
            )

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertFalse(report.isClean)
        assertEquals(1, report.conflicts.size)
        val conflict = report.conflicts.first()
        assertNull(conflict.theirs)
        assertNotNull(conflict.mine)
    }

    @Test
    fun `parametric symbol parameter edits on both sides differently surface a conflict`() {
        val ancestor =
            chart(
                "a",
                listOf(
                    ChartLayer(
                        id = "L1",
                        name = "Main",
                        cells = listOf(ChartCell("std.cyc.ch-space", 0, 0, symbolParameters = mapOf("n" to "3"))),
                    ),
                ),
            )
        val theirs =
            chart(
                "t",
                listOf(
                    ChartLayer(
                        id = "L1",
                        name = "Main",
                        cells = listOf(ChartCell("std.cyc.ch-space", 0, 0, symbolParameters = mapOf("n" to "5"))),
                    ),
                ),
                parentRevisionId = "a",
            )
        val mine =
            chart(
                "m",
                listOf(
                    ChartLayer(
                        id = "L1",
                        name = "Main",
                        cells = listOf(ChartCell("std.cyc.ch-space", 0, 0, symbolParameters = mapOf("n" to "7"))),
                    ),
                ),
                parentRevisionId = "a",
            )

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertFalse(report.isClean)
        assertEquals(1, report.conflicts.size)
        assertEquals(
            "5",
            report.conflicts
                .first()
                .theirs
                ?.symbolParameters
                ?.get("n"),
        )
        assertEquals(
            "7",
            report.conflicts
                .first()
                .mine
                ?.symbolParameters
                ?.get("n"),
        )
    }

    @Test
    fun `layer added on theirs only is autoLayerFromTheirs`() {
        val ancestor = chart("a", listOf(ChartLayer(id = "L1", name = "Main")))
        val theirs =
            chart(
                "t",
                listOf(
                    ChartLayer(id = "L1", name = "Main"),
                    ChartLayer(id = "L2", name = "Cable"),
                ),
                parentRevisionId = "a",
            )
        val mine = chart("m", ancestor.layers, parentRevisionId = "a")

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertTrue(report.isClean)
        assertEquals(1, report.autoLayerFromTheirs.size)
        assertEquals(0, report.layerConflicts.size)
    }

    @Test
    fun `layer renamed differently on both sides surfaces a layer conflict`() {
        val ancestor = chart("a", listOf(ChartLayer(id = "L1", name = "Main")))
        val theirs = chart("t", listOf(ChartLayer(id = "L1", name = "Cable")), parentRevisionId = "a")
        val mine = chart("m", listOf(ChartLayer(id = "L1", name = "Lace")), parentRevisionId = "a")

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertFalse(report.isClean)
        assertEquals(1, report.layerConflicts.size)
        val conflict = report.layerConflicts.first()
        assertEquals("L1", conflict.layerId)
        assertEquals("Main", conflict.ancestor?.name)
        assertEquals("Cable", conflict.theirs?.name)
        assertEquals("Lace", conflict.mine?.name)
    }

    @Test
    fun `layer renamed identically on both sides auto resolves`() {
        val ancestor = chart("a", listOf(ChartLayer(id = "L1", name = "Main")))
        val sameRename = ChartLayer(id = "L1", name = "Cable")
        val theirs = chart("t", listOf(sameRename), parentRevisionId = "a")
        val mine = chart("m", listOf(sameRename), parentRevisionId = "a")

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertTrue(report.isClean)
        assertEquals(0, report.layerConflicts.size)
        assertEquals(1, report.autoLayerFromTheirs.size)
    }

    @Test
    fun `polar charts diff identically with stitch ring keying`() {
        val polarExtents = ChartExtents.Polar(rings = 4, stitchesPerRing = listOf(8, 12, 16, 20))
        val ancestor =
            chart(
                "a",
                listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.cyc.ch", 5, 2)))),
                coordinateSystem = CoordinateSystem.POLAR_ROUND,
                extents = polarExtents,
            )
        val theirs =
            chart(
                "t",
                listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.cyc.dc", 5, 2)))),
                coordinateSystem = CoordinateSystem.POLAR_ROUND,
                extents = polarExtents,
                parentRevisionId = "a",
            )
        val mine =
            chart(
                "m",
                listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.cyc.tr", 5, 2)))),
                coordinateSystem = CoordinateSystem.POLAR_ROUND,
                extents = polarExtents,
                parentRevisionId = "a",
            )

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertFalse(report.isClean)
        assertEquals(1, report.conflicts.size)
        val conflict = report.conflicts.first()
        assertEquals(5, conflict.x) // stitch
        assertEquals(2, conflict.y) // ring
    }

    @Test
    fun `multi layer mixed changes partition independently per layer`() {
        val ancestor =
            chart(
                "a",
                listOf(
                    ChartLayer(
                        id = "L1",
                        name = "Main",
                        cells = listOf(ChartCell("jis.k1", 0, 0), ChartCell("jis.k1", 1, 0)),
                    ),
                    ChartLayer(id = "L2", name = "Cable"),
                ),
            )
        val theirs =
            chart(
                "t",
                listOf(
                    ChartLayer(
                        id = "L1",
                        name = "Main",
                        // Modifies (0,0) — only theirs touches it
                        cells = listOf(ChartCell("jis.p1", 0, 0), ChartCell("jis.k1", 1, 0)),
                    ),
                    ChartLayer(id = "L2", name = "Cable", cells = listOf(ChartCell("jis.k1", 0, 0))),
                ),
                parentRevisionId = "a",
            )
        val mine =
            chart(
                "m",
                listOf(
                    ChartLayer(
                        id = "L1",
                        name = "Main",
                        // Modifies (1,0) — only mine touches it; (0,0) still ancestor
                        cells = listOf(ChartCell("jis.k1", 0, 0), ChartCell("jis.yo", 1, 0)),
                    ),
                    ChartLayer(id = "L2", name = "Cable"),
                ),
                parentRevisionId = "a",
            )

        val report = ConflictDetector.detect(ancestor, theirs, mine)

        assertTrue(report.isClean)
        // L1: (0,0) auto-from-theirs (Modified), (1,0) auto-from-mine (Modified) — no conflict.
        // L2: existed in ancestor with no cells; theirs adds (0,0) — cell-level
        // CellAdded inside the shared layer, NOT a layer-level Add. So total
        // autoFromTheirs = 2 (L1's (0,0) Modified + L2's (0,0) Added).
        assertEquals(2, report.autoFromTheirs.size)
        assertEquals(1, report.autoFromMine.size)
        // No layer-level changes on either side.
        assertEquals(0, report.autoLayerFromTheirs.size)
        assertEquals(0, report.layerConflicts.size)
    }
}
