package io.github.b150005.knitnote.ui.chart

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.b150005.knitnote.domain.chart.CellBounds
import io.github.b150005.knitnote.domain.chart.SymbolRenderTransform
import io.github.b150005.knitnote.domain.symbol.PathCommand
import io.github.b150005.knitnote.domain.symbol.SvgPathParser
import io.github.b150005.knitnote.domain.symbol.SymbolDefinition

/**
 * Render the SVG path of [def] inside [bounds], applying axis-aligned [rotation]
 * around the cell center. Coordinate math is delegated to
 * [SymbolRenderTransform] so the same logic is shared with the iOS renderer.
 */
fun DrawScope.drawSymbolPath(
    def: SymbolDefinition,
    bounds: Rect,
    rotation: Int = 0,
    color: Color = Color.Black,
    strokeWidthPx: Float = 2f,
    parsedPathCache: MutableMap<String, List<PathCommand>>? = null,
) {
    if (bounds.width <= 0f || bounds.height <= 0f) return
    val cellBounds =
        CellBounds(
            left = bounds.left.toDouble(),
            top = bounds.top.toDouble(),
            right = bounds.right.toDouble(),
            bottom = bounds.bottom.toDouble(),
        )
    val commands =
        parsedPathCache?.getOrPut(def.id) { SvgPathParser.parse(def.pathData) }
            ?: SvgPathParser.parse(def.pathData)
    val path = Path()
    commands.forEach { raw ->
        when (val cmd = SymbolRenderTransform.mapCommand(raw, cellBounds, rotation)) {
            is PathCommand.MoveTo -> path.moveTo(cmd.x.toFloat(), cmd.y.toFloat())
            is PathCommand.LineTo -> path.lineTo(cmd.x.toFloat(), cmd.y.toFloat())
            is PathCommand.CurveTo ->
                path.cubicTo(
                    cmd.c1x.toFloat(),
                    cmd.c1y.toFloat(),
                    cmd.c2x.toFloat(),
                    cmd.c2y.toFloat(),
                    cmd.x.toFloat(),
                    cmd.y.toFloat(),
                )
            is PathCommand.QuadTo ->
                path.quadraticTo(
                    cmd.c1x.toFloat(),
                    cmd.c1y.toFloat(),
                    cmd.x.toFloat(),
                    cmd.y.toFloat(),
                )
            PathCommand.ClosePath -> path.close()
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
    )
}

/** Draw a faint background tile to mark the cell boundary while inspecting a chart. */
fun DrawScope.drawCellBackground(
    bounds: Rect,
    color: Color,
) {
    drawRect(
        color = color,
        topLeft = bounds.topLeft,
        size = bounds.size,
    )
}
