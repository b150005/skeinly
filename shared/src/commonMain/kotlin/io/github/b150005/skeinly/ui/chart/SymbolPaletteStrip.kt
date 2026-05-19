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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.b150005.skeinly.domain.symbol.SymbolCategory
import io.github.b150005.skeinly.domain.symbol.SymbolDefinition
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.a11y_action_eraser_tool
import io.github.b150005.skeinly.generated.resources.a11y_label_palette_cell
import io.github.b150005.skeinly.generated.resources.title_locked_symbol
import io.github.b150005.skeinly.platform.DeviceContextProvider
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

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
    // R2 (audit §3.2 H1) — palette cells speak locale-appropriate symbol
    // names. Resolves once at the strip level (same pattern as
    // ChartViewerScreen) and threads `deviceContext` down via the cell
    // composables so the inner cells stay pure of Koin lookups. (X3 cleanup:
    // `val isJa` was removed in favour of passing `deviceContext` and
    // evaluating the locale at the point of use — the catalog jaLabel /
    // enLabel resolver is the only remaining caller; tracked as the X3
    // follow-up "Catalog locale-aware symbol label resolver".)
    val deviceContext: DeviceContextProvider = koinInject()
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
                    deviceContext = deviceContext,
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
                    deviceContext = deviceContext,
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
    // R2 (audit §3.2 H1) — was hardcoded "Eraser"; routed through the
    // localized key + cell-level semantics announce role=Button + selected.
    val eraserLabel = stringResource(Res.string.a11y_action_eraser_tool)
    val isSelected = selected
    Box(
        modifier =
            Modifier
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = eraserLabel
                    role = Role.Button
                    this.selected = isSelected
                }.testTag("paletteEraser"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Clear,
            // Cell-level semantics already speaks the eraser label; null on
            // the inner Icon avoids SR double-announcement.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PaletteSymbolCell(
    def: SymbolDefinition,
    selected: Boolean,
    deviceContext: DeviceContextProvider,
    onClick: () -> Unit,
) {
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.onSurface
    // R2 (audit §3.2 H1) — was unlabeled; def.{en,ja}Label was unused.
    // Formats via the `a11y_label_palette_cell` placeholder so SR speaks
    // "Symbol: <name>" / "記号: <name>" with role=Button + selected state.
    // X3 (R1b Follow-up #1) — inline locale evaluation; tracked as X3
    // follow-up "Catalog locale-aware symbol label resolver".
    val symbolName =
        if (deviceContext.locale.startsWith("ja", ignoreCase = true)) def.jaLabel else def.enLabel
    val cellDescription = stringResource(Res.string.a11y_label_palette_cell, symbolName)
    val isSelected = selected
    Box(
        modifier =
            Modifier
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = cellDescription
                    role = Role.Button
                    this.selected = isSelected
                }.testTag("paletteSymbol_${def.id}"),
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
    deviceContext: DeviceContextProvider,
    onClick: () -> Unit,
) {
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.onSurface
    val lockedDescription = stringResource(Res.string.title_locked_symbol)
    // R2 (audit §3.2 H1) — was unlabeled; the lock-icon contentDescription
    // alone said "Pro symbol" but never the symbol name. The cell-level
    // semantic now composes "<Pro symbol> · <Symbol: name>" so SR speaks
    // both the locked state and which symbol is locked.
    // X3 (R1b Follow-up #1) — inline locale evaluation; tracked as X3
    // follow-up "Catalog locale-aware symbol label resolver".
    val symbolName =
        if (deviceContext.locale.startsWith("ja", ignoreCase = true)) def.jaLabel else def.enLabel
    val cellDescription =
        lockedDescription + " · " +
            stringResource(Res.string.a11y_label_palette_cell, symbolName)
    Box(
        modifier =
            Modifier
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(width = 1.dp, color = outlineColor, shape = RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = cellDescription
                    role = Role.Button
                }.testTag("paletteSymbolLocked_${def.id}"),
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
                // Cell-level semantics already announces locked + symbol;
                // null on the inner Lock icon avoids SR double-announcement.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
