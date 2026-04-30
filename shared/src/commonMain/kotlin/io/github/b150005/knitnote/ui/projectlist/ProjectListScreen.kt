package io.github.b150005.knitnote.ui.projectlist

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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.model.SortOrder
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_activity_feed
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_clear_search
import io.github.b150005.knitnote.generated.resources.action_create_project
import io.github.b150005.knitnote.generated.resources.action_delete
import io.github.b150005.knitnote.generated.resources.action_discover_patterns
import io.github.b150005.knitnote.generated.resources.action_new_project
import io.github.b150005.knitnote.generated.resources.action_pattern_library
import io.github.b150005.knitnote.generated.resources.action_profile
import io.github.b150005.knitnote.generated.resources.action_pull_requests
import io.github.b150005.knitnote.generated.resources.action_settings
import io.github.b150005.knitnote.generated.resources.action_shared_with_me
import io.github.b150005.knitnote.generated.resources.action_sort
import io.github.b150005.knitnote.generated.resources.action_symbol_dictionary
import io.github.b150005.knitnote.generated.resources.app_name
import io.github.b150005.knitnote.generated.resources.dialog_delete_project_body
import io.github.b150005.knitnote.generated.resources.dialog_delete_project_title
import io.github.b150005.knitnote.generated.resources.hint_search_projects
import io.github.b150005.knitnote.generated.resources.label_rows_current
import io.github.b150005.knitnote.generated.resources.label_rows_of_total
import io.github.b150005.knitnote.generated.resources.label_sort_alphabetical
import io.github.b150005.knitnote.generated.resources.label_sort_alphabetical_detail
import io.github.b150005.knitnote.generated.resources.label_sort_progress
import io.github.b150005.knitnote.generated.resources.label_sort_progress_detail
import io.github.b150005.knitnote.generated.resources.label_sort_recent
import io.github.b150005.knitnote.generated.resources.label_status_all
import io.github.b150005.knitnote.generated.resources.label_status_completed
import io.github.b150005.knitnote.generated.resources.label_status_in_progress
import io.github.b150005.knitnote.generated.resources.label_status_not_started
import io.github.b150005.knitnote.generated.resources.state_no_matching_projects
import io.github.b150005.knitnote.generated.resources.state_no_matching_projects_body
import io.github.b150005.knitnote.generated.resources.state_no_projects
import io.github.b150005.knitnote.generated.resources.state_no_projects_body
import io.github.b150005.knitnote.ui.components.EmptyStateView
import io.github.b150005.knitnote.ui.components.localized
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    onProjectClick: (String) -> Unit,
    onPatternLibraryClick: () -> Unit = {},
    onSharedWithMeClick: () -> Unit = {},
    onActivityFeedClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onDiscoverClick: () -> Unit = {},
    onSymbolGalleryClick: () -> Unit = {},
    onSuggestionsClick: () -> Unit = {},
    viewModel: ProjectListViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(ProjectListEvent.ClearError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.app_name)) },
                actions = {
                    IconButton(onClick = onDiscoverClick) {
                        Icon(
                            Icons.Default.Explore,
                            contentDescription = stringResource(Res.string.action_discover_patterns),
                        )
                    }
                    IconButton(
                        onClick = onPatternLibraryClick,
                        modifier = Modifier.testTag("patternLibraryButton"),
                    ) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = stringResource(Res.string.action_pattern_library),
                        )
                    }
                    IconButton(
                        onClick = onSymbolGalleryClick,
                        modifier = Modifier.testTag("symbolGalleryButton"),
                    ) {
                        Icon(
                            Icons.Default.GridView,
                            contentDescription = stringResource(Res.string.action_symbol_dictionary),
                        )
                    }
                    IconButton(
                        onClick = onProfileClick,
                        modifier = Modifier.testTag("profileButton"),
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = stringResource(Res.string.action_profile),
                        )
                    }
                    IconButton(
                        onClick = onActivityFeedClick,
                        modifier = Modifier.testTag("activityFeedButton"),
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = stringResource(Res.string.action_activity_feed),
                        )
                    }
                    // Phase 38.2 (ADR-014 §6) — entry point to the read-only PR
                    // list. Defaults to the Incoming filter (the user's own
                    // patterns receive PRs from forkers); the chip row in the
                    // list lets them toggle to Outgoing without re-navigating.
                    IconButton(
                        onClick = onSuggestionsClick,
                        modifier = Modifier.testTag("pullRequestsButton"),
                    ) {
                        Icon(
                            Icons.Default.Forum,
                            contentDescription = stringResource(Res.string.action_pull_requests),
                        )
                    }
                    IconButton(
                        onClick = onSharedWithMeClick,
                        modifier = Modifier.testTag("sharedWithMeButton"),
                    ) {
                        Icon(
                            Icons.Default.People,
                            contentDescription = stringResource(Res.string.action_shared_with_me),
                        )
                    }
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier.testTag("settingsButton"),
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(Res.string.action_settings),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.onEvent(ProjectListEvent.ShowCreateDialog) },
                modifier = Modifier.testTag("createProjectFab"),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(Res.string.action_new_project),
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
                onQueryChange = { viewModel.onEvent(ProjectListEvent.UpdateSearchQuery(it)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            FilterSortRow(
                statusFilter = state.statusFilter,
                sortOrder = state.sortOrder,
                onStatusFilterChange = { viewModel.onEvent(ProjectListEvent.UpdateStatusFilter(it)) },
                onSortOrderChange = { viewModel.onEvent(ProjectListEvent.UpdateSortOrder(it)) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            val hasActiveFilter = state.searchQuery.isNotBlank() || state.statusFilter != null
            var projectToDelete by rememberSaveable { mutableStateOf<String?>(null) }

            if (projectToDelete != null) {
                val projectName = state.projects.find { it.id == projectToDelete }?.title ?: ""
                DeleteConfirmDialog(
                    itemName = projectName,
                    onConfirm = {
                        projectToDelete?.let { id ->
                            viewModel.onEvent(ProjectListEvent.DeleteProject(id))
                        }
                        projectToDelete = null
                    },
                    onDismiss = { projectToDelete = null },
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
                state.projects.isEmpty() && hasActiveFilter -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(Res.string.state_no_matching_projects),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(Res.string.state_no_matching_projects_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.projects.isEmpty() -> {
                    EmptyStateView(
                        icon = Icons.Default.FolderOpen,
                        title = stringResource(Res.string.state_no_projects),
                        body = stringResource(Res.string.state_no_projects_body),
                        actionLabel = stringResource(Res.string.action_create_project),
                        onAction = { viewModel.onEvent(ProjectListEvent.ShowCreateDialog) },
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
                        items(state.projects, key = { it.id }) { project ->
                            SwipeToDismissProjectCard(
                                project = project,
                                onClick = { onProjectClick(project.id) },
                                onDeleteRequest = { projectToDelete = project.id },
                            )
                        }
                    }
                }
            }
        }

        if (state.showCreateDialog) {
            CreateProjectDialog(
                onDismiss = { viewModel.onEvent(ProjectListEvent.DismissCreateDialog) },
                onCreate = { title, totalRows, patternId ->
                    viewModel.onEvent(ProjectListEvent.CreateProject(title, totalRows, patternId))
                },
                patterns = state.patternsForCreate,
            )
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
        modifier = modifier.fillMaxWidth().testTag("searchField"),
        placeholder = { Text(stringResource(Res.string.hint_search_projects)) },
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
    statusFilter: ProjectStatus?,
    sortOrder: SortOrder,
    onStatusFilterChange: (ProjectStatus?) -> Unit,
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
                    selected = statusFilter == null,
                    onClick = { onStatusFilterChange(null) },
                    label = { Text(stringResource(Res.string.label_status_all)) },
                    modifier = Modifier.testTag("filterAllChip"),
                )
            }
            item {
                FilterChip(
                    selected = statusFilter == ProjectStatus.IN_PROGRESS,
                    onClick = {
                        onStatusFilterChange(
                            if (statusFilter == ProjectStatus.IN_PROGRESS) null else ProjectStatus.IN_PROGRESS,
                        )
                    },
                    label = { Text(stringResource(Res.string.label_status_in_progress)) },
                    modifier = Modifier.testTag("filterInProgressChip"),
                )
            }
            item {
                FilterChip(
                    selected = statusFilter == ProjectStatus.NOT_STARTED,
                    onClick = {
                        onStatusFilterChange(
                            if (statusFilter == ProjectStatus.NOT_STARTED) null else ProjectStatus.NOT_STARTED,
                        )
                    },
                    label = { Text(stringResource(Res.string.label_status_not_started)) },
                    modifier = Modifier.testTag("filterNotStartedChip"),
                )
            }
            item {
                FilterChip(
                    selected = statusFilter == ProjectStatus.COMPLETED,
                    onClick = {
                        onStatusFilterChange(
                            if (statusFilter == ProjectStatus.COMPLETED) null else ProjectStatus.COMPLETED,
                        )
                    },
                    label = { Text(stringResource(Res.string.label_status_completed)) },
                    modifier = Modifier.testTag("filterCompletedChip"),
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
            label = { Text(stringResource(sortOrder.chipLabelKey)) },
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
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(stringResource(order.menuLabelKey)) },
                    onClick = {
                        onSortOrderChange(order)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissProjectCard(
    project: Project,
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
                    contentDescription = stringResource(Res.string.action_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        ProjectCard(project = project, onClick = onClick)
    }
}

@Composable
private fun ProjectCard(
    project: Project,
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
                    text = project.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(project.status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            val progressText =
                if (project.totalRows != null) {
                    stringResource(Res.string.label_rows_of_total, project.currentRow, project.totalRows)
                } else {
                    stringResource(Res.string.label_rows_current, project.currentRow)
                }
            Text(
                text = progressText,
                style = MaterialTheme.typography.bodyLarge,
            )

            if (project.totalRows != null && project.totalRows > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { project.currentRow.toFloat() / project.totalRows.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    itemName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_delete_project_title)) },
        text = { Text(stringResource(Res.string.dialog_delete_project_body, itemName)) },
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
private fun StatusChip(status: ProjectStatus) {
    val (textKey, color) =
        when (status) {
            ProjectStatus.NOT_STARTED -> Res.string.label_status_not_started to MaterialTheme.colorScheme.outline
            ProjectStatus.IN_PROGRESS -> Res.string.label_status_in_progress to MaterialTheme.colorScheme.primary
            ProjectStatus.COMPLETED -> Res.string.label_status_completed to MaterialTheme.colorScheme.tertiary
        }
    Text(
        text = stringResource(textKey),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

private val SortOrder.chipLabelKey: StringResource
    get() =
        when (this) {
            SortOrder.RECENT -> Res.string.label_sort_recent
            SortOrder.ALPHABETICAL -> Res.string.label_sort_alphabetical
            SortOrder.PROGRESS -> Res.string.label_sort_progress
        }

// RECENT reuses `label_sort_recent` in both chip and menu forms by design —
// the short form reads naturally as a full menu label. If a detail variant is
// ever needed (e.g. "Most Recent"), add `label_sort_recent_detail`.
private val SortOrder.menuLabelKey: StringResource
    get() =
        when (this) {
            SortOrder.RECENT -> Res.string.label_sort_recent
            SortOrder.ALPHABETICAL -> Res.string.label_sort_alphabetical_detail
            SortOrder.PROGRESS -> Res.string.label_sort_progress_detail
        }
