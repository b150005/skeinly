package io.github.b150005.knitnote.ui.projectdetail

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.b150005.knitnote.domain.model.CommentTargetType
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.ui.chartviewer.ChartImageGrid
import io.github.b150005.knitnote.ui.chartviewer.ChartImageViewer
import io.github.b150005.knitnote.ui.comments.CommentSection
import io.github.b150005.knitnote.ui.imagepicker.ImagePickerResult
import io.github.b150005.knitnote.ui.imagepicker.rememberImagePickerLauncher
import io.github.b150005.knitnote.ui.util.formatShort
import kotlinx.coroutines.flow.collect
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    onBack: () -> Unit,
    onChartViewerClick: (String) -> Unit = {},
    viewModel: ProjectDetailViewModel = koinViewModel { parametersOf(projectId) },
) {
    val state by viewModel.state.collectAsState()
    val progressNotes by viewModel.progressNotes.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddNoteDialog by rememberSaveable { mutableStateOf(false) }
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    var showUserPickerDialog by remember { mutableStateOf(false) }
    var noteToDelete by remember { mutableStateOf<String?>(null) }
    var chartImageToDelete by remember { mutableStateOf<String?>(null) }
    var progressPhotoViewerUrl by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher =
        rememberImagePickerLauncher { result ->
            if (result != null) {
                viewModel.onEvent(ProjectDetailEvent.UploadChartImage(result.data, result.fileName))
            }
        }

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
            onConfirm = { note, photoData, photoFileName ->
                viewModel.onEvent(
                    ProjectDetailEvent.AddNoteWithPhoto(
                        note = note,
                        photoData = photoData,
                        photoFileName = photoFileName,
                    ),
                )
                showAddNoteDialog = false
            },
            isUploading = state.isUploadingPhoto,
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

    chartImageToDelete?.let { imagePath ->
        AlertDialog(
            onDismissRequest = { chartImageToDelete = null },
            title = { Text("Remove Image") },
            text = { Text("Are you sure you want to remove this chart image?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onEvent(ProjectDetailEvent.DeleteChartImage(imagePath))
                    chartImageToDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { chartImageToDelete = null }) { Text("Cancel") }
            },
        )
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
                                statusText =
                                    when (project.status) {
                                        ProjectStatus.NOT_STARTED -> "Not Started"
                                        ProjectStatus.IN_PROGRESS -> "In Progress"
                                        ProjectStatus.COMPLETED -> "Completed!"
                                    },
                                statusColor =
                                    when (project.status) {
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

                        // Pattern metadata section
                        state.pattern?.let { pattern ->
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                PatternInfoSection(
                                    pattern = pattern,
                                    hasStructuredChart = state.hasStructuredChart,
                                    onChartViewerClick = {
                                        if (state.hasStructuredChart) {
                                            onChartViewerClick(pattern.id)
                                        }
                                    },
                                )
                            }
                        }

                        // Chart images section
                        if (state.chartImageSignedUrls.isNotEmpty() || state.isUploadingImage) {
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                ChartImageGrid(
                                    signedUrls = state.chartImageSignedUrls,
                                    storagePaths = state.chartImagePaths,
                                    isUploading = state.isUploadingImage,
                                    onImageClick = { index ->
                                        viewModel.onEvent(ProjectDetailEvent.SelectChartImage(index))
                                    },
                                    onDeleteClick = { path -> chartImageToDelete = path },
                                    onAddClick = { imagePickerLauncher.launch() },
                                )
                            }
                        } else {
                            // Show add button even when no images
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                ChartImageGrid(
                                    signedUrls = emptyList(),
                                    storagePaths = emptyList(),
                                    isUploading = state.isUploadingImage,
                                    onImageClick = {},
                                    onDeleteClick = {},
                                    onAddClick = { imagePickerLauncher.launch() },
                                )
                            }
                        }

                        // Notes header
                        item {
                            Spacer(modifier = Modifier.height(32.dp))
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                            Row(
                                modifier =
                                    Modifier
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
                                    photoSignedUrl = state.photoSignedUrls[note.id],
                                    onPhotoClick = { url -> progressPhotoViewerUrl = url },
                                )
                            }
                        }

                        // Comments section
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            val authRepository = koinInject<AuthRepository>()
                            val currentUserId =
                                remember(authRepository) {
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

            // Full-screen chart image viewer overlay
            val selectedIndex = state.selectedChartImageIndex
            if (selectedIndex != null && selectedIndex in state.chartImageSignedUrls.indices) {
                ChartImageViewer(
                    imageUrl = state.chartImageSignedUrls[selectedIndex],
                    onDismiss = { viewModel.onEvent(ProjectDetailEvent.CloseChartViewer) },
                )
            }

            // Full-screen progress photo viewer overlay
            progressPhotoViewerUrl?.let { url ->
                ChartImageViewer(
                    imageUrl = url,
                    onDismiss = { progressPhotoViewerUrl = null },
                )
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
        modifier =
            Modifier
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
            modifier = Modifier.testTag("rowCounter"),
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
                modifier =
                    Modifier
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
                modifier = Modifier.size(72.dp).testTag("decrementButton"),
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
                modifier = Modifier.size(96.dp).testTag("incrementButton"),
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
    photoSignedUrl: String? = null,
    onPhotoClick: ((String) -> Unit)? = null,
) {
    val dismissState =
        rememberSwipeToDismissBoxState(
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
                modifier =
                    Modifier
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
        NoteItem(
            note = note,
            photoSignedUrl = photoSignedUrl,
            onPhotoClick = onPhotoClick,
        )
    }
}

@Composable
private fun NoteItem(
    note: Progress,
    photoSignedUrl: String? = null,
    onPhotoClick: ((String) -> Unit)? = null,
) {
    val timestamp = remember(note.createdAt) { note.createdAt.formatShort() }

    ListItem(
        headlineContent = {
            Text(text = note.note)
        },
        supportingContent = {
            Text(text = "Row ${note.rowNumber} - $timestamp")
        },
        trailingContent =
            if (photoSignedUrl != null) {
                {
                    AsyncImage(
                        model = photoSignedUrl,
                        contentDescription = "Progress photo",
                        modifier =
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPhotoClick?.invoke(photoSignedUrl) },
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                null
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
        modifier =
            Modifier
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
    onConfirm: (note: String, photoData: ByteArray?, photoFileName: String?) -> Unit,
    isUploading: Boolean = false,
) {
    var noteText by rememberSaveable { mutableStateOf("") }
    var selectedPhoto by remember { mutableStateOf<ImagePickerResult?>(null) }
    val imagePickerLauncher =
        rememberImagePickerLauncher { result ->
            selectedPhoto = result
        }

    AlertDialog(
        onDismissRequest = { if (!isUploading) onDismiss() },
        title = { Text("Add Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note") },
                    placeholder = { Text("e.g., Decreased stitch, changed color...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3,
                )

                if (selectedPhoto != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Photo attached",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { selectedPhoto = null },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove photo",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                } else {
                    TextButton(
                        onClick = { imagePickerLauncher.launch() },
                        modifier = Modifier.testTag("addPhotoButton"),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("Add Photo")
                    }
                }

                if (isUploading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text(
                            text = "Uploading photo...",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(noteText.trim(), selectedPhoto?.data, selectedPhoto?.fileName)
                },
                enabled = noteText.isNotBlank() && !isUploading,
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isUploading,
            ) {
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

@Composable
private fun PatternInfoSection(
    pattern: Pattern,
    hasStructuredChart: Boolean,
    onChartViewerClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Pattern: ${pattern.title}",
            style = MaterialTheme.typography.titleMedium,
        )
        if (hasStructuredChart) {
            Text(
                text = "View structured chart",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .clickable(onClick = onChartViewerClick)
                        .testTag("openChartViewerLink"),
            )
        }
        pattern.difficulty?.let { difficulty ->
            Text(
                text = difficulty.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        pattern.gauge?.let { gauge ->
            Text(
                text = "Gauge: $gauge",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        pattern.yarnInfo?.let { yarnInfo ->
            Text(
                text = "Yarn: $yarnInfo",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        pattern.needleSize?.let { needleSize ->
            Text(
                text = "Needle: $needleSize",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
