package io.github.b150005.knitnote.domain.symbol

/**
 * Intermediate representation emitted by [SvgPathParser] and consumed by
 * platform-specific symbol renderers (Compose on Android, SwiftUI on iOS).
 *
 * All coordinates are absolute and live in unit-square space (0..1). Relative
 * commands in the source path (`m`, `l`, `c`, …) are resolved against the current
 * point at parse time so renderers only need to handle absolute movement.
 */
sealed interface PathCommand {
    data class MoveTo(
        val x: Double,
        val y: Double,
    ) : PathCommand

    data class LineTo(
        val x: Double,
        val y: Double,
    ) : PathCommand

    /** Cubic Bézier with both control points absolute. */
    data class CurveTo(
        val c1x: Double,
        val c1y: Double,
        val c2x: Double,
        val c2y: Double,
        val x: Double,
        val y: Double,
    ) : PathCommand

    /** Quadratic Bézier. */
    data class QuadTo(
        val c1x: Double,
        val c1y: Double,
        val x: Double,
        val y: Double,
    ) : PathCommand

    data object ClosePath : PathCommand
}
