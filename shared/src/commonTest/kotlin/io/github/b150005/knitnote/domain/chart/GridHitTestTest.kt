package io.github.b150005.knitnote.domain.chart

import io.github.b150005.knitnote.domain.model.ChartExtents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GridHitTestTest {
    private val extents = ChartExtents.Rect(minX = 0, maxX = 7, minY = 0, maxY = 7)
    private val cellSize = 10.0
    private val originX = 0.0
    private val originY = 0.0

    @Test
    fun `tap inside the bottom-left cell returns minX minY`() {
        // Bottom-left on screen is actually chart y = maxY = 7 after the y-flip.
        // Actually no: the convention puts chart origin at bottom-left of the screen grid.
        // The top of the screen maps to chart y = maxY. The bottom of the screen maps to minY.
        // Screen bottom-left pixel ≈ (originX + 0, originY + gridHeight*cellSize - 1).
        val cell =
            GridHitTest.hitTest(
                screenX = 1.0,
                screenY = 79.0,
                extents = extents,
                cellSize = cellSize,
                originX = originX,
                originY = originY,
            )
        assertEquals(GridHitTest.Cell(x = 0, y = 0), cell)
    }

    @Test
    fun `tap at top-left of grid returns minX maxY with y flipped`() {
        val cell =
            GridHitTest.hitTest(
                screenX = 1.0,
                screenY = 1.0,
                extents = extents,
                cellSize = cellSize,
                originX = originX,
                originY = originY,
            )
        assertEquals(GridHitTest.Cell(x = 0, y = 7), cell)
    }

    @Test
    fun `tap outside the grid returns null`() {
        val below =
            GridHitTest.hitTest(
                screenX = 1.0,
                screenY = 81.0,
                extents = extents,
                cellSize = cellSize,
                originX = originX,
                originY = originY,
            )
        val above = GridHitTest.hitTest(1.0, -1.0, extents, cellSize, originX, originY)
        val right = GridHitTest.hitTest(81.0, 1.0, extents, cellSize, originX, originY)
        assertNull(below)
        assertNull(above)
        assertNull(right)
    }

    @Test
    fun `hitTest respects origin offset`() {
        val cell =
            GridHitTest.hitTest(
                screenX = 55.0,
                screenY = 105.0,
                extents = extents,
                cellSize = cellSize,
                originX = 50.0,
                originY = 100.0,
            )
        // localX = 5 -> gx = 0; localY = 5 -> rowFromTop = 0 -> gy = 7
        assertEquals(GridHitTest.Cell(x = 0, y = 7), cell)
    }

    @Test
    fun `empty extents returns null`() {
        val empty = ChartExtents.Rect.EMPTY
        val cell = GridHitTest.hitTest(0.0, 0.0, empty, cellSize, originX, originY)
        assertNull(cell)
    }

    // --- hitTestPolar ---

    private val polarExtents =
        ChartExtents.Polar(rings = 3, stitchesPerRing = listOf(4, 8, 12))
    private val polarLayout =
        PolarCellLayout.Layout(
            cx = 100.0,
            cy = 100.0,
            innerRadius = 20.0,
            ringThickness = 10.0,
        )

    @Test
    fun `hitTestPolar tap inside inner hole returns null`() {
        // Radius from center < innerRadius.
        val cell = GridHitTest.hitTestPolar(100.0, 100.0, polarExtents, polarLayout)
        assertNull(cell)
    }

    @Test
    fun `hitTestPolar tap inside inner hole off-center returns null`() {
        val cell = GridHitTest.hitTestPolar(85.0, 100.0, polarExtents, polarLayout)
        assertNull(cell)
    }

    @Test
    fun `hitTestPolar tap beyond outermost ring returns null`() {
        val cell = GridHitTest.hitTestPolar(155.0, 100.0, polarExtents, polarLayout)
        assertNull(cell)
    }

    @Test
    fun `hitTestPolar tap exactly at outer boundary is exclusive`() {
        // outerRadius = 20 + 3*10 = 50; radius=50 returns null.
        val cell = GridHitTest.hitTestPolar(150.0, 100.0, polarExtents, polarLayout)
        assertNull(cell)
    }

    @Test
    fun `hitTestPolar tap exactly at inner boundary is inclusive`() {
        // radius = 20 at (100, 80); lands in ring 0 at stitch 0 (12 o clock).
        val cell = GridHitTest.hitTestPolar(100.0, 80.0, polarExtents, polarLayout)
        assertEquals(GridHitTest.Cell(x = 0, y = 0), cell)
    }

    @Test
    fun `hitTestPolar twelve o clock ring 1 returns stitch 0 ring 1`() {
        // (100, 70): dx=0, dy=-30, radius=30 which lands in ring 1.
        val cell = GridHitTest.hitTestPolar(100.0, 70.0, polarExtents, polarLayout)
        assertEquals(GridHitTest.Cell(x = 0, y = 1), cell)
    }

    @Test
    fun `hitTestPolar three o clock ring 1 returns stitch 2`() {
        // Ring 1 has 8 stitches so 3 o clock is one quarter around CW which is stitch 2.
        val cell = GridHitTest.hitTestPolar(130.0, 100.0, polarExtents, polarLayout)
        assertEquals(GridHitTest.Cell(x = 2, y = 1), cell)
    }

    @Test
    fun `hitTestPolar six o clock ring 1 returns stitch 4`() {
        val cell = GridHitTest.hitTestPolar(100.0, 130.0, polarExtents, polarLayout)
        assertEquals(GridHitTest.Cell(x = 4, y = 1), cell)
    }

    @Test
    fun `hitTestPolar nine o clock ring 1 returns stitch 6`() {
        val cell = GridHitTest.hitTestPolar(70.0, 100.0, polarExtents, polarLayout)
        assertEquals(GridHitTest.Cell(x = 6, y = 1), cell)
    }

    @Test
    fun `hitTestPolar between rings partitions correctly`() {
        // radius=25 lands in ring 0 exactly mid-thickness.
        val cell = GridHitTest.hitTestPolar(125.0, 100.0, polarExtents, polarLayout)
        // Ring 0 has 4 stitches and 3 o clock is stitch 1 (wedge 0 spans 0 to PI over 2).
        assertEquals(GridHitTest.Cell(x = 1, y = 0), cell)
    }

    @Test
    fun `hitTestPolar rings 0 returns null`() {
        val empty = ChartExtents.Polar(rings = 0, stitchesPerRing = emptyList())
        val cell = GridHitTest.hitTestPolar(100.0, 100.0, empty, polarLayout)
        assertNull(cell)
    }

    @Test
    fun `hitTestPolar non-positive ringThickness returns null`() {
        val bad = polarLayout.copy(ringThickness = 0.0)
        val cell = GridHitTest.hitTestPolar(125.0, 100.0, polarExtents, bad)
        assertNull(cell)
    }

    @Test
    fun `hitTestPolar offset center respects layout cx cy`() {
        val offset = polarLayout.copy(cx = 200.0, cy = 200.0)
        val cell = GridHitTest.hitTestPolar(200.0, 180.0, polarExtents, offset)
        assertEquals(GridHitTest.Cell(x = 0, y = 0), cell)
    }

    @Test
    fun `hitTestPolar atan2 fourth quadrant resolves to stitch 0`() {
        // dy<0 dx>0 small offset from 12 o clock CW. Should fall in stitch 0 wedge of ring 0.
        val cell = GridHitTest.hitTestPolar(125.0, 90.0, polarExtents, polarLayout)
        // radius ~= 26.9 so ring 0. Angle from 12 o clock CW is about 68 degrees which is still in stitch 0's wedge [0, 90].
        assertEquals(GridHitTest.Cell(x = 0, y = 0), cell)
    }

    @Test
    fun `hitTestPolar outer ring uses ring 2`() {
        // radius=45 → ring 2 (band 40..50).
        val cell = GridHitTest.hitTestPolar(145.0, 100.0, polarExtents, polarLayout)
        // Ring 2 has 12 stitches and 3 o clock is one quarter CW which is stitch 3.
        assertEquals(GridHitTest.Cell(x = 3, y = 2), cell)
    }

    @Test
    fun `hitTestPolar angle wraparound left side returns stitch index less than N`() {
        // 9 o clock ring 2: three quarters CW around 12 stitches = stitch 9.
        val cell = GridHitTest.hitTestPolar(55.0, 100.0, polarExtents, polarLayout)
        assertEquals(GridHitTest.Cell(x = 9, y = 2), cell)
    }

    @Test
    fun `hitTestPolar near-12-o-clock from left side returns last stitch of ring`() {
        // Just slightly to the left of 12 o clock at radius ~31 so we land in ring 1.
        // Angle is near 2π and wraps, landing in the last stitch wedge (ring 1 has 8 stitches).
        val cell = GridHitTest.hitTestPolar(99.0, 69.0, polarExtents, polarLayout)
        assertEquals(GridHitTest.Cell(x = 7, y = 1), cell)
    }
}
