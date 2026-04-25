package io.github.b150005.knitnote.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import io.github.b150005.knitnote.domain.symbol.PathCommand
import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_view_chart_thumbnail
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min

private val DEFAULT_THUMBNAIL_SIZE = 64.dp

/**
 * Phase 36.4 (ADR-012 §5): chart-preview thumbnail rendered live by reusing
 * the same draw helpers as [ChartViewerScreen] at fixed scale, with no
 * gestures, no overlays, and no row/ring labels. Tap routes to the read-only
 * chart viewer via [onClick]. Fetches the chart document on first composition
 * (and again when [patternId] changes).
 *
 * The cached-PNG-thumbnail-column alternative is explicitly deferred per
 * ADR-012 §8 — live render is correct (always matches current chart state)
 * and Discovery's [androidx.compose.foundation.lazy.LazyColumn] keeps the
 * fetch demand-driven (off-screen rows do not fetch).
 */
@Composable
fun ChartThumbnail(
    patternId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = DEFAULT_THUMBNAIL_SIZE,
    structuredChartRepository: StructuredChartRepository = koinInject(),
    catalog: SymbolCatalog = koinInject(),
) {
    var chart by remember(patternId) { mutableStateOf<StructuredChart?>(null) }
    LaunchedEffect(patternId) {
        chart =
            try {
                structuredChartRepository.getByPatternId(patternId)
            } catch (e: CancellationException) {
                // Same cancellation-safety pattern as ForkPublicPatternUseCase
                // (Phase 36.3): when the composable leaves composition mid-fetch
                // (routine for off-screen LazyColumn rows), the cancellation
                // signal must propagate so coroutine cleanup stays cooperative.
                throw e
            } catch (e: Exception) {
                // The Discovery list-fetch already named which patterns have charts;
                // a per-row fetch failure here is a transient that shouldn't block
                // the rest of Discovery — fall back to the placeholder background.
                null
            }
    }

    val a11yLabel = stringResource(Res.string.action_view_chart_thumbnail)
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val symbolColor = MaterialTheme.colorScheme.onSurface
    val unknownBg = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    val unknownFg = MaterialTheme.colorScheme.error
    val textMeasurer = rememberTextMeasurer()
    val parsedPathCache = remember { mutableMapOf<String, List<PathCommand>>() }

    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .background(placeholderColor)
                .clickable(onClick = onClick)
                .semantics { contentDescription = a11yLabel }
                .testTag("chartThumbnail_$patternId"),
    ) {
        val current = chart ?: return@Box
        Canvas(modifier = Modifier.size(size)) {
            when (val extents = current.extents) {
                is ChartExtents.Rect -> {
                    if (extents.maxX < extents.minX || extents.maxY < extents.minY) return@Canvas
                    drawRectThumbnail(
                        rect = extents,
                        chart = current,
                        catalog = catalog,
                        parsedPathCache = parsedPathCache,
                        gridColor = gridColor,
                        symbolColor = symbolColor,
                        unknownBg = unknownBg,
                    )
                }
                is ChartExtents.Polar -> {
                    val rings = extents.rings
                    val perRing = extents.stitchesPerRing
                    if (rings <= 0 || perRing.size < rings || perRing.any { it <= 0 }) return@Canvas
                    val layout = polarLayoutFor(this.size.width, this.size.height, extents)
                    drawPolarGrid(extents, layout, gridColor)
                    drawPolarCells(
                        polar = extents,
                        chart = current,
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
}

/**
 * Stripped-down version of `drawRectChart` for thumbnail render: full canvas
 * (no left gutter), no row labels, no segment overlay, no parameter slot text.
 * Iterates only visible layers, paints grid + symbol glyphs.
 */
@Suppress("LongParameterList")
private fun DrawScope.drawRectThumbnail(
    rect: ChartExtents.Rect,
    chart: StructuredChart,
    catalog: SymbolCatalog,
    parsedPathCache: MutableMap<String, List<PathCommand>>,
    gridColor: Color,
    symbolColor: Color,
    unknownBg: Color,
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
        if (!layer.visible) return@forEach
        layer.cells.forEach { cell ->
            val bounds: Rect =
                cellScreenRect(
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
