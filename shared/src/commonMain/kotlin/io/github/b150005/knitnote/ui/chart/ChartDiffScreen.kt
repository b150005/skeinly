package io.github.b150005.knitnote.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.b150005.knitnote.domain.chart.PolarCellLayout
import io.github.b150005.knitnote.domain.model.CellChange
import io.github.b150005.knitnote.domain.model.ChartDiff
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.LayerChange
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.label_diff_summary
import io.github.b150005.knitnote.generated.resources.label_initial_commit
import io.github.b150005.knitnote.generated.resources.label_layer_added
import io.github.b150005.knitnote.generated.resources.label_layer_hidden
import io.github.b150005.knitnote.generated.resources.label_layer_locked
import io.github.b150005.knitnote.generated.resources.label_layer_removed
import io.github.b150005.knitnote.generated.resources.label_layer_renamed
import io.github.b150005.knitnote.generated.resources.label_layer_shown
import io.github.b150005.knitnote.generated.resources.label_layer_unlocked
import io.github.b150005.knitnote.generated.resources.state_no_changes
import io.github.b150005.knitnote.generated.resources.title_chart_diff
import io.github.b150005.knitnote.ui.components.localized
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.max
import kotlin.math.min

private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 8f

/**
 * Phase 37.3 (ADR-013 §5 §6) — side-by-side diff between two chart revisions.
 *
 * Pan and zoom are SYNCHRONIZED across the two canvases — a single
 * `transformableState` writes to shared `scale` / `offsetX` / `offsetY` state
 * that both canvases consume via `graphicsLayer`. Without this, comparing
 * specific cells across panes would force the user to manually align both
 * sides, defeating the diff use case (ADR-013 Negative §3).
 *
 * Highlight palette is traffic-light + DELIBERATELY DISTINCT from the
 * per-segment overlay palette (ADR-013 Considered alternatives row 8) — the
 * two surfaces share no visual language so users do not have to disambiguate
 * "Color.Yellow means modified-cell here vs. wip-segment there".
 *
 * Initial commit (`diff.base == null`): the base pane shows
 * `label_initial_commit` text in lieu of an empty canvas. Every layer in the
 * target is a `LayerChange.Added` and the renderer paints each cell green.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartDiffScreen(
    baseRevisionId: String?,
    targetRevisionId: String,
    onBack: () -> Unit,
    viewModel: ChartDiffViewModel =
        koinViewModel { parametersOf(baseRevisionId, targetRevisionId) },
    catalog: SymbolCatalog = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(ChartDiffEvent.ClearError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_chart_diff)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("chartDiffScreen"),
        ) {
            when {
                state.isLoading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                state.diff == null ->
                    // Error path — Snackbar already showed the message; render
                    // an unobtrusive placeholder rather than a stack of nested
                    // states. The Snackbar dismiss restores no-op state.
                    Text(
                        text = "",
                        modifier = Modifier.align(Alignment.Center),
                    )

                else -> {
                    val diff = state.diff
                    if (diff != null) {
                        DiffContent(
                            diff = diff,
                            catalog = catalog,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffContent(
    diff: ChartDiff,
    catalog: SymbolCatalog,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        DiffSummaryRow(diff)
        if (diff.layerChanges.isNotEmpty()) {
            LayerChangesBanner(diff.layerChanges)
        }
        if (diff.hasNoChanges) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.state_no_changes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            DualCanvasPanel(
                diff = diff,
                catalog = catalog,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun DiffSummaryRow(diff: ChartDiff) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = { /* informational only */ },
            label = {
                Text(
                    stringResource(
                        Res.string.label_diff_summary,
                        diff.addedCellCount,
                        diff.modifiedCellCount,
                        diff.removedCellCount,
                    ),
                )
            },
            modifier = Modifier.testTag("diffSummaryChip"),
            colors = AssistChipDefaults.assistChipColors(),
        )
    }
}

