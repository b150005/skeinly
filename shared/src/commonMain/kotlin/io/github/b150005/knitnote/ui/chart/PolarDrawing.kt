package io.github.b150005.knitnote.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.b150005.knitnote.domain.chart.PolarCellLayout
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.SegmentState
import io.github.b150005.knitnote.domain.model.StructuredChart
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Fit [polar] inside a canvas of [canvasWidth] x [canvasHeight]. The inner hole
 * reserves ~15% of the usable radius so rings retain even width when
 * [polar].rings is small, and the outer radius leaves a small margin against
 * the canvas edges.
 */
fun polarLayoutFor(
    canvasWidth: Float,
    canvasHeight: Float,
    polar: ChartExtents.Polar,
): PolarCellLayout.Layout {
    val cx = canvasWidth / 2.0
    val cy = canvasHeight / 2.0
    // 0.47 ≈ 94% of the half-extent — leaves a visible margin on the canvas edge.
    val maxRadius = min(canvasWidth, canvasHeight) * 0.47
    val innerRadius = maxRadius * 0.15
    val rings = polar.rings.coerceAtLeast(1)
    val ringThickness = (maxRadius - innerRadius) / rings
    return PolarCellLayout.Layout(
        cx = cx,
        cy = cy,
        innerRadius = innerRadius,
        ringThickness = ringThickness,
    )
}

/**
 * Paint concentric ring boundaries plus radial spokes along the outermost
 * ring's stitch boundaries. Per-ring spokes are a Phase 35.x polish item —
 * ADR-011 §2 accepts outermost-only as the first cut.
 */
fun DrawScope.drawPolarGrid(
    polar: ChartExtents.Polar,
    layout: PolarCellLayout.Layout,
    color: Color,
) {
    val center = Offset(layout.cx.toFloat(), layout.cy.toFloat())
    for (i in 0..polar.rings) {
        val r = layout.innerRadius + i * layout.ringThickness
        drawCircle(
            color = color,
            center = center,
            radius = r.toFloat(),
            style = Stroke(width = 1f),
        )
    }
    val outerStitches = polar.stitchesPerRing.lastOrNull() ?: return
    if (outerStitches <= 0) return
    val innerR = layout.innerRadius
    val outerR = layout.innerRadius + polar.rings * layout.ringThickness
    val sweep = 2.0 * PI / outerStitches
    for (s in 0 until outerStitches) {
        // 12-o'clock-CW convention → screen cartesian: subtract π/2.
        val screenAngle = s * sweep - PI / 2.0
        val dx = cos(screenAngle)
        val dy = sin(screenAngle)
        drawLine(
            color = color,
            start =
                Offset(
                    (layout.cx + innerR * dx).toFloat(),
                    (layout.cy + innerR * dy).toFloat(),
                ),
            end =
                Offset(
                    (layout.cx + outerR * dx).toFloat(),
                    (layout.cy + outerR * dy).toFloat(),
                ),
            strokeWidth = 1f,
        )
    }
}

/**
 * Build an annular-wedge [Path] for [wedge] centered at [layout]. Traces the
 * outer arc clockwise then the inner arc counter-clockwise to form a closed
 * region — avoids the need for a separate mask on the hole.
 *
 * Convention conversion: [PolarCellLayout] uses 12 o'clock = 0°, CW positive.
 * Compose [Path.arcTo] measures from 3 o'clock, CW positive on y-down screens.
 * The `-90` shift reconciles the two.
 */
fun polarWedgePath(
    wedge: PolarCellLayout.Wedge,
    layout: PolarCellLayout.Layout,
): Path {
    val startDeg = (wedge.startAngle * 180.0 / PI - 90.0).toFloat()
    val sweepDeg = (wedge.sweepAngle * 180.0 / PI).toFloat()
    val cx = layout.cx.toFloat()
    val cy = layout.cy.toFloat()
    val outerR = wedge.outerRadius.toFloat()
    val innerR = wedge.innerRadius.toFloat()
    val outerRect = Rect(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
    val innerRect = Rect(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
    return Path().apply {
        arcTo(
            rect = outerRect,
            startAngleDegrees = startDeg,
            sweepAngleDegrees = sweepDeg,
            forceMoveTo = true,
        )
        arcTo(
            rect = innerRect,
            startAngleDegrees = startDeg + sweepDeg,
            sweepAngleDegrees = -sweepDeg,
            forceMoveTo = false,
        )
        close()
    }
}

/**
 * Paint done/wip segment overlays for every visible cell whose (x, y) maps to
 * a valid (stitch, ring) within [polar]. Out-of-range cells are silently
 * skipped — matches the rect renderer's defensive clipping.
 */
fun DrawScope.drawPolarSegmentOverlay(
    polar: ChartExtents.Polar,
    chart: StructuredChart,
    hiddenLayerIds: Set<String>,
    segments: Map<SegmentKey, SegmentState>,
    layout: PolarCellLayout.Layout,
    doneColor: Color,
    wipColor: Color,
    wipStrokeWidthPx: Float,
) {
    chart.layers.forEach { layer ->
        if (!layer.visible || layer.id in hiddenLayerIds) return@forEach
        layer.cells.forEach { cell ->
            val ring = cell.y
            val stitch = cell.x
            if (ring !in 0 until polar.rings) return@forEach
            val stitchesInRing = polar.stitchesPerRing.getOrNull(ring) ?: return@forEach
            if (stitch !in 0 until stitchesInRing) return@forEach
            val state = segments[SegmentKey(layer.id, stitch, ring)] ?: return@forEach
            val wedge = PolarCellLayout.wedgeFor(stitch, ring, polar, layout)
            val path = polarWedgePath(wedge, layout)
            when (state) {
                SegmentState.DONE -> drawPath(path = path, color = doneColor)
                SegmentState.WIP ->
                    drawPath(
                        path = path,
                        color = wipColor,
                        style = Stroke(width = wipStrokeWidthPx),
                    )
            }
        }
    }
}
