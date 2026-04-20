package io.github.b150005.knitnote.domain.chart

import io.github.b150005.knitnote.domain.model.ChartExtents
import kotlin.math.floor

/**
 * Maps a screen-space tap to the chart cell coordinate it lands in, using the same
 * layout convention as the Phase 31 viewer (`cellRect`): origin at bottom-left in
 * chart space, rendered with the Y-axis flipped in screen space.
 *
 * Returns `null` when the tap falls outside the chart grid.
 */
object GridHitTest {
    data class Cell(
        val x: Int,
        val y: Int,
    )

    fun hitTest(
        screenX: Double,
        screenY: Double,
        extents: ChartExtents.Rect,
        cellSize: Double,
        originX: Double,
        originY: Double,
    ): Cell? {
        if (extents.maxX < extents.minX || extents.maxY < extents.minY) return null
        if (cellSize <= 0) return null

        val gridWidth = extents.maxX - extents.minX + 1
        val gridHeight = extents.maxY - extents.minY + 1

        val localX = screenX - originX
        val localY = screenY - originY
        if (localX < 0 || localY < 0) return null
        if (localX >= cellSize * gridWidth) return null
        if (localY >= cellSize * gridHeight) return null

        val gx = floor(localX / cellSize).toInt()
        // Y flip: screen row 0 corresponds to the top of the grid, which is chart y = maxY.
        val rowFromTop = floor(localY / cellSize).toInt()
        val gy = (gridHeight - 1 - rowFromTop)

        return Cell(x = extents.minX + gx, y = extents.minY + gy)
    }
}