@Composable
private fun LayerChangesBanner(changes: List<LayerChange>) {
    // Plain Column not LazyColumn — layer changes are O(layers), typically
    // single digits, and the banner sits inside an unconstrained Column where
    // a LazyColumn's infinite-height parent would crash at measure-time.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag("layerChangesBanner"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        changes.forEach { change ->
            Text(
                text = describeLayerChange(change),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun describeLayerChange(change: LayerChange): String =
    when (change) {
        is LayerChange.Added -> stringResource(Res.string.label_layer_added)
        is LayerChange.Removed -> stringResource(Res.string.label_layer_removed)
        is LayerChange.PropertyChanged -> {
            // Compose the most informative single-line summary. Rename takes
            // priority because it carries the layer's identity in both before
            // and after; visibility / locked toggles are secondary.
            //
            // Algorithm invariant: ChartDiffAlgorithm only emits PropertyChanged
            // when at least one of name / visible / locked differs, so the final
            // `else` is structurally unreachable. Surface it as `error(...)` so
            // any future algorithm bug fails loudly instead of rendering a
            // blank Text row that occupies layout space invisibly.
            when {
                change.before.name != change.after.name ->
                    stringResource(
                        Res.string.label_layer_renamed,
                        change.before.name,
                        change.after.name,
                    )
                change.before.visible && !change.after.visible ->
                    stringResource(Res.string.label_layer_hidden)
                !change.before.visible && change.after.visible ->
                    stringResource(Res.string.label_layer_shown)
                !change.before.locked && change.after.locked ->
                    stringResource(Res.string.label_layer_locked)
                change.before.locked && !change.after.locked ->
                    stringResource(Res.string.label_layer_unlocked)
                else -> error("PropertyChanged with no differing fields — algorithm invariant violated")
            }
        }
    }

@Composable
private fun DualCanvasPanel(
    diff: ChartDiff,
    catalog: SymbolCatalog,
    modifier: Modifier = Modifier,
) {
    // Synchronized pan/zoom — both panes read from the same shared state.
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val transformableState =
        rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
            offsetX += panChange.x
            offsetY += panChange.y
        }

    val baseChart = diff.base
    val targetChart = diff.target

    // Shared base→target classification per (layerId, x, y) so both panes paint
    // with consistent color semantics. Cells in added/removed layers are flagged
    // collectively (the entire layer's cells light up) per ADR-013 §5.
    val classification = remember(diff) { classifyCells(diff) }

    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    val symbolColor = MaterialTheme.colorScheme.onSurface
    val unknownBg = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    val unknownFg = MaterialTheme.colorScheme.error
    val highlightAlpha = 0.4f
    val addedColor = Color(red = 0.2f, green = 0.7f, blue = 0.3f, alpha = highlightAlpha)
    val modifiedColor = Color(red = 0.95f, green = 0.78f, blue = 0.1f, alpha = highlightAlpha)
    val removedColor = Color(red = 0.85f, green = 0.2f, blue = 0.2f, alpha = highlightAlpha)

    Row(
        modifier =
            modifier
                .transformable(state = transformableState),
    ) {
        // Base pane (left) — null → "Initial commit" placeholder.
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(4.dp)
                    .testTag("baseChartCanvas"),
            contentAlignment = Alignment.Center,
        ) {
            if (baseChart == null) {
                Text(
                    text = stringResource(Res.string.label_initial_commit),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                ChartDiffCanvas(
                    chart = baseChart,
                    catalog = catalog,
                    side = DiffSide.BASE,
                    classification = classification,
                    scale = scale,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    gridColor = gridColor,
                    symbolColor = symbolColor,
                    unknownBg = unknownBg,
                    unknownFg = unknownFg,
                    addedColor = addedColor,
                    modifiedColor = modifiedColor,
                    removedColor = removedColor,
                )
            }
        }
        // Target pane (right).
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(4.dp)
                    .testTag("targetChartCanvas"),
        ) {
            ChartDiffCanvas(
                chart = targetChart,
                catalog = catalog,
                side = DiffSide.TARGET,
                classification = classification,
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY,
                gridColor = gridColor,
                symbolColor = symbolColor,
                unknownBg = unknownBg,
                unknownFg = unknownFg,
                addedColor = addedColor,
                modifiedColor = modifiedColor,
                removedColor = removedColor,
            )
        }
    }
}

private enum class DiffSide { BASE, TARGET }

private enum class CellHighlight { ADDED, REMOVED, MODIFIED, UNCHANGED }

private data class CellKey(
    val layerId: String,
    val x: Int,
    val y: Int,
)

