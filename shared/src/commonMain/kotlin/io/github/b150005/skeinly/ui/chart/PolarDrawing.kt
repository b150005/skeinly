package io.github.b150005.skeinly.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.b150005.skeinly.domain.chart.PolarCellLayout
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.SegmentState
import io.github.b150005.skeinly.domain.model.StructuredChart
import io.github.b150005.skeinly.domain.symbol.PathCommand
import io.github.b150005.skeinly.domain.symbol.SymbolCatalog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
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

/**
 * Paint the glyph inside every visible polar cell. The cell is the largest
 * axis-aligned square that fits inside the wedge at its mid-radius, then
 * rotated CW by the wedge's angular center so the glyph's local "up" points
 * radially outward. `cell.rotation` (author's discrete 0/90/180/270) is still
 * applied within the cell bounds — the radial orientation composes on top.
 *
 * Inscribed-square side is `min(ringThickness, 2·r_center·sin(sweep/2))` —
 * the `sin(sweep/2)` factor is the half-chord at r_center; clamped to ≥ 1f.
 * Out-of-range cells are silently skipped, mirroring [drawPolarSegmentOverlay].
 *
 * Caller is responsible for ordering — ADR-011 §2 requires glyphs on top of
 * the segment overlay (matches the rect renderer's AC-1.2 "overlay under glyph").
 *
 * [parsedPathCache] is a shared accumulator keyed by `SymbolDefinition.id`.
 * The rect and polar render paths share a single instance per canvas so SVG
 * path parsing happens at most once per symbol per composition.
 */
@Suppress("LongParameterList")
fun DrawScope.drawPolarCells(
    polar: ChartExtents.Polar,
    chart: StructuredChart,
    hiddenLayerIds: Set<String>,
    catalog: SymbolCatalog,
    layout: PolarCellLayout.Layout,
    textMeasurer: TextMeasurer,
    parsedPathCache: MutableMap<String, List<PathCommand>>,
    symbolColor: Color,
    unknownBg: Color,
    unknownFg: Color,
) {
    chart.layers.forEach { layer ->
        if (!layer.visible || layer.id in hiddenLayerIds) return@forEach
        layer.cells.forEach { cell ->
            val ring = cell.y
            val stitch = cell.x
            if (ring !in 0 until polar.rings) return@forEach
            val stitchesInRing = polar.stitchesPerRing.getOrNull(ring) ?: return@forEach
            if (stitch !in 0 until stitchesInRing) return@forEach

            val (pxD, pyD) = PolarCellLayout.cellCenter(stitch, ring, polar, layout)
            val px = pxD.toFloat()
            val py = pyD.toFloat()
            val sweep = 2.0 * PI / stitchesInRing
            val rCenter = layout.innerRadius + (ring + 0.5) * layout.ringThickness
            val chord = 2.0 * rCenter * sin(sweep / 2.0)
            val side = min(layout.ringThickness, chord).toFloat().coerceAtLeast(1f)
            val half = side / 2f
            val bounds = Rect(px - half, py - half, px + half, py + half)
            val radialDeg =
                (PolarCellLayout.cellRadialUpRotation(stitch, ring, polar) * 180.0 / PI).toFloat()

            rotate(degrees = radialDeg, pivot = Offset(px, py)) {
                val def = catalog.get(cell.symbolId)
                if (def == null) {
                    drawRect(color = unknownBg, topLeft = bounds.topLeft, size = bounds.size)
                    val measured =
                        textMeasurer.measure(
                            text = "?",
                            style =
                                TextStyle(
                                    color = unknownFg,
                                    fontSize = (bounds.height * 0.5f).coerceAtLeast(8f).sp,
                                    textAlign = TextAlign.Center,
                                ),
                        )
                    val tx = bounds.left + (bounds.width - measured.size.width) / 2f
                    val ty = bounds.top + (bounds.height - measured.size.height) / 2f
                    drawText(measured, topLeft = Offset(tx, ty))
                } else {
                    drawSymbolPath(
                        def = def,
                        bounds = bounds,
                        rotation = cell.rotation,
                        color = symbolColor,
                        strokeWidthPx = max(1f, side * 0.06f),
                        parsedPathCache = parsedPathCache,
                    )
                }
            }
        }
    }
}
