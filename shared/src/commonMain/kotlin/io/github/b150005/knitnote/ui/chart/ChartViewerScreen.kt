package io.github.b150005.knitnote.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.b150005.knitnote.domain.chart.CellBounds
import io.github.b150005.knitnote.domain.chart.SymbolRenderTransform
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.max
import kotlin.math.min

private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 8f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartViewerScreen(
    patternId: String,
    onBack: () -> Unit,
    viewModel: ChartViewerViewModel = koinViewModel { parametersOf(patternId) },
    catalog: SymbolCatalog = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chart") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        text = "No structured chart available",
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
    // Cache parsed SVG path commands across recompositions and frames. Symbol path
    // data is immutable and keyed by id, so a never-evicted map is safe and avoids
    // re-parsing the same string per cell on every pan/zoom redraw.
    val parsedPathCache =
        remember {
            mutableMapOf<String, List<io.github.b150005.knitnote.domain.symbol.PathCommand>>()
        }
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    val symbolColor = MaterialTheme.colorScheme.onSurface
    val unknownBg = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    val unknownFg = MaterialTheme.colorScheme.error
    val parameterColor = MaterialTheme.colorScheme.primary

    val rect = (chart.extents as? ChartExtents.Rect) ?: return
    if (rect.maxX < rect.minX || rect.maxY < rect.minY) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Empty chart", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Canvas(
        modifier =
            modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ).transformable(state = transformableState),
    ) {
        val gridWidth = (rect.maxX - rect.minX + 1)
        val gridHeight = (rect.maxY - rect.minY + 1)
        val cellSize =
            min(
                size.width / gridWidth.toFloat(),
                size.height / gridHeight.toFloat(),
            ).coerceAtLeast(1f)
        val drawW = cellSize * gridWidth
        val drawH = cellSize * gridHeight
        val originX = (size.width - drawW) / 2f
        val originY = (size.height - drawH) / 2f

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
                    cellRect(
                        cell = cell,
                        rect = rect,
                        gridHeight = gridHeight,
                        cellSize = cellSize,
                        originX = originX,
                        originY = originY,
                    )
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
    }
}

private fun cellRect(
    cell: ChartCell,
    rect: ChartExtents.Rect,
    gridHeight: Int,
    cellSize: Float,
    originX: Float,
    originY: Float,
): Rect {
    // Chart coordinates: y increases upward, origin at bottom-left.
    // Screen: y increases downward. Flip happens here so the rest of the renderer
    // works in y-down space.
    val gx = cell.x - rect.minX
    val gy = cell.y - rect.minY
    val left = originX + gx * cellSize
    val bottom = originY + (gridHeight - gy) * cellSize
    val top = bottom - cell.height * cellSize
    val right = left + cell.width * cellSize
    return Rect(left = left, top = top, right = right, bottom = bottom)
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
