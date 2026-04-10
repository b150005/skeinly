package com.knitnote.ui.projectdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.knitnote.domain.model.CommentTargetType
import com.knitnote.domain.model.Progress
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.ui.comments.CommentSection
import com.knitnote.ui.util.formatShort
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    onBack: () -> Unit,
    viewModel: ProjectDetailViewModel = koinViewModel { parametersOf(projectId) },
) {
    val state by viewModel.state.collectAsState()
    val progressNotes by viewModel.progressNotes.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddNoteDialog by rememberSaveable { mutableStateOf(false) }
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    var showUserPickerDialog by remember { mutableStateOf(false) }
    var noteToDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(ProjectDetailEvent.ClearError)
        }
    }

    if (showEditDialog) {
        val project = state.project
        if (project != null) {
            EditProjectDialog(
                currentTitle = project.title,
                currentTotalRows = project.totalRows,
                onDismiss = { showEditDialog = false },
                onConfirm = { title, totalRows ->
                    viewModel.onEvent(ProjectDetailEvent.EditProject(title, totalRows))
                    showEditDialog = false
                },
            )
        }
    }

    if (showAddNoteDialog) {
        AddNoteDialog(
            onDismiss = { showAddNoteDialog = false },
            onConfirm = { note ->
                viewModel.onEvent(ProjectDetailEvent.AddNote(note))
                showAddNoteDialog = false
            },
        )
    }

    val shareLinkToken = state.shareLink?.shareToken
    if (shareLinkToken != null) {
        ShareLinkDialog(
            shareToken = shareLinkToken,
            onDismiss = { viewModel.onEvent(ProjectDetailEvent.DismissShareDialog) },
        )
    }

    if (showUserPickerDialog) {
        UserPickerDialog(
            onDismiss = { showUserPickerDialog = false },
            onUserSelected = { userId, permission ->
                viewModel.onEvent(ProjectDetailEvent.ShareWithUser(userId, permission))
                showUserPickerDialog = false
            },
        )
    }

    LaunchedEffect(Unit) {
        viewModel.directShareSuccess.collect {
            snackbarHostState.showSnackbar("Shared successfully!")
        }
    }

    noteToDelete?.let { progressId ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("Delete Note") },
            text = { Text("Are you sure you want to delete this note?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onEvent(ProjectDetailEvent.DeleteNote(progressId))
                    noteToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.project?.title ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.project != null) {
                        IconButton(
                            onClick = { viewModel.onEvent(ProjectDetailEvent.ShareProject) },
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share link")
                        }
                        IconButton(
                            onClick = { showUserPickerDialog = true },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Share with user")
                        }
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit project")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.project == null -> {
                    Text(
                        text = "Project not found",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> {
                    val project = checkNotNull(state.project)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Counter section
                        item {
                            CounterSection(
                                statusText = when (project.status) {
                                    ProjectStatus.NOT_STARTED -> "Not Started"
                                    ProjectStatus.IN_PROGRESS -> "In Progress"
                                    ProjectStatus.COMPLETED -> "Completed!"
                                },
                                statusColor = when (project.status) {
                                    ProjectStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
                                    ProjectStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outline
                                },
                                currentRow = project.currentRow,
                                totalRows = project.totalRows,
                                onIncrement = { viewModel.onEvent(ProjectDetailEvent.IncrementRow) },
                                onDecrement = { viewModel.onEvent(ProjectDetailEvent.DecrementRow) },
                            )
                        }

                        // Status toggle button
                        item {
                            StatusToggleButton(
                                status = project.status,
                                onComplete = { viewModel.onEvent(ProjectDetailEvent.CompleteProject) },
                                onReopen = { viewModel.onEvent(ProjectDetailEvent.ReopenProject) },
                            )
                        }

                        // Notes header
                        item {
                            Spacer(modifier = Modifier.height(32.dp))
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Notes",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                IconButton(onClick = { showAddNoteDialog = true }) {
                                    Icon(Icons.Default.Add, contentDescription = "Add note")
                                }
                            }
                        }

                        // Notes list or empty state
                        if (progressNotes.isEmpty()) {
                            item {
                                Text(
                                    text = "No notes yet",
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(
                                items = progressNotes,
                                key = { it.id },
                            ) { note ->
                                SwipeToDismissNoteItem(
                                    note = note,
                                    onDelete = { noteToDelete = note.id },
                                )
                            }
                        }

                        // Comments section
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            val authRepository = koinInject<AuthRepository>()
                            val currentUserId = remember(authRepository) {
                                authRepository.getCurrentUserId()
                            }
                            CommentSection(
                                targetType = CommentTargetType.PROJECT,
                                targetId = projectId,
                                currentUserId = currentUserId,
                                onError = { message ->
                                    viewModel.onEvent(ProjectDetailEvent.ClearError)
                                    viewModel.showExternalError(message)
                                },
                            )
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CounterSection(
    statusText: String,
    statusColor: Color,
    currentRow: Int,
    totalRows: Int?,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelLarge,
            color = statusColor,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "$currentRow",
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        if (totalRows != null) {
            Text(
                text = "of $totalRows rows",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = {
                    if (totalRows > 0) {
                        currentRow.toFloat() / totalRows.toFloat()
                    } else {
                        0f
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
        } else {
            Text(
                text = "rows",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onDecrement,
                modifier = Modifier.size(72.dp),
                enabled = currentRow > 0,
            ) {
                Text(
                    text = "-",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            FilledTonalButton(
                onClick = onIncrement,
                modifier = Modifier.size(96.dp),
            ) {
                Text(
                    text = "+",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissNoteItem(
    note: Progress,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
            }
            false // Always return false — deletion is confirmed via dialog
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete note",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        NoteItem(note = note)
    }
}

@Composable
private fun NoteItem(note: Progress) {
    val timestamp = remember(note.createdAt) { note.createdAt.formatShort() }

    ListItem(
        headlineContent = {
            Text(text = note.note)
        },
        supportingContent = {
            Text(text = "Row ${note.rowNumber} - $timestamp")
        },
    )
}

@Composable
private fun StatusToggleButton(
    status: ProjectStatus,
    onComplete: () -> Unit,
    onReopen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        when (status) {
            ProjectStatus.COMPLETED -> {
                FilledTonalButton(onClick = onReopen) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Reopen")
                }
            }
            else -> {
                Button(onClick = onComplete) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Mark Complete")
                }
            }
        }
    }
}

@Composable
private fun AddNoteDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var noteText by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Note") },
        text = {
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text("Note") },
                placeholder = { Text("e.g., Decreased stitch, changed color...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(noteText.trim()) },
                enabled = noteText.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun EditProjectDialog(
    currentTitle: String,
    currentTotalRows: Int?,
    onDismiss: () -> Unit,
    onConfirm: (title: String, totalRows: Int?) -> Unit,
) {
    var title by remember(currentTitle) { mutableStateOf(currentTitle) }
    var totalRowsText by remember(currentTotalRows) {
        mutableStateOf(currentTotalRows?.toString() ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = totalRowsText,
                    onValueChange = { totalRowsText = it.filter { c -> c.isDigit() } },
                    label = { Text("Total Rows (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val totalRows = totalRowsText.toIntOrNull()
                    onConfirm(title, totalRows)
                },
                enabled = title.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
