package io.github.b150005.knitnote.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.b150005.knitnote.domain.chart.CellBounds
import io.github.b150005.knitnote.domain.chart.GridHitTest
import io.github.b150005.knitnote.domain.chart.SymbolRenderTransform
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.SegmentState
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.state_empty_chart
import io.github.b150005.knitnote.generated.resources.state_no_structured_chart
import io.github.b150005.knitnote.generated.resources.title_chart_viewer
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.max
import kotlin.math.min

private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 8f

/**
 * Reserved left-gutter width (in raw pixels, pre-graphicsLayer scale) for
 * rect-chart row-number labels. Sized for up to 3-digit rows at the label's
 * font size. Long-press in this band routes to `MarkRowDone(row)` per
 * ADR-011 §4.
 */
private const val RECT_ROW_LABEL_GUTTER_PX = 28f

/**
 * Half-width of the polar ring-label hit rectangle, measured horizontally
 * from the 12 o'clock diameter line. Narrow enough to stay out of stitch-0
 * and stitch-N-1 wedges at typical ring thicknesses; wide enough to land a
 * finger tap.
 */
private const val POLAR_RING_LABEL_HALF_W_PX = 16f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartViewerScreen(
    patternId: String,
    onBack: () -> Unit,
    projectId: String? = null,
    viewModel: ChartViewerViewModel =
        koinViewModel { parametersOf(patternId, projectId) },
    catalog: SymbolCatalog = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_chart_viewer)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("backButton")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surface),
        ) {
            val chart = state.chart
            val errorMessage = state.errorMessage
            when {
                state.isLoading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                errorMessage != null ->
                    Text(
                        text = errorMessage,
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                chart == null ->
                    Text(
                        text = stringResource(Res.string.state_no_structured_chart),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                else ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (chart.layers.isNotEmpty()) {
                            LayerChips(
                                layers = chart.layers,
                                hiddenLayerIds = state.hiddenLayerIds,
                                onToggle = { id ->
                                    viewModel.onEvent(ChartViewerEvent.ToggleLayer(id))
                                },
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        ChartCanvas(
                            chart = chart,
                            catalog = catalog,
                            hiddenLayerIds = state.hiddenLayerIds,
                            segments = state.segments,
                            onTapCell = { layerId, x, y ->
                                viewModel.onEvent(ChartViewerEvent.TapCell(layerId, x, y))
                            },
                            onLongPressCell = { layerId, x, y ->
                                viewModel.onEvent(ChartViewerEvent.LongPressCell(layerId, x, y))
                            },
                            onMarkRowDone = { row ->
                                viewModel.onEvent(ChartViewerEvent.MarkRowDone(row))
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
            }
        }
    }
}

@Composable
private fun LayerChips(
    layers: List<ChartLayer>,
    hiddenLayerIds: Set<String>,
    onToggle: (String) -> Unit,
) {
    LazyRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        items(layers, key = { it.id }) { layer ->
            val visible = layer.id !in hiddenLayerIds
            FilterChip(
                selected = visible,
                onClick = { onToggle(layer.id) },
                label = { Text(layer.name) },
                modifier = Modifier.padding(horizontal = 4.dp),
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun ChartCanvas(
    chart: StructuredChart,
    catalog: SymbolCatalog,
    hiddenLayerIds: Set<String>,
    segments: Map<SegmentKey, SegmentState>,
    onTapCell: (layerId: String, x: Int, y: Int) -> Unit,
    onLongPressCell: (layerId: String, x: Int, y: Int) -> Unit,
    onMarkRowDone: (row: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val transformableState =
        rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
            offsetX += panChange.x
            offsetY += panChange.y
        }

    val textMeasurer = rememberTextMeasurer()
    val parsedPathCache =
        remember {
            mutableMapOf<String, List<io.github.b150005.knitnote.domain.symbol.PathCommand>>()
        }
    val haptics = LocalHapticFeedback.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    val symbolColor = MaterialTheme.colorScheme.onSurface
    val unknownBg = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    val unknownFg = MaterialTheme.colorScheme.error
    val parameterColor = MaterialTheme.colorScheme.primary
    // Per PRD AC-1.1: done → filled onSurface @ 20%, wip → 2dp outline primary.
    val segmentDoneColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val segmentWipColor = MaterialTheme.colorScheme.primary
    val rowLabelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val extents = chart.extents
    val isEmpty =
        when (extents) {
            is ChartExtents.Rect -> extents.maxX < extents.minX || extents.maxY < extents.minY
            is ChartExtents.Polar ->
                extents.rings <= 0 ||
                    extents.stitchesPerRing.size < extents.rings ||
                    extents.stitchesPerRing.any { it <= 0 }
        }
    if (isEmpty) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.state_empty_chart),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    // Pre-filter layers that actually get drawn so tap hit-testing uses the
    // same set. Phase 35.2f: tap-route excludes locked layers per ADR-011 §5
    // addendum decision 1(c) — overlay still paints (drawRectChart /
    // drawPolarSegmentOverlay iterate over chart.layers directly), but
    // segment-progress taps on a locked-layer cell are silently dropped.
    val visibleLayers =
        chart.layers.filter { it.visible && it.id !in hiddenLayerIds }
    val tapTargetLayers = visibleLayers.filter { !it.locked }

    Canvas(
        modifier =
            modifier
                .fillMaxSize()
                .testTag("segmentOverlay")
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ).transformable(state = transformableState)
                // Rekey only on extents + layer visibility — deps that actually
                // change hit-test geometry. PRD Q-3: co-composing detectTapGestures
                // with transformable() is the established pattern.
                .pointerInput(extents, tapTargetLayers.map { it.id }) {
                    detectTapGestures(
                        onTap = { offset ->
                            val hit =
                                when (extents) {
                                    is ChartExtents.Rect ->
                                        resolveHit(offset, size, extents, tapTargetLayers)
                                    is ChartExtents.Polar ->
                                        resolvePolarHit(offset, size, extents, tapTargetLayers)
                                }
                            if (hit != null) {
                                // Top-most visible AND unlocked layer wins. Locked layers
                                // were filtered out of [tapTargetLayers] above so no
                                // segment-progress event can dispatch against them.
                                onTapCell(hit.layerId, hit.x, hit.y)
                            }
                        },
                        onLongPress = { offset ->
                            // Phase 35.2d: label hits take priority over cell hits so
                            // long-press on the row/ring number dispatches MarkRowDone.
                            val labelRow =
                                when (extents) {
                                    is ChartExtents.Rect ->
                                        resolveRowLabelHit(offset, size, extents)
                                    is ChartExtents.Polar ->
                                        resolvePolarRingLabelHit(offset, size, extents)
                                }
                            if (labelRow != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onMarkRowDone(labelRow)
                                return@detectTapGestures
                            }
                            val hit =
                                when (extents) {
                                    is ChartExtents.Rect ->
                                        resolveHit(offset, size, extents, tapTargetLayers)
                                    is ChartExtents.Polar ->
                                        resolvePolarHit(offset, size, extents, tapTargetLayers)
                                }
                            if (hit != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPressCell(hit.layerId, hit.x, hit.y)
                            }
                        },
                    )
                },
    ) {
        when (extents) {
            is ChartExtents.Rect ->
                drawRectChart(
                    rect = extents,
                    chart = chart,
                    catalog = catalog,
                    hiddenLayerIds = hiddenLayerIds,
                    segments = segments,
                    textMeasurer = textMeasurer,
                    parsedPathCache = parsedPathCache,
                    gridColor = gridColor,
                    symbolColor = symbolColor,
                    unknownBg = unknownBg,
                    unknownFg = unknownFg,
                    parameterColor = parameterColor,
                    segmentDoneColor = segmentDoneColor,
                    segmentWipColor = segmentWipColor,
                    rowLabelColor = rowLabelColor,
                )
            is ChartExtents.Polar -> {
                val layout = polarLayoutFor(size.width, size.height, extents)
                drawPolarGrid(extents, layout, gridColor)
                drawPolarSegmentOverlay(
                    polar = extents,
                    chart = chart,
                    hiddenLayerIds = hiddenLayerIds,
                    segments = segments,
                    layout = layout,
                    doneColor = segmentDoneColor,
                    wipColor = segmentWipColor,
                    wipStrokeWidthPx = 2.dp.toPx(),
                )
                // Glyphs paint on top of the overlay per ADR-011 §2 (matches rect
                // AC-1.2 "overlay under glyph"). Parametric slot text is deferred
                // to Phase 35.2+ along with the polar editor.
                drawPolarCells(
                    polar = extents,
                    chart = chart,
                    hiddenLayerIds = hiddenLayerIds,
                    catalog = catalog,
                    layout = layout,
                    textMeasurer = textMeasurer,
                    parsedPathCache = parsedPathCache,
                    symbolColor = symbolColor,
                    unknownBg = unknownBg,
                    unknownFg = unknownFg,
                )
                drawPolarRingLabels(
                    polar = extents,
                    layout = layout,
                    textMeasurer = textMeasurer,
                    color = rowLabelColor,
                )
            }
        }
    }
}

@Suppress("LongParameterList")
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRectChart(
    rect: ChartExtents.Rect,
    chart: StructuredChart,
    catalog: SymbolCatalog,
    hiddenLayerIds: Set<String>,
    segments: Map<SegmentKey, SegmentState>,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    parsedPathCache: MutableMap<String, List<io.github.b150005.knitnote.domain.symbol.PathCommand>>,
    gridColor: Color,
    symbolColor: Color,
    unknownBg: Color,
    unknownFg: Color,
    parameterColor: Color,
    segmentDoneColor: Color,
    segmentWipColor: Color,
    rowLabelColor: Color,
) {
    val gridWidth = (rect.maxX - rect.minX + 1)
    val gridHeight = (rect.maxY - rect.minY + 1)
    // Route through computeViewerLayout so draw and hit-test share a single
    // cellSize/origin math — required after Phase 35.2d reserved a left gutter.
    val layout = computeViewerLayout(size, rect)
    val cellSize = layout.cellSize
    val originX = layout.originX
    val originY = layout.originY
    val drawW = cellSize * gridWidth
    val drawH = cellSize * gridHeight

    // Grid background
    for (gx in 0..gridWidth) {
        val x = originX + gx * cellSize
        drawLine(
            color = gridColor,
            start = Offset(x, originY),
            end = Offset(x, originY + drawH),
            strokeWidth = 1f,
        )
    }
    for (gy in 0..gridHeight) {
        val y = originY + gy * cellSize
        drawLine(
            color = gridColor,
            start = Offset(originX, y),
            end = Offset(originX + drawW, y),
            strokeWidth = 1f,
        )
    }

    chart.layers.forEach { layer ->
        if (!layer.visible || layer.id in hiddenLayerIds) return@forEach
        layer.cells.forEach { cell ->
            val bounds =
                cellScreenRect(
                    cell = cell,
                    rect = rect,
                    gridHeight = gridHeight,
                    cellSize = cellSize,
                    originX = originX,
                    originY = originY,
                )
            // Paint the segment overlay UNDER the symbol glyph per PRD AC-1.2.
            when (segments[SegmentKey(layer.id, cell.x, cell.y)]) {
                SegmentState.DONE ->
                    drawRect(
                        color = segmentDoneColor,
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                    )
                SegmentState.WIP ->
                    drawRect(
                        color = segmentWipColor,
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                null -> { /* implicit todo — no overlay */ }
            }
            val def = catalog.get(cell.symbolId)
            if (def == null) {
                drawRect(
                    color = unknownBg,
                    topLeft = bounds.topLeft,
                    size = bounds.size,
                )
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
                    strokeWidthPx = max(1f, cellSize * 0.06f),
                    parsedPathCache = parsedPathCache,
                )
                drawParameterSlots(
                    def = def,
                    cell = cell,
                    bounds = bounds,
                    cellSize = cellSize,
                    textMeasurer = textMeasurer,
                    color = parameterColor,
                )
            }
        }
    }

    // Row-number labels in the reserved left gutter. Labels are 1-indexed
    // from the bottom row per chart y-up convention (docs/en/chart-coordinates.md)
    // and locale-independent digits, so no i18n involvement. Reading-convention-
    // aware orientation is out-of-scope for Phase 35.2d.
    val labelFontPx = (cellSize * 0.35f).coerceAtLeast(8f)
    val gutterCenterX = originX - RECT_ROW_LABEL_GUTTER_PX / 2f
    for (gy in 0 until gridHeight) {
        val rowCenterY = originY + (gridHeight - gy - 0.5f) * cellSize
        val rowNumber = gy + 1
        val measured =
            textMeasurer.measure(
                text = rowNumber.toString(),
                style =
                    TextStyle(
                        color = rowLabelColor,
                        fontSize = labelFontPx.sp,
                        textAlign = TextAlign.Center,
                    ),
            )
        val tx = gutterCenterX - measured.size.width / 2f
        val ty = rowCenterY - measured.size.height / 2f
        drawText(measured, topLeft = Offset(tx, ty))
    }
}

private data class ViewerCanvasLayout(
    val cellSize: Float,
    val originX: Float,
    val originY: Float,
)

private fun computeViewerLayout(
    size: Size,
    rect: ChartExtents.Rect,
): ViewerCanvasLayout {
    val gridWidth = (rect.maxX - rect.minX + 1)
    val gridHeight = (rect.maxY - rect.minY + 1)
    // Reserve the left gutter for row-number labels (Phase 35.2d). The grid
    // centers in the remaining width so small charts still look balanced.
    val availableW = (size.width - RECT_ROW_LABEL_GUTTER_PX).coerceAtLeast(1f)
    val cellSize =
        min(
            availableW / gridWidth.toFloat(),
            size.height / gridHeight.toFloat(),
        ).coerceAtLeast(1f)
    val drawW = cellSize * gridWidth
    val drawH = cellSize * gridHeight
    return ViewerCanvasLayout(
        cellSize = cellSize,
        originX = RECT_ROW_LABEL_GUTTER_PX + (availableW - drawW) / 2f,
        originY = (size.height - drawH) / 2f,
    )
}

/**
 * Returns the id of the top-most visible layer that has a drawn [ChartCell]
 * at ([x], [y]), or null if no visible layer has a cell there. "Top-most" is
 * interpreted as the last entry in [layers] per the existing rendering order.
 */
private fun topmostLayerAt(
    layers: List<ChartLayer>,
    x: Int,
    y: Int,
): String? {
    for (i in layers.indices.reversed()) {
        val layer = layers[i]
        if (layer.cells.any { it.x == x && it.y == y }) return layer.id
    }
    return null
}

private data class ResolvedHit(
    val layerId: String,
    val x: Int,
    val y: Int,
)

private fun resolveHit(
    offset: Offset,
    sizePx: androidx.compose.ui.unit.IntSize,
    rect: ChartExtents.Rect,
    visibleLayers: List<ChartLayer>,
): ResolvedHit? {
    val layout = computeViewerLayout(Size(sizePx.width.toFloat(), sizePx.height.toFloat()), rect)
    val cell =
        GridHitTest.hitTest(
            screenX = offset.x.toDouble(),
            screenY = offset.y.toDouble(),
            extents = rect,
            cellSize = layout.cellSize.toDouble(),
            originX = layout.originX.toDouble(),
            originY = layout.originY.toDouble(),
        ) ?: return null
    val layerId = topmostLayerAt(visibleLayers, cell.x, cell.y) ?: return null
    return ResolvedHit(layerId, cell.x, cell.y)
}

/**
 * Hit-test a tap against the reserved row-label gutter. Returns the chart
 * y-coordinate of the row (matching `ChartCell.y` storage, offset by
 * `rect.minY` for non-zero-origin extents) when the tap falls within the
 * gutter band aligned vertically with the grid, or null otherwise.
 *
 * The gutter spans screen-x `[0, originX)`. Rows are 1-indexed upward from
 * the bottom per chart y-up convention; the returned value is a chart-y
 * coordinate, not a 1-indexed label.
 */
private fun resolveRowLabelHit(
    offset: Offset,
    sizePx: androidx.compose.ui.unit.IntSize,
    rect: ChartExtents.Rect,
): Int? {
    val layout = computeViewerLayout(Size(sizePx.width.toFloat(), sizePx.height.toFloat()), rect)
    if (offset.x < 0f || offset.x >= layout.originX) return null
    val gridHeight = rect.maxY - rect.minY + 1
    val drawH = layout.cellSize * gridHeight
    if (offset.y < layout.originY) return null
    if (offset.y >= layout.originY + drawH) return null
    val rowFromTop = kotlin.math.floor((offset.y - layout.originY) / layout.cellSize).toInt()
    val gy = gridHeight - 1 - rowFromTop
    if (gy !in 0 until gridHeight) return null
    return rect.minY + gy
}

/**
 * Hit-test a tap against polar ring-number labels, painted along the 12
 * o'clock diameter above the center at each ring's mid-radius. Returns the
 * ring index (0 = innermost, matching `ChartCell.y` storage) when the tap
 * lands within the narrow hit rectangle straddling the 12 o'clock line, or
 * null otherwise.
 *
 * The hit region is orthogonal to the stitch wedges so ring-label taps can
 * never be ambiguous with stitch-0 / stitch-N-1 long-press for `MarkSegmentDone`.
 */
private fun resolvePolarRingLabelHit(
    offset: Offset,
    sizePx: androidx.compose.ui.unit.IntSize,
    polar: ChartExtents.Polar,
): Int? {
    if (polar.rings <= 0) return null
    val layout = polarLayoutFor(sizePx.width.toFloat(), sizePx.height.toFloat(), polar)
    val cx = layout.cx.toFloat()
    val cy = layout.cy.toFloat()
    if (offset.x < cx - POLAR_RING_LABEL_HALF_W_PX) return null
    if (offset.x >= cx + POLAR_RING_LABEL_HALF_W_PX) return null
    if (offset.y >= cy) return null
    val dy = (cy - offset.y).toDouble()
    val innerR = layout.innerRadius
    val ringThickness = layout.ringThickness
    val outerR = innerR + polar.rings * ringThickness
    if (dy < innerR || dy >= outerR) return null
    val ring = kotlin.math.floor((dy - innerR) / ringThickness).toInt()
    return ring.coerceIn(0, polar.rings - 1)
}

/**
 * Paint ring-number labels along the 12 o'clock diameter at each ring's
 * mid-radius. Ring 0 (innermost) renders as "1" per the 1-indexed display
 * convention matching rect row labels. Locale-independent digits, no i18n.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolarRingLabels(
    polar: ChartExtents.Polar,
    layout: io.github.b150005.knitnote.domain.chart.PolarCellLayout.Layout,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    color: Color,
) {
    if (polar.rings <= 0) return
    val cx = layout.cx.toFloat()
    val cy = layout.cy.toFloat()
    val fontPx = (layout.ringThickness.toFloat() * 0.4f).coerceAtLeast(10f)
    for (ring in 0 until polar.rings) {
        val rCenter = layout.innerRadius + (ring + 0.5) * layout.ringThickness
        val py = cy - rCenter.toFloat()
        val measured =
            textMeasurer.measure(
                text = (ring + 1).toString(),
                style =
                    TextStyle(
                        color = color,
                        fontSize = fontPx.sp,
                        textAlign = TextAlign.Center,
                    ),
            )
        val tx = cx - measured.size.width / 2f
        val ty = py - measured.size.height / 2f
        drawText(measured, topLeft = Offset(tx, ty))
    }
}

/**
 * Polar analog of [resolveHit]. Shares the `topmostLayerAt` layer-resolution
 * semantics — in polar, `cell.x = stitch` and `cell.y = ring`, matching the
 * storage convention established by the Phase 35.1b/c overlay + glyph passes.
 */
private fun resolvePolarHit(
    offset: Offset,
    sizePx: androidx.compose.ui.unit.IntSize,
    polar: ChartExtents.Polar,
    visibleLayers: List<ChartLayer>,
): ResolvedHit? {
    val layout = polarLayoutFor(sizePx.width.toFloat(), sizePx.height.toFloat(), polar)
    val cell =
        GridHitTest.hitTestPolar(
            screenX = offset.x.toDouble(),
            screenY = offset.y.toDouble(),
            extents = polar,
            layout = layout,
        ) ?: return null
    val layerId = topmostLayerAt(visibleLayers, cell.x, cell.y) ?: return null
    return ResolvedHit(layerId, cell.x, cell.y)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawParameterSlots(
    def: io.github.b150005.knitnote.domain.symbol.SymbolDefinition,
    cell: ChartCell,
    bounds: Rect,
    cellSize: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    color: Color,
) {
    if (def.parameterSlots.isEmpty()) return
    val cellBounds =
        CellBounds(
            left = bounds.left.toDouble(),
            top = bounds.top.toDouble(),
            right = bounds.right.toDouble(),
            bottom = bounds.bottom.toDouble(),
        )
    def.parameterSlots.forEach { slot ->
        val value = cell.symbolParameters[slot.key] ?: slot.defaultValue ?: return@forEach
        val anchor = SymbolRenderTransform.mapPoint(slot.x, slot.y, cellBounds, cell.rotation)
        val measured =
            textMeasurer.measure(
                text = value,
                style =
                    TextStyle(
                        color = color,
                        fontSize = (cellSize * 0.35f).coerceAtLeast(8f).sp,
                    ),
            )
        val tx = anchor.x.toFloat() - measured.size.width / 2f
        val ty = anchor.y.toFloat() - measured.size.height / 2f
        drawText(measured, topLeft = Offset(tx, ty))
    }
}
