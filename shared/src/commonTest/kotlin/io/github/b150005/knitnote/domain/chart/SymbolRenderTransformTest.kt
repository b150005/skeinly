package io.github.b150005.knitnote.domain.chart

import io.github.b150005.knitnote.domain.symbol.PathCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SymbolRenderTransformTest {
    private val cell = CellBounds(left = 100.0, top = 200.0, right = 140.0, bottom = 260.0)

    @Test
    fun `mapPoint with rotation 0 maps unit corners to screen corners`() {
        val topLeft = SymbolRenderTransform.mapPoint(0.0, 0.0, cell, rotation = 0)
        assertPoint(100.0, 200.0, topLeft)

        val bottomRight = SymbolRenderTransform.mapPoint(1.0, 1.0, cell, rotation = 0)
        assertPoint(140.0, 260.0, bottomRight)
    }

    @Test
    fun `mapPoint maps unit center to cell center for any rotation`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val center = SymbolRenderTransform.mapPoint(0.5, 0.5, cell, rotation = rotation)
            assertPoint(120.0, 230.0, center, message = "rotation=$rotation")
        }
    }

    @Test
    fun `mapPoint with rotation 90 rotates clockwise around cell center`() {
        // Unit (0, 0) is the symbol's top-left. After 90° CW around (0.5, 0.5),
        // it lands at unit (1, 0), which maps to screen (right, top) = (140, 200).
        val rotated = SymbolRenderTransform.mapPoint(0.0, 0.0, cell, rotation = 90)
        assertPoint(140.0, 200.0, rotated)
    }

    @Test
    fun `mapPoint with rotation 180 mirrors across cell center`() {
        val rotated = SymbolRenderTransform.mapPoint(0.0, 0.0, cell, rotation = 180)
        assertPoint(140.0, 260.0, rotated)
    }

    @Test
    fun `mapPoint with rotation 270 rotates counter-clockwise around cell center`() {
        val rotated = SymbolRenderTransform.mapPoint(0.0, 0.0, cell, rotation = 270)
        assertPoint(100.0, 260.0, rotated)
    }

    @Test
    fun `mapPoint with non-axis-aligned rotation throws`() {
        assertFailsWith<IllegalArgumentException> {
            SymbolRenderTransform.mapPoint(0.5, 0.5, cell, rotation = 45)
        }
    }

    @Test
    fun `CellBounds rejects inverted rectangle`() {
        assertFailsWith<IllegalArgumentException> {
            CellBounds(left = 10.0, top = 0.0, right = 5.0, bottom = 10.0)
        }
        assertFailsWith<IllegalArgumentException> {
            CellBounds(left = 0.0, top = 10.0, right = 10.0, bottom = 5.0)
        }
    }

    @Test
    fun `mapCommand transforms MoveTo and LineTo`() {
        val move = SymbolRenderTransform.mapCommand(PathCommand.MoveTo(0.0, 0.0), cell)
        assertEquals(PathCommand.MoveTo(100.0, 200.0), move)

        val line = SymbolRenderTransform.mapCommand(PathCommand.LineTo(1.0, 1.0), cell)
        assertEquals(PathCommand.LineTo(140.0, 260.0), line)
    }

    @Test
    fun `mapCommand transforms CurveTo with all control points`() {
        val curve =
            PathCommand.CurveTo(
                c1x = 0.0,
                c1y = 0.0,
                c2x = 1.0,
                c2y = 0.0,
                x = 1.0,
                y = 1.0,
            )
        val mapped = SymbolRenderTransform.mapCommand(curve, cell)
        assertEquals(
            PathCommand.CurveTo(
                c1x = 100.0,
                c1y = 200.0,
                c2x = 140.0,
                c2y = 200.0,
                x = 140.0,
                y = 260.0,
            ),
            mapped,
        )
    }

    @Test
    fun `mapCommand transforms QuadTo`() {
        val quad = PathCommand.QuadTo(c1x = 0.5, c1y = 0.0, x = 1.0, y = 1.0)
        val mapped = SymbolRenderTransform.mapCommand(quad, cell)
        assertEquals(
            PathCommand.QuadTo(c1x = 120.0, c1y = 200.0, x = 140.0, y = 260.0),
            mapped,
        )
    }

    @Test
    fun `mapCommand passes ClosePath through unchanged`() {
        assertEquals(
            PathCommand.ClosePath,
            SymbolRenderTransform.mapCommand(PathCommand.ClosePath, cell),
        )
    }

    @Test
    fun `mapPoint scales unit-square across multi-cell bounds`() {
        // A 3-stitch-wide cell rendered at cellSize = 40 yields bounds.width = 120.
        // The unit square stays 0..1, so unit (0.5, 0.5) lands at the geometric center.
        val wide = CellBounds(left = 0.0, top = 0.0, right = 120.0, bottom = 40.0)
        val center = SymbolRenderTransform.mapPoint(0.5, 0.5, wide, rotation = 0)
        assertPoint(60.0, 20.0, center)

        val rightEdge = SymbolRenderTransform.mapPoint(1.0, 0.5, wide, rotation = 0)
        assertPoint(120.0, 20.0, rightEdge)
    }

    private fun assertPoint(
        expectedX: Double,
        expectedY: Double,
        actual: ScreenPoint,
        message: String = "",
    ) {
        val tolerance = 1e-9
        assertEquals(expectedX, actual.x, tolerance, "x mismatch ($message)")
        assertEquals(expectedY, actual.y, tolerance, "y mismatch ($message)")
    }
}