/**
 * Pre-classify every cell on both sides into a single map per `(layerId, x, y)`.
 * Cells in `LayerChange.Added` layers are flagged as ADDED on the target side;
 * cells in `LayerChange.Removed` layers are flagged as REMOVED on the base side.
 * Cells in shared layers route through the explicit `cellChanges` list.
 */
private data class DiffClassification(
    val baseHighlights: Map<CellKey, CellHighlight>,
    val targetHighlights: Map<CellKey, CellHighlight>,
)

private fun classifyCells(diff: ChartDiff): DiffClassification {
    val base = mutableMapOf<CellKey, CellHighlight>()
    val target = mutableMapOf<CellKey, CellHighlight>()

    diff.cellChanges.forEach { change ->
        when (change) {
            is CellChange.Added ->
                target[CellKey(change.layerId, change.cell.x, change.cell.y)] = CellHighlight.ADDED
            is CellChange.Removed ->
                base[CellKey(change.layerId, change.cell.x, change.cell.y)] = CellHighlight.REMOVED
            is CellChange.Modified -> {
                base[CellKey(change.layerId, change.before.x, change.before.y)] = CellHighlight.MODIFIED
                target[CellKey(change.layerId, change.after.x, change.after.y)] = CellHighlight.MODIFIED
            }
        }
    }

    diff.layerChanges.forEach { change ->
        when (change) {
            is LayerChange.Added ->
                change.layer.cells.forEach { cell ->
                    target[CellKey(change.layerId, cell.x, cell.y)] = CellHighlight.ADDED
                }
            is LayerChange.Removed ->
                change.layer.cells.forEach { cell ->
                    base[CellKey(change.layerId, cell.x, cell.y)] = CellHighlight.REMOVED
                }
            is LayerChange.PropertyChanged -> { /* No per-cell highlight from layer-property change. */ }
        }
    }

    return DiffClassification(baseHighlights = base, targetHighlights = target)
}

@Suppress("LongParameterList")
@Composable
private fun ChartDiffCanvas(
    chart: StructuredChart,
    catalog: SymbolCatalog,
    side: DiffSide,
    classification: DiffClassification,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    gridColor: Color,
    symbolColor: Color,
    unknownBg: Color,
    unknownFg: Color,
    addedColor: Color,
    modifiedColor: Color,
    removedColor: Color,
) {
    val textMeasurer = rememberTextMeasurer()
    val parsedPathCache =
        remember {
            mutableMapOf<String, List<io.github.b150005.knitnote.domain.symbol.PathCommand>>()
        }

    val highlights = if (side == DiffSide.BASE) classification.baseHighlights else classification.targetHighlights

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
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }

    Canvas(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
    ) {
        when (extents) {
            is ChartExtents.Rect ->
                drawRectDiff(
                    rect = extents,
                    chart = chart,
                    catalog = catalog,
                    highlights = highlights,
                    textMeasurer = textMeasurer,
                    parsedPathCache = parsedPathCache,
                    gridColor = gridColor,
                    symbolColor = symbolColor,
                    unknownBg = unknownBg,
                    unknownFg = unknownFg,
                    addedColor = addedColor,
                    modifiedColor = modifiedColor,
                    removedColor = removedColor,
                )
            is ChartExtents.Polar -> {
                val layout = polarLayoutFor(size.width, size.height, extents)
                drawPolarGrid(extents, layout, gridColor)
                drawPolarDiffOverlay(
                    polar = extents,
                    chart = chart,
                    highlights = highlights,
                    layout = layout,
                    addedColor = addedColor,
                    modifiedColor = modifiedColor,
                    removedColor = removedColor,
                )
                drawPolarCells(
                    polar = extents,
                    chart = chart,
                    hiddenLayerIds = emptySet(),
                    catalog = catalog,
                    layout = layout,
                    textMeasurer = textMeasurer,
                    parsedPathCache = parsedPathCache,
                    symbolColor = symbolColor,
                    unknownBg = unknownBg,
                    unknownFg = unknownFg,
                )
            }
        }
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawRectDiff(
    rect: ChartExtents.Rect,
    chart: StructuredChart,
    catalog: SymbolCatalog,
    highlights: Map<CellKey, CellHighlight>,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    parsedPathCache: MutableMap<String, List<io.github.b150005.knitnote.domain.symbol.PathCommand>>,
    gridColor: Color,
    symbolColor: Color,
    unknownBg: Color,
    unknownFg: Color,
    addedColor: Color,
    modifiedColor: Color,
    removedColor: Color,
) {
    val gridWidth = (rect.maxX - rect.minX + 1)
    val gridHeight = (rect.maxY - rect.minY + 1)
    val layout = computeDiffLayout(size, gridWidth, gridHeight)
    val cellSize = layout.cellSize
    val originX = layout.originX
    val originY = layout.originY
    val drawW = cellSize * gridWidth
    val drawH = cellSize * gridHeight

    // Grid background.
    for (gx in 0..gridWidth) {
        val x = originX + gx * cellSize
        drawLine(color = gridColor, start = Offset(x, originY), end = Offset(x, originY + drawH), strokeWidth = 1f)
    }
    for (gy in 0..gridHeight) {
        val y = originY + gy * cellSize
        drawLine(color = gridColor, start = Offset(originX, y), end = Offset(originX + drawW, y), strokeWidth = 1f)
    }

    chart.layers.forEach { layer ->
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
            // Highlight UNDER the glyph so the symbol stays legible (mirrors
            // ADR-011 §2 overlay-under-glyph order). Wide cells: cellScreenRect
            // already returns a rect spanning [x, x+width) so the highlight
            // covers all columns the cell occupies.
            val key = CellKey(layer.id, cell.x, cell.y)
            highlights[key]?.let { tag ->
                val color =
                    when (tag) {
                        CellHighlight.ADDED -> addedColor
                        CellHighlight.REMOVED -> removedColor
                        CellHighlight.MODIFIED -> modifiedColor
                        CellHighlight.UNCHANGED -> null
                    }
                if (color != null) {
                    drawRect(color = color, topLeft = bounds.topLeft, size = bounds.size)
                }
            }

            val def = catalog.get(cell.symbolId)
            if (def == null) {
                // Match ChartViewerScreen + drawPolarCells unknown-symbol render
                // (filled bg + "?" glyph) so the diff view's two panes don't
                // diverge from each other or from the read-only viewer when a
                // pattern references a symbol the catalog does not know.
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
                    strokeWidthPx = max(1f, cellSize * 0.06f),
                    parsedPathCache = parsedPathCache,
                )
            }
        }
    }
}

