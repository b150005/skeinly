package io.github.b150005.knitnote.ui.symbol

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.b150005.knitnote.domain.symbol.PathCommand
import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import io.github.b150005.knitnote.domain.symbol.SymbolDefinition
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.title_symbol_dictionary
import io.github.b150005.knitnote.ui.chart.drawSymbolPath
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.max
import kotlin.math.min

private val GalleryCategories = SymbolCategory.entries

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymbolGalleryScreen(
    onBack: () -> Unit,
    viewModel: SymbolGalleryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Plain mutable map wrapped in `remember` — writes happen during Canvas draw
    // and must not trigger recomposition. Matches the pattern in
    // `ChartViewerScreen.ChartCanvas.parsedPathCache`.
    val parsedPathCache =
        remember { mutableMapOf<String, List<PathCommand>>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.title_symbol_dictionary))
                        Text(
                            text = "${state.symbols.size} / ${state.totalCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            CategoryFilterChips(
                activeFilter = state.activeCategoryFilter,
                onFilterClick = { category ->
                    if (state.activeCategoryFilter == category) {
                        viewModel.onEvent(SymbolGalleryEvent.ClearCategoryFilter)
                    } else {
                        viewModel.onEvent(SymbolGalleryEvent.FilterByCategory(category))
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag("symbolGalleryGrid"),
            ) {
                items(state.symbols, key = { it.id }) { definition ->
                    SymbolCard(
                        definition = definition,
                        parsedPathCache = parsedPathCache,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryFilterChips(
    activeFilter: SymbolCategory?,
    onFilterClick: (SymbolCategory) -> Unit,
) {
    LazyRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        items(GalleryCategories, key = { it.name }) { category ->
            val selected = category == activeFilter
            FilterChip(
                selected = selected,
                onClick = { onFilterClick(category) },
                label = { Text("${category.jaLabel} / ${category.enLabel}") },
                modifier = Modifier.padding(horizontal = 4.dp),
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun SymbolCard(
    definition: SymbolDefinition,
    parsedPathCache: MutableMap<String, List<PathCommand>>,
) {
    val symbolColor = MaterialTheme.colorScheme.onSurface
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("symbolCard-${definition.id}"),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val padding = 16f
                    val w = size.width - padding * 2
                    val h = size.height - padding * 2
                    val side = min(w, h)
                    val left = (size.width - side) / 2f
                    val top = (size.height - side) / 2f
                    drawSymbolPath(
                        def = definition,
                        bounds =
                            Rect(
                                left = left,
                                top = top,
                                right = left + side,
                                bottom = top + side,
                            ),
                        color = symbolColor,
                        strokeWidthPx = max(1.5f, side * 0.05f),
                        parsedPathCache = parsedPathCache,
                    )
                }
            }
            Text(
                text = definition.jaLabel,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = definition.enLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = definition.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
            val jaDesc = definition.jaDescription
            val enDesc = definition.enDescription
            if (!jaDesc.isNullOrBlank()) {
                Text(
                    text = jaDesc,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!enDesc.isNullOrBlank()) {
                Text(
                    text = enDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
