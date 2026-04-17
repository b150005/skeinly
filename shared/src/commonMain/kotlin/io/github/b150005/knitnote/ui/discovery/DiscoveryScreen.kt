package io.github.b150005.knitnote.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.SortOrder
import io.github.b150005.knitnote.ui.components.EmptyStateView
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    onBack: () -> Unit,
    onForked: (projectId: String) -> Unit,
    viewModel: DiscoveryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(DiscoveryEvent.ClearError)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.forkedProjectId.collect { projectId ->
            onForked(projectId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover Patterns") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.onEvent(DiscoveryEvent.Refresh) },
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                DiscoverySearchField(
                    query = state.searchQuery,
                    onQueryChange = { viewModel.onEvent(DiscoveryEvent.UpdateSearchQuery(it)) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                DiscoveryFilterSortRow(
                    difficultyFilter = state.difficultyFilter,
                    sortOrder = state.sortOrder,
                    onDifficultyFilterChange = {
                        viewModel.onEvent(DiscoveryEvent.UpdateDifficultyFilter(it))
                    },
                    onSortOrderChange = {
                        viewModel.onEvent(DiscoveryEvent.UpdateSortOrder(it))
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                Spacer(modifier = Modifier.height(8.dp))

                val hasActiveFilter = state.searchQuery.isNotBlank() || state.difficultyFilter != null

                when {
                    state.isLoading && state.patterns.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    state.patterns.isEmpty() && hasActiveFilter -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No matching patterns",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Try adjusting your search or filters",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    state.patterns.isEmpty() -> {
                        EmptyStateView(
                            icon = Icons.Default.Explore,
                            title = "No public patterns yet",
                            body = "Public patterns from other knitters will appear here",
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            items(state.patterns, key = { it.id }) { pattern ->
                                DiscoveryPatternCard(
                                    pattern = pattern,
                                    isForkInProgress = state.forkingPatternId == pattern.id,
                                    onFork = { viewModel.onEvent(DiscoveryEvent.ForkPattern(pattern.id)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("discoverySearchField"),
        placeholder = { Text("Search public patterns...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
    )
}

@Composable
private fun DiscoveryFilterSortRow(
    difficultyFilter: Difficulty?,
    sortOrder: SortOrder,
    onDifficultyFilterChange: (Difficulty?) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = difficultyFilter == null,
                    onClick = { onDifficultyFilterChange(null) },
                    label = { Text("All") },
                )
            }
            item {
                FilterChip(
                    selected = difficultyFilter == Difficulty.BEGINNER,
                    onClick = {
                        onDifficultyFilterChange(
                            if (difficultyFilter == Difficulty.BEGINNER) null else Difficulty.BEGINNER,
                        )
                    },
                    label = { Text("Beginner") },
                )
            }
            item {
                FilterChip(
                    selected = difficultyFilter == Difficulty.INTERMEDIATE,
                    onClick = {
                        onDifficultyFilterChange(
                            if (difficultyFilter == Difficulty.INTERMEDIATE) null else Difficulty.INTERMEDIATE,
                        )
                    },
                    label = { Text("Intermediate") },
                )
            }
            item {
                FilterChip(
                    selected = difficultyFilter == Difficulty.ADVANCED,
                    onClick = {
                        onDifficultyFilterChange(
                            if (difficultyFilter == Difficulty.ADVANCED) null else Difficulty.ADVANCED,
                        )
                    },
                    label = { Text("Advanced") },
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        DiscoverySortDropdown(sortOrder = sortOrder, onSortOrderChange = onSortOrderChange)
    }
}

@Composable
private fun DiscoverySortDropdown(
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chipLabel =
        when (sortOrder) {
            SortOrder.RECENT -> "Recent"
            SortOrder.ALPHABETICAL -> "A\u2013Z"
            SortOrder.PROGRESS -> "Recent"
        }

    Box {
        FilterChip(
            selected = sortOrder != SortOrder.RECENT,
            onClick = { expanded = true },
            label = { Text(chipLabel) },
            trailingIcon = {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Recent") },
                onClick = {
                    onSortOrderChange(SortOrder.RECENT)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Alphabetical (A\u2013Z)") },
                onClick = {
                    onSortOrderChange(SortOrder.ALPHABETICAL)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun DiscoveryPatternCard(
    pattern: Pattern,
    isForkInProgress: Boolean,
    onFork: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pattern.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (pattern.difficulty != null) {
                    DiscoveryDifficultyBadge(pattern.difficulty)
                }
            }

            if (!pattern.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pattern.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val details = buildDiscoveryPatternDetails(pattern)
            if (details.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (isForkInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(
                        onClick = onFork,
                        modifier = Modifier.testTag("forkButton_${pattern.id}"),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Fork pattern",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryDifficultyBadge(difficulty: Difficulty) {
    val (text, color) =
        when (difficulty) {
            Difficulty.BEGINNER -> "Beginner" to MaterialTheme.colorScheme.tertiary
            Difficulty.INTERMEDIATE -> "Intermediate" to MaterialTheme.colorScheme.primary
            Difficulty.ADVANCED -> "Advanced" to MaterialTheme.colorScheme.error
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

private fun buildDiscoveryPatternDetails(pattern: Pattern): String =
    listOfNotNull(
        pattern.gauge?.let { "Gauge: $it" },
        pattern.yarnInfo?.let { "Yarn: $it" },
        pattern.needleSize?.let { "Needle: $it" },
    ).joinToString(" \u2022 ")