private data class DiffCanvasLayout(
    val cellSize: Float,
    val originX: Float,
    val originY: Float,
)

private fun computeDiffLayout(
    size: Size,
    gridWidth: Int,
    gridHeight: Int,
): DiffCanvasLayout {
    val cellSize =
        min(size.width / gridWidth.toFloat(), size.height / gridHeight.toFloat()).coerceAtLeast(1f)
    val drawW = cellSize * gridWidth
    val drawH = cellSize * gridHeight
    return DiffCanvasLayout(
        cellSize = cellSize,
        originX = (size.width - drawW) / 2f,
        originY = (size.height - drawH) / 2f,
    )
}

@Suppress("LongParameterList")
private fun DrawScope.drawPolarDiffOverlay(
    polar: ChartExtents.Polar,
    chart: StructuredChart,
    highlights: Map<CellKey, CellHighlight>,
    layout: PolarCellLayout.Layout,
    addedColor: Color,
    modifiedColor: Color,
    removedColor: Color,
) {
    chart.layers.forEach { layer ->
        layer.cells.forEach { cell ->
            val ring = cell.y
            val stitch = cell.x
            if (ring !in 0 until polar.rings) return@forEach
            val stitchesInRing = polar.stitchesPerRing.getOrNull(ring) ?: return@forEach
            if (stitch !in 0 until stitchesInRing) return@forEach

            val highlight = highlights[CellKey(layer.id, cell.x, cell.y)] ?: return@forEach
            val color =
                when (highlight) {
                    CellHighlight.ADDED -> addedColor
                    CellHighlight.MODIFIED -> modifiedColor
                    CellHighlight.REMOVED -> removedColor
                    CellHighlight.UNCHANGED -> return@forEach
                }
            val wedge = PolarCellLayout.wedgeFor(stitch, ring, polar, layout)
            drawPath(path = polarWedgePath(wedge, layout), color = color)
        }
    }
}
