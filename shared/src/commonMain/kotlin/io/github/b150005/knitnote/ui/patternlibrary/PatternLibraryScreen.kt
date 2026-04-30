package io.github.b150005.knitnote.ui.patternlibrary

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.SortOrder
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_clear_search
import io.github.b150005.knitnote.generated.resources.action_delete
import io.github.b150005.knitnote.generated.resources.action_new_pattern
import io.github.b150005.knitnote.generated.resources.action_sort
import io.github.b150005.knitnote.generated.resources.dialog_delete_pattern_body
import io.github.b150005.knitnote.generated.resources.dialog_delete_pattern_title
import io.github.b150005.knitnote.generated.resources.hint_search_patterns
import io.github.b150005.knitnote.generated.resources.label_difficulty_all
import io.github.b150005.knitnote.generated.resources.label_gauge_value
import io.github.b150005.knitnote.generated.resources.label_needle_value
import io.github.b150005.knitnote.generated.resources.label_sort_alphabetical
import io.github.b150005.knitnote.generated.resources.label_sort_alphabetical_detail
import io.github.b150005.knitnote.generated.resources.label_sort_recent
import io.github.b150005.knitnote.generated.resources.label_yarn_value
import io.github.b150005.knitnote.generated.resources.state_no_matching_patterns
import io.github.b150005.knitnote.generated.resources.state_no_matching_patterns_body
import io.github.b150005.knitnote.generated.resources.state_no_patterns
import io.github.b150005.knitnote.generated.resources.state_no_patterns_body
import io.github.b150005.knitnote.generated.resources.title_pattern_library
import io.github.b150005.knitnote.ui.components.EmptyStateView
import io.github.b150005.knitnote.ui.components.LiveSnackbarHost
import io.github.b150005.knitnote.ui.components.labelKey
import io.github.b150005.knitnote.ui.components.localized
import io.github.b150005.knitnote.ui.components.selectedCheckmarkIcon
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatternLibraryScreen(
    onPatternClick: (patternId: String) -> Unit,
    onCreatePattern: () -> Unit,
    onBack: () -> Unit,
    viewModel: PatternLibraryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(PatternLibraryEvent.ClearError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_pattern_library)) },
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
        snackbarHost = { LiveSnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreatePattern,
                modifier = Modifier.testTag("createPatternFab"),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(Res.string.action_new_pattern),
                )
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            SearchField(
                query = state.searchQuery,
                onQueryChange = { viewModel.onEvent(PatternLibraryEvent.UpdateSearchQuery(it)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            FilterSortRow(
                difficultyFilter = state.difficultyFilter,
                sortOrder = state.sortOrder,
                onDifficultyFilterChange = {
                    viewModel.onEvent(PatternLibraryEvent.UpdateDifficultyFilter(it))
                },
                onSortOrderChange = {
                    viewModel.onEvent(PatternLibraryEvent.UpdateSortOrder(it))
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            val hasActiveFilter =
                state.searchQuery.isNotBlank() || state.difficultyFilter != null
            var patternToDelete by rememberSaveable { mutableStateOf<String?>(null) }

            if (patternToDelete != null) {
                val patternName =
                    state.patterns.find { it.id == patternToDelete }?.title ?: ""
                DeleteConfirmDialog(
                    itemName = patternName,
                    onConfirm = {
                        patternToDelete?.let { id ->
                            viewModel.onEvent(PatternLibraryEvent.DeletePattern(id))
                        }
                        patternToDelete = null
                    },
                    onDismiss = { patternToDelete = null },
                )
            }

            when {
                state.isLoading -> {
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
                    // Sprint B M4 (Phase 39 pre-beta UX audit): the EmptyStateView
                    // CTA button was visually duplicating the always-visible FAB
                    // bottom-right. The FAB is Material 3's canonical
                    // "create pattern" entry point on this surface; the empty
                    // state now just explains *why* the user is seeing nothing
                    // and points the eye toward the FAB.
                    EmptyStateView(
                        icon = Icons.Default.Favorite,
                        title = stringResource(Res.string.state_no_patterns),
                        body = stringResource(Res.string.state_no_patterns_body),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding =
                            androidx.compose.foundation.layout
                                .PaddingValues(horizontal = 16.dp),
                    ) {
                        items(state.patterns, key = { it.id }) { pattern ->
                            SwipeToDismissPatternCard(
                                pattern = pattern,
                                onClick = { onPatternClick(pattern.id) },
                                onDeleteRequest = { patternToDelete = pattern.id },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().testTag("patternSearchField"),
        placeholder = { Text(stringResource(Res.string.hint_search_patterns)) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.testTag("clearSearchButton"),
                ) {
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
private fun FilterSortRow(
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
            // Sprint A PR5: leadingIcon checkmark on selected chips per WCAG 1.4.1.
            item {
                FilterChip(
                    selected = difficultyFilter == null,
                    onClick = { onDifficultyFilterChange(null) },
                    label = { Text(stringResource(Res.string.label_difficulty_all)) },
                    leadingIcon = selectedCheckmarkIcon(difficultyFilter == null),
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
                    leadingIcon = selectedCheckmarkIcon(difficultyFilter == Difficulty.BEGINNER),
                    modifier = Modifier.testTag("difficultyBeginnerChip"),
                )
            }
            item {
                FilterChip(
                    selected = difficultyFilter == Difficulty.INTERMEDIATE,
                    onClick = {
                        onDifficultyFilterChange(
                            if (difficultyFilter == Difficulty.INTERMEDIATE) {
                                null
                            } else {
                                Difficulty.INTERMEDIATE
                            },
                        )
                    },
                    label = { Text(stringResource(Difficulty.INTERMEDIATE.labelKey)) },
                    leadingIcon = selectedCheckmarkIcon(difficultyFilter == Difficulty.INTERMEDIATE),
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
                    leadingIcon = selectedCheckmarkIcon(difficultyFilter == Difficulty.ADVANCED),
                    modifier = Modifier.testTag("difficultyAdvancedChip"),
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        SortDropdown(
            sortOrder = sortOrder,
            onSortOrderChange = onSortOrderChange,
        )
    }
}

@Composable
private fun SortDropdown(
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // PROGRESS is not a valid sort order for patterns; fall back to the RECENT
    // label to keep the chip readable if the state ever ends up there.
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeToDismissPatternCard(
    pattern: Pattern,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    // Sprint B M6 (WCAG 2.5.7 Dragging Movements): long-press → context menu
    // provides a non-drag delete path so motor-impaired users + screen reader
    // users can reach the delete affordance via single-pointer-no-drag input.
    var contextMenuExpanded by remember { mutableStateOf(false) }
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    onDeleteRequest()
                    false // Don't actually dismiss — wait for dialog confirmation
                } else {
                    false
                }
            },
        )

    Box {
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.action_delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            },
            enableDismissFromStartToEnd = false,
        ) {
            PatternCard(
                pattern = pattern,
                onClick = onClick,
                onLongClick = { contextMenuExpanded = true },
            )
        }
        DropdownMenu(
            expanded = contextMenuExpanded,
            onDismissRequest = { contextMenuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.action_delete)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    contextMenuExpanded = false
                    onDeleteRequest()
                },
                modifier = Modifier.testTag("patternContextDeleteItem"),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PatternCard(
    pattern: Pattern,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onLongClickLabel = stringResource(Res.string.action_delete),
                ),
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
                    DifficultyBadge(pattern.difficulty)
                }
            }

            if (!pattern.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pattern.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val details = buildPatternDetails(pattern)
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
        }
    }
}

@Composable
private fun DifficultyBadge(difficulty: Difficulty) {
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
private fun DeleteConfirmDialog(
    itemName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_delete_pattern_title)) },
        text = {
            Text(stringResource(Res.string.dialog_delete_pattern_body, itemName))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(Res.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun buildPatternDetails(pattern: Pattern): String {
    val gauge = pattern.gauge?.let { stringResource(Res.string.label_gauge_value, it) }
    val yarn = pattern.yarnInfo?.let { stringResource(Res.string.label_yarn_value, it) }
    val needle = pattern.needleSize?.let { stringResource(Res.string.label_needle_value, it) }
    return listOfNotNull(gauge, yarn, needle).joinToString(" \u2022 ")
}
