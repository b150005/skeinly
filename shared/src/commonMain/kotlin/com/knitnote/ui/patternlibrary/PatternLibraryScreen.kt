package com.knitnote.ui.patternlibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.knitnote.domain.model.Difficulty
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.SortOrder
import com.knitnote.ui.components.EmptyStateView
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

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(PatternLibraryEvent.ClearError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pattern Library") },
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreatePattern,
                modifier = Modifier.testTag("createPatternFab"),
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Pattern")
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
                        icon = Icons.Default.Favorite,
                        title = "No patterns yet",
                        body = "Build your pattern library with gauge, yarn, and needle info",
                        actionLabel = "Create Pattern",
                        onAction = onCreatePattern,
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
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Search patterns...") },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
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
                            if (difficultyFilter == Difficulty.INTERMEDIATE) {
                                null
                            } else {
                                Difficulty.INTERMEDIATE
                            },
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

    Box {
        FilterChip(
            selected = sortOrder != SortOrder.RECENT,
            onClick = { expanded = true },
            label = { Text(sortOrder.patternChipLabel) },
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "Sort",
                )
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            PatternSortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.menuLabel) },
                    onClick = {
                        onSortOrderChange(option.order)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissPatternCard(
    pattern: Pattern,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
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
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        PatternCard(pattern = pattern, onClick = onClick)
    }
}

@Composable
private fun PatternCard(
    pattern: Pattern,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
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

@Composable
private fun DeleteConfirmDialog(
    itemName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Pattern?") },
        text = { Text("Are you sure you want to delete \"$itemName\"? This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun buildPatternDetails(pattern: Pattern): String =
    listOfNotNull(
        pattern.gauge?.let { "Gauge: $it" },
        pattern.yarnInfo?.let { "Yarn: $it" },
        pattern.needleSize?.let { "Needle: $it" },
    ).joinToString(" \u2022 ")

private val SortOrder.patternChipLabel: String
    get() =
        when (this) {
            SortOrder.RECENT -> "Recent"
            SortOrder.ALPHABETICAL -> "A\u2013Z"
            SortOrder.PROGRESS -> "Recent" // Not applicable for patterns
        }

private enum class PatternSortOption(
    val order: SortOrder,
    val menuLabel: String,
) {
    RECENT(SortOrder.RECENT, "Recent"),
    ALPHABETICAL(SortOrder.ALPHABETICAL, "Alphabetical (A\u2013Z)"),
}
