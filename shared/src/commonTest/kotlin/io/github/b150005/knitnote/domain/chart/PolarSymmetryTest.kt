package io.github.b150005.knitnote.domain.chart

import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolarSymmetryTest {
    private val extents8 =
        ChartExtents.Polar(rings = 2, stitchesPerRing = listOf(8, 8))

    private fun cell(
        x: Int,
        y: Int,
        symbolId: String = "std.knit.k",
        rotation: Int = 0,
    ) = ChartCell(symbolId = symbolId, x = x, y = y, rotation = rotation)

    // --- rotateCells ---

    @Test
    fun `rotateCells fold 2 produces one opposite copy per cell`() {
        val input = listOf(cell(0, 0))
        val out = PolarSymmetry.rotateCells(input, extents8, fold = 2)
        assertEquals(2, out.size)
        assertTrue(out.any { it.x == 0 && it.y == 0 })
        assertTrue(out.any { it.x == 4 && it.y == 0 })
    }

    @Test
    fun `rotateCells fold 4 produces three 90-degree copies per cell on ring of 8`() {
        val input = listOf(cell(0, 0))
        val out = PolarSymmetry.rotateCells(input, extents8, fold = 4)
        assertEquals(4, out.size)
        assertEquals(setOf(0, 2, 4, 6), out.map { it.x }.toSet())
    }

    @Test
    fun `rotateCells fold 3 on ring with 12 stitches produces three copies`() {
        val extents = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(12))
        val input = listOf(cell(1, 0))
        val out = PolarSymmetry.rotateCells(input, extents, fold = 3)
        assertEquals(3, out.size)
        assertEquals(setOf(1, 5, 9), out.map { it.x }.toSet())
    }

    @Test
    fun `rotateCells fold 3 on ring with 8 stitches skips that ring`() {
        // 8 is not divisible by 3 — ring should pass through unchanged.
        val input = listOf(cell(0, 0))
        val out = PolarSymmetry.rotateCells(input, extents8, fold = 3)
        assertEquals(input, out)
    }

    @Test
    fun `rotateCells with mixed rings applies per-ring divisibility`() {
        // ring 0 has 6 stitches, ring 1 has 8. fold=3 divides 6 but not 8.
        val extents = ChartExtents.Polar(rings = 2, stitchesPerRing = listOf(6, 8))
        val input = listOf(cell(0, 0), cell(0, 1))
        val out = PolarSymmetry.rotateCells(input, extents, fold = 3)
        // ring 0 produces 3 cells at x=0,2,4; ring 1 unchanged at x=0.
        assertEquals(4, out.size)
        val ring0 = out.filter { it.y == 0 }.map { it.x }.toSet()
        val ring1 = out.filter { it.y == 1 }.map { it.x }.toSet()
        assertEquals(setOf(0, 2, 4), ring0)
        assertEquals(setOf(0), ring1)
    }

    @Test
    fun `rotateCells fold 1 is identity`() {
        val input = listOf(cell(3, 0))
        assertEquals(input, PolarSymmetry.rotateCells(input, extents8, fold = 1))
    }

    @Test
    fun `rotateCells on empty cell list is no-op`() {
        assertEquals(emptyList(), PolarSymmetry.rotateCells(emptyList(), extents8, fold = 4))
    }

    @Test
    fun `rotateCells dedup keeps authored cell and drops colliding generated copy`() {
        // Authored cells at x=0 AND x=4 both on ring 0. fold=2 would generate
        // x=4 from x=0 and x=0 from x=4 — both collide with existing authored
        // cells, so output size equals input size.
        val input = listOf(cell(0, 0, symbolId = "a"), cell(4, 0, symbolId = "b"))
        val out = PolarSymmetry.rotateCells(input, extents8, fold = 2)
        assertEquals(2, out.size)
        // Authored symbols preserved, generated copies dropped.
        assertEquals("a", out.first { it.x == 0 }.symbolId)
        assertEquals("b", out.first { it.x == 4 }.symbolId)
    }

    @Test
    fun `rotateCells ignores out-of-range ring index`() {
        val input = listOf(cell(0, 5))
        val out = PolarSymmetry.rotateCells(input, extents8, fold = 2)
        assertEquals(input, out)
    }

    // --- reflectCells ---

    @Test
    fun `reflectCells axis 0 mirrors across 12 o'clock`() {
        val input = listOf(cell(1, 0))
        val out = PolarSymmetry.reflectCells(input, extents8, axisStitch = 0)
        assertEquals(2, out.size)
        // (2·0 - 1) mod 8 = 7
        assertTrue(out.any { it.x == 7 && it.y == 0 })
    }

    @Test
    fun `reflectCells axis 2 mirrors cell at stitch 1 to stitch 3`() {
        val input = listOf(cell(1, 0))
        val out = PolarSymmetry.reflectCells(input, extents8, axisStitch = 2)
        // (2·2 - 1) mod 8 = 3
        assertTrue(out.any { it.x == 3 && it.y == 0 })
    }

    @Test
    fun `reflectCells negates rotation field`() {
        val input = listOf(cell(1, 0, rotation = 90))
        val out = PolarSymmetry.reflectCells(input, extents8, axisStitch = 0)
        val mirrored = out.first { it.x == 7 }
        assertEquals(270, mirrored.rotation)
    }

    @Test
    fun `reflectCells rotation 0 stays 0 under negation`() {
        val input = listOf(cell(1, 0, rotation = 0))
        val out = PolarSymmetry.reflectCells(input, extents8, axisStitch = 0)
        val mirrored = out.first { it.x == 7 }
        assertEquals(0, mirrored.rotation)
    }

    @Test
    fun `reflectCells axis-on-cell leaves authored cell with no conflict copy`() {
        // Mirroring x=0 across axis 0 produces x=0 again — same (stitch, ring)
        // as the authored cell, so dedup drops the copy.
        val input = listOf(cell(0, 0))
        val out = PolarSymmetry.reflectCells(input, extents8, axisStitch = 0)
        assertEquals(1, out.size)
    }

    @Test
    fun `reflectCells on empty list is no-op`() {
        assertEquals(emptyList(), PolarSymmetry.reflectCells(emptyList(), extents8, axisStitch = 0))
    }

    @Test
    fun `reflectCells dedup keeps authored over mirrored conflict`() {
        // Author x=1 and x=7. Mirroring x=1 across axis 0 → x=7 (collision
        // with authored); authored wins.
        val input = listOf(cell(1, 0, symbolId = "a"), cell(7, 0, symbolId = "b"))
        val out = PolarSymmetry.reflectCells(input, extents8, axisStitch = 0)
        assertEquals(2, out.size)
        assertEquals("b", out.first { it.x == 7 }.symbolId)
    }

    @Test
    fun `reflectCells handles mixed rings with different stitchesPerRing`() {
        val extents = ChartExtents.Polar(rings = 2, stitchesPerRing = listOf(4, 8))
        val input = listOf(cell(1, 0), cell(1, 1))
        val out = PolarSymmetry.reflectCells(input, extents, axisStitch = 0)
        // ring 0 (4 stitches): (2·0 - 1) mod 4 = 3
        // ring 1 (8 stitches): (2·0 - 1) mod 8 = 7
        assertTrue(out.any { it.x == 3 && it.y == 0 })
        assertTrue(out.any { it.x == 7 && it.y == 1 })
    }
}
