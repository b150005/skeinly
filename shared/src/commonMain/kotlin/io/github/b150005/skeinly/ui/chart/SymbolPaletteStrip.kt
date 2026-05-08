package io.github.b150005.skeinly.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.b150005.skeinly.domain.symbol.SymbolCategory
import io.github.b150005.skeinly.domain.symbol.SymbolDefinition
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.title_locked_symbol
import org.jetbrains.compose.resources.stringResource

/**
 * Horizontal palette strip for the chart editor. Shows an "Eraser" cell followed
 * by the current category's symbols. Category switcher sits above the strip.
 */
@Composable
fun SymbolPaletteStrip(
    selectedCategory: SymbolCategory,
    availableCategories: List<SymbolCategory>,
    symbols: List<SymbolDefinition>,
    selectedSymbolId: String?,
    onCategorySelected: (SymbolCategory) -> Unit,
    onSymbolSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    // Phase 41.4 (ADR-016 §5.2) — Pro symbols the user is not entitled to
    // use. Rendered after the entitled symbols with a lock badge overlay.
    // Tap routes through [onLockedSymbolTap] which the host wires to the
    // existing paywall-request channel on `ChartEditorViewModel`. Empty
    // by default so existing call sites keep working without edit.
    lockedProSymbols: List<SymbolDefinition> = emptyList(),
    onLockedSymbolTap: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            items(availableCategories, key = { it.name }) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { onCategorySelected(category) },
                    label = { Text(category.enLabel, fontSize = 12.sp) },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        LazyRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(horizontal = 8.dp)
                    .testTag("symbolPalette"),
        ) {
            item {
                EraserCell(
                    selected = selectedSymbolId == null,
                    onClick = { onSymbolSelected(null) },
                )
            }
            items(symbols, key = { it.id }) { def ->
                PaletteSymbolCell(
                    def = def,
                    selected = def.id == selectedSymbolId,
                    onClick = { onSymbolSelected(def.id) },
                )
            }
            // Locked-pro symbols render as a separate trailing section so
            // the visual split between "available" / "Pro" reads cleanly.
            // Each locked cell is dimmed (alpha) + carries a Lock icon
            // overlay. Tap dispatches the paywall request rather than the
            // selection event (no point selecting a symbol the user can't
            // place).
            items(lockedProSymbols, key = { "locked_${it.id}" }) { def ->
                LockedPaletteSymbolCell(
                    def = def,
                    onClick = onLockedSymbolTap,
                )
            }
        }
    }
}

@Composable
private fun EraserCell(
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier =
            Modifier
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .testTag("paletteEraser"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Clear,
            contentDescription = "Eraser",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PaletteSymbolCell(
    def: SymbolDefinition,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier =
            Modifier
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .testTag("paletteSymbol_${def.id}"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(8.dp),
        ) {
            val bounds = Rect(left = 0f, top = 0f, right = size.width, bottom = size.height)
            drawSymbolPath(
                def = def,
                bounds = bounds,
                color = surface,
                strokeWidthPx = 2f,
            )
        }
    }
}

@Composable
private fun LockedPaletteSymbolCell(
    def: SymbolDefinition,
    onClick: () -> Unit,
) {
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.onSurface
    val lockedDescription = stringResource(Res.string.title_locked_symbol)
    Box(
        modifier =
            Modifier
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(width = 1.dp, color = outlineColor, shape = RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .testTag("paletteSymbolLocked_${def.id}"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .alpha(0.45f),
        ) {
            val bounds = Rect(left = 0f, top = 0f, right = size.width, bottom = size.height)
            drawSymbolPath(
                def = def,
                bounds = bounds,
                color = surface,
                strokeWidthPx = 2f,
            )
        }
        // Lock-icon corner badge — sits in the bottom-right of the cell so
        // it doesn't overlap the symbol's center mass. Filled circle for
        // contrast against the surface, primary tint to match the broader
        // Pro accent direction.
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = lockedDescription,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
