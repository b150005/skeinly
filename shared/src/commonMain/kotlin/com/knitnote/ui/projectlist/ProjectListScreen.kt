package com.knitnote.ui.projectlist

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    onProjectClick: (String) -> Unit,
    viewModel: ProjectListViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(ProjectListEvent.ClearError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Knit Note") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.onEvent(ProjectListEvent.ShowCreateDialog) },
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Project")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.projects.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "No projects yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap + to create your first project",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    ) {
                        items(state.projects, key = { it.id }) { project ->
                            ProjectCard(
                                project = project,
                                onClick = { onProjectClick(project.id) },
                                onDelete = { viewModel.onEvent(ProjectListEvent.DeleteProject(project.id)) },
                            )
                        }
                    }
                }
            }
        }

        if (state.showCreateDialog) {
            CreateProjectDialog(
                onDismiss = { viewModel.onEvent(ProjectListEvent.DismissCreateDialog) },
                onCreate = { title, totalRows ->
                    viewModel.onEvent(ProjectListEvent.CreateProject(title, totalRows))
                },
            )
        }
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
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
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val progressText = if (project.totalRows != null) {
                "${project.currentRow} / ${project.totalRows} rows"
            } else {
                "${project.currentRow} rows"
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
private fun StatusChip(status: ProjectStatus) {
    val (text, color) = when (status) {
        ProjectStatus.NOT_STARTED -> "Not Started" to MaterialTheme.colorScheme.outline
        ProjectStatus.IN_PROGRESS -> "In Progress" to MaterialTheme.colorScheme.primary
        ProjectStatus.COMPLETED -> "Completed" to MaterialTheme.colorScheme.tertiary
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}
