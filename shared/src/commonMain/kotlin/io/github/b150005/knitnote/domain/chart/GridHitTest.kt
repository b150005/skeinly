package io.github.b150005.knitnote.domain.chart

import io.github.b150005.knitnote.domain.model.ChartExtents
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

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

    /**
     * Maps a screen-space tap to a polar chart cell `(stitch, ring)` per
     * ADR-011 §1.
     *
     * Returns `null` if:
     * - The tap falls inside the inner hole (`radius < innerRadius`).
     * - The tap falls outside the outermost ring (`radius >= innerRadius + rings * ringThickness`).
     * - The polar extents are degenerate (rings == 0).
     * - Layout dimensions are non-positive.
     *
     * Angle convention: 12 o'clock = 0, clockwise positive. Stitch 0 of each
     * ring begins at 12 o'clock; the wedge spans `[0, 2π/N)` where `N =
     * stitchesPerRing[ring]`.
     */
    fun hitTestPolar(
        screenX: Double,
        screenY: Double,
        extents: ChartExtents.Polar,
        layout: PolarCellLayout.Layout,
    ): Cell? {
        if (extents.rings <= 0) return null
        if (layout.ringThickness <= 0.0) return null
        if (layout.innerRadius < 0.0) return null

        val dx = screenX - layout.cx
        val dy = screenY - layout.cy
        val radius = sqrt(dx * dx + dy * dy)

        if (radius < layout.innerRadius) return null
        val outerRadius = layout.innerRadius + extents.rings * layout.ringThickness
        if (radius >= outerRadius) return null

        val ring =
            floor((radius - layout.innerRadius) / layout.ringThickness)
                .toInt()
                .coerceIn(0, extents.rings - 1) // defensive against fp boundary rounding
        val stitchesInRing = extents.stitchesPerRing.getOrNull(ring) ?: return null
        if (stitchesInRing <= 0) return null

        // Screen atan2 runs clockwise from +x (3 o'clock) because screen y is flipped.
        // Adding π/2 shifts the origin to 12 o'clock; mod 2π normalizes.
        val rawTheta = atan2(dy, dx) + PI / 2.0
        val twoPi = 2.0 * PI
        val theta = ((rawTheta % twoPi) + twoPi) % twoPi

        val sweep = twoPi / stitchesInRing
        val stitch = floor(theta / sweep).toInt().coerceIn(0, stitchesInRing - 1)

        return Cell(x = stitch, y = ring)
    }
}
