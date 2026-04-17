package io.github.b150005.knitnote.domain.chart

import io.github.b150005.knitnote.domain.symbol.PathCommand

/**
 * Pure mapping from symbol-local unit-square coordinates (y-down, 0..1) to screen-space
 * coordinates inside a target cell rectangle, with optional axis-aligned rotation
 * applied around the cell center.
 *
 * Phase 31 platform renderers (Compose `DrawScope` on Android, SwiftUI `Path` on iOS)
 * call [mapCommand] for every [PathCommand] emitted by [io.github.b150005.knitnote.domain.symbol.SvgPathParser]
 * and feed the result straight into native draw calls.
 *
 * The chart-coord-to-screen flip described in `docs/en/chart-coordinates.md` happens
 * outside this transform: callers compute [CellBounds] in already-flipped screen space.
 * Only axis-aligned rotations (0/90/180/270) are accepted, matching ADR-008 §Rotation.
 */
object SymbolRenderTransform {
    private val SUPPORTED_ROTATIONS = setOf(0, 90, 180, 270)

    fun mapPoint(
        unitX: Double,
        unitY: Double,
        bounds: CellBounds,
        rotation: Int = 0,
    ): ScreenPoint {
        val (rx, ry) = rotateUnit(unitX, unitY, rotation)
        val sx = bounds.left + rx * (bounds.right - bounds.left)
        val sy = bounds.top + ry * (bounds.bottom - bounds.top)
        return ScreenPoint(sx, sy)
    }

    fun mapCommand(
        command: PathCommand,
        bounds: CellBounds,
        rotation: Int = 0,
    ): PathCommand =
        when (command) {
            is PathCommand.MoveTo -> {
                val p = mapPoint(command.x, command.y, bounds, rotation)
                PathCommand.MoveTo(p.x, p.y)
            }
            is PathCommand.LineTo -> {
                val p = mapPoint(command.x, command.y, bounds, rotation)
                PathCommand.LineTo(p.x, p.y)
            }
            is PathCommand.CurveTo -> {
                val c1 = mapPoint(command.c1x, command.c1y, bounds, rotation)
                val c2 = mapPoint(command.c2x, command.c2y, bounds, rotation)
                val end = mapPoint(command.x, command.y, bounds, rotation)
                PathCommand.CurveTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
            }
            is PathCommand.QuadTo -> {
                val c1 = mapPoint(command.c1x, command.c1y, bounds, rotation)
                val end = mapPoint(command.x, command.y, bounds, rotation)
                PathCommand.QuadTo(c1.x, c1.y, end.x, end.y)
            }
            PathCommand.ClosePath -> PathCommand.ClosePath
        }

    private fun rotateUnit(
        ux: Double,
        uy: Double,
        rotation: Int,
    ): Pair<Double, Double> {
        require(rotation in SUPPORTED_ROTATIONS) {
            "rotation must be one of $SUPPORTED_ROTATIONS; got $rotation"
        }
        val cx = ux - 0.5
        val cy = uy - 0.5
        val (rx, ry) =
            when (rotation) {
                0 -> cx to cy
                // Clockwise in y-down screen space: (x, y) -> (-y, x)
                90 -> -cy to cx
                180 -> -cx to -cy
                270 -> cy to -cx
                else -> error("unreachable")
            }
        return (rx + 0.5) to (ry + 0.5)
    }
}

/**
 * Screen-space rectangle for a single chart cell, after the chart-coord-to-screen
 * flip. `top < bottom` in y-down screen coordinates.
 */
data class CellBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(right >= left) { "right ($right) must be >= left ($left)" }
        require(bottom >= top) { "bottom ($bottom) must be >= top ($top)" }
    }
}

data class ScreenPoint(
    val x: Double,
    val y: Double,
)
