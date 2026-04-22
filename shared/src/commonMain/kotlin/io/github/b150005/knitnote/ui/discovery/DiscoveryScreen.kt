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
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_clear_search
import io.github.b150005.knitnote.generated.resources.action_fork_pattern
import io.github.b150005.knitnote.generated.resources.action_sort
import io.github.b150005.knitnote.generated.resources.hint_search_public_patterns
import io.github.b150005.knitnote.generated.resources.label_difficulty_advanced
import io.github.b150005.knitnote.generated.resources.label_difficulty_all
import io.github.b150005.knitnote.generated.resources.label_difficulty_beginner
import io.github.b150005.knitnote.generated.resources.label_difficulty_intermediate
import io.github.b150005.knitnote.generated.resources.label_gauge_value
import io.github.b150005.knitnote.generated.resources.label_needle_value
import io.github.b150005.knitnote.generated.resources.label_sort_alphabetical
import io.github.b150005.knitnote.generated.resources.label_sort_alphabetical_detail
import io.github.b150005.knitnote.generated.resources.label_sort_recent
import io.github.b150005.knitnote.generated.resources.label_yarn_value
import io.github.b150005.knitnote.generated.resources.state_no_matching_patterns
import io.github.b150005.knitnote.generated.resources.state_no_matching_patterns_body
import io.github.b150005.knitnote.generated.resources.state_no_public_patterns
import io.github.b150005.knitnote.generated.resources.state_no_public_patterns_body
import io.github.b150005.knitnote.generated.resources.title_discover_patterns
import io.github.b150005.knitnote.ui.components.EmptyStateView
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
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
        modifier = Modifier.testTag("discoveryScreen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_discover_patterns)) },
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
                                    text = stringResource(Res.string.state_no_matching_patterns),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(Res.string.state_no_matching_patterns_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    state.patterns.isEmpty() -> {
                        EmptyStateView(
                            icon = Icons.Default.Explore,
                            title = stringResource(Res.string.state_no_public_patterns),
                            body = stringResource(Res.string.state_no_public_patterns_body),
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
        placeholder = { Text(stringResource(Res.string.hint_search_public_patterns)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(Res.string.action_clear_search),
                    )
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
                    label = { Text(stringResource(Res.string.label_difficulty_all)) },
                    modifier = Modifier.testTag("difficultyAllChip"),
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
                    label = { Text(stringResource(Difficulty.BEGINNER.labelKey)) },
                    modifier = Modifier.testTag("difficultyBeginnerChip"),
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
                    label = { Text(stringResource(Difficulty.INTERMEDIATE.labelKey)) },
                    modifier = Modifier.testTag("difficultyIntermediateChip"),
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
                    label = { Text(stringResource(Difficulty.ADVANCED.labelKey)) },
                    modifier = Modifier.testTag("difficultyAdvancedChip"),
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
    val chipLabelKey =
        when (sortOrder) {
            SortOrder.RECENT -> Res.string.label_sort_recent
            SortOrder.ALPHABETICAL -> Res.string.label_sort_alphabetical
            SortOrder.PROGRESS -> Res.string.label_sort_recent
        }

    Box {
        FilterChip(
            selected = sortOrder != SortOrder.RECENT,
            onClick = { expanded = true },
            label = { Text(stringResource(chipLabelKey)) },
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = stringResource(Res.string.action_sort),
                )
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.label_sort_recent)) },
                onClick = {
                    onSortOrderChange(SortOrder.RECENT)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.label_sort_alphabetical_detail)) },
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
                            contentDescription = stringResource(Res.string.action_fork_pattern),
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
    val color =
        when (difficulty) {
            Difficulty.BEGINNER -> MaterialTheme.colorScheme.tertiary
            Difficulty.INTERMEDIATE -> MaterialTheme.colorScheme.primary
            Difficulty.ADVANCED -> MaterialTheme.colorScheme.error
        }
    Text(
        text = stringResource(difficulty.labelKey),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun buildDiscoveryPatternDetails(pattern: Pattern): String {
    val gauge = pattern.gauge?.let { stringResource(Res.string.label_gauge_value, it) }
    val yarn = pattern.yarnInfo?.let { stringResource(Res.string.label_yarn_value, it) }
    val needle = pattern.needleSize?.let { stringResource(Res.string.label_needle_value, it) }
    return listOfNotNull(gauge, yarn, needle).joinToString(" \u2022 ")
}

// Difficulty label keys are defined once here so filter chips, the badge, and any
// future consumer within Discovery resolve via a single source. If PatternLibrary
// needs the same mapping later, promote to a shared domain extension file.
private val Difficulty.labelKey: StringResource
    get() =
        when (this) {
            Difficulty.BEGINNER -> Res.string.label_difficulty_beginner
            Difficulty.INTERMEDIATE -> Res.string.label_difficulty_intermediate
            Difficulty.ADVANCED -> Res.string.label_difficulty_advanced
        }
