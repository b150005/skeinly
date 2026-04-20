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
    fun `tap at top-left of grid returns minX maxY (y flipped)`() {
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
}
