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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.b150005.knitnote.domain.model.CommentTargetType
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_add
import io.github.b150005.knitnote.generated.resources.action_add_note
import io.github.b150005.knitnote.generated.resources.action_add_photo
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_create_structured_chart
import io.github.b150005.knitnote.generated.resources.action_delete
import io.github.b150005.knitnote.generated.resources.action_delete_note
import io.github.b150005.knitnote.generated.resources.action_edit_project
import io.github.b150005.knitnote.generated.resources.action_edit_structured_chart
import io.github.b150005.knitnote.generated.resources.action_mark_complete
import io.github.b150005.knitnote.generated.resources.action_remove
import io.github.b150005.knitnote.generated.resources.action_remove_photo
import io.github.b150005.knitnote.generated.resources.action_reopen
import io.github.b150005.knitnote.generated.resources.action_reset_progress
import io.github.b150005.knitnote.generated.resources.action_save
import io.github.b150005.knitnote.generated.resources.action_share_link
import io.github.b150005.knitnote.generated.resources.action_share_with_user
import io.github.b150005.knitnote.generated.resources.action_view_structured_chart
import io.github.b150005.knitnote.generated.resources.dialog_add_note_title
import io.github.b150005.knitnote.generated.resources.dialog_delete_note_body
import io.github.b150005.knitnote.generated.resources.dialog_delete_note_title
import io.github.b150005.knitnote.generated.resources.dialog_edit_project_title
import io.github.b150005.knitnote.generated.resources.dialog_remove_chart_image_body
import io.github.b150005.knitnote.generated.resources.dialog_remove_chart_image_title
import io.github.b150005.knitnote.generated.resources.dialog_reset_progress_body
import io.github.b150005.knitnote.generated.resources.dialog_reset_progress_title
import io.github.b150005.knitnote.generated.resources.hint_note_example
import io.github.b150005.knitnote.generated.resources.label_forked_from
import io.github.b150005.knitnote.generated.resources.label_gauge_value
import io.github.b150005.knitnote.generated.resources.label_needle_value
import io.github.b150005.knitnote.generated.resources.label_note
import io.github.b150005.knitnote.generated.resources.label_note_row_timestamp
import io.github.b150005.knitnote.generated.resources.label_notes_section
import io.github.b150005.knitnote.generated.resources.label_of_rows
import io.github.b150005.knitnote.generated.resources.label_pattern_value
import io.github.b150005.knitnote.generated.resources.label_photo_attached
import io.github.b150005.knitnote.generated.resources.label_progress_photo
import io.github.b150005.knitnote.generated.resources.label_rows_only
import io.github.b150005.knitnote.generated.resources.label_segments_completed
import io.github.b150005.knitnote.generated.resources.label_someone
import io.github.b150005.knitnote.generated.resources.label_status_completed
import io.github.b150005.knitnote.generated.resources.label_status_in_progress
import io.github.b150005.knitnote.generated.resources.label_status_not_started
import io.github.b150005.knitnote.generated.resources.label_title
import io.github.b150005.knitnote.generated.resources.label_total_rows_optional
import io.github.b150005.knitnote.generated.resources.label_yarn_value
import io.github.b150005.knitnote.generated.resources.message_reset_progress_done
import io.github.b150005.knitnote.generated.resources.message_shared_successfully
import io.github.b150005.knitnote.generated.resources.state_forked_from_deleted
import io.github.b150005.knitnote.generated.resources.state_no_notes
import io.github.b150005.knitnote.generated.resources.state_project_not_found
import io.github.b150005.knitnote.generated.resources.state_uploading_photo
import io.github.b150005.knitnote.generated.resources.title_chart_history
import io.github.b150005.knitnote.generated.resources.title_pull_requests
import io.github.b150005.knitnote.ui.chartviewer.ChartImageGrid
import io.github.b150005.knitnote.ui.chartviewer.ChartImageViewer
import io.github.b150005.knitnote.ui.comments.CommentSection
import io.github.b150005.knitnote.ui.components.labelKey
import io.github.b150005.knitnote.ui.components.localized
import io.github.b150005.knitnote.ui.imagepicker.ImagePickerResult
import io.github.b150005.knitnote.ui.imagepicker.rememberImagePickerLauncher
import io.github.b150005.knitnote.ui.platform.dialogTestTagsAsResourceId
import io.github.b150005.knitnote.ui.util.formatShort
import kotlinx.coroutines.flow.collect
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    onBack: () -> Unit,
    onChartViewerClick: (String) -> Unit = {},
    onChartEditorClick: (String) -> Unit = {},
    // Phase 36.5 (ADR-012 §6): tap on the "Forked from" attribution row routes
    // to the source pattern's read-only chart viewer. Plumbed by NavGraph as
    // `ChartViewer(patternId = parentPatternId, projectId = null)` — no
    // segment overlay since the user is browsing someone else's pattern.
    onParentPatternClick: (String) -> Unit = {},
    // Phase 37.2 (ADR-013 §6): "History" link surfaces in the pattern-info
    // section when the project's pattern has a structured chart (so there is
    // at least one revision to render). Routes to ChartHistoryScreen.
    onChartHistoryClick: (String) -> Unit = {},
    // Phase 38.2 (ADR-014 §6): "Suggestions" link in the pattern-info section.
    // Default filter is Incoming when the project's pattern is the upstream
    // (`parentPatternId == null`), Outgoing when the pattern is itself a fork
    // (`parentPatternId != null`). Plumbed via the boolean param so NavGraph
    // routes with the right initial filter.
    onSuggestionsClick: (isFork: Boolean) -> Unit = { _ -> },
    viewModel: ProjectDetailViewModel = koinViewModel { parametersOf(projectId) },
) {
    val state by viewModel.state.collectAsState()
    val progressNotes by viewModel.progressNotes.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddNoteDialog by rememberSaveable { mutableStateOf(false) }
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    var showUserPickerDialog by remember { mutableStateOf(false) }
    var showResetProgressDialog by remember { mutableStateOf(false) }
    var noteToDelete by remember { mutableStateOf<String?>(null) }
    var chartImageToDelete by remember { mutableStateOf<String?>(null) }
    var progressPhotoViewerUrl by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher =
        rememberImagePickerLauncher { result ->
            if (result != null) {
                viewModel.onEvent(ProjectDetailEvent.UploadChartImage(result.data, result.fileName))
            }
        }

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
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

    val sharedSuccessMessage = stringResource(Res.string.message_shared_successfully)
    LaunchedEffect(Unit) {
        viewModel.directShareSuccess.collect {
            snackbarHostState.showSnackbar(sharedSuccessMessage)
        }
    }

    val resetProgressDoneMessage = stringResource(Res.string.message_reset_progress_done)
    LaunchedEffect(Unit) {
        viewModel.resetProgressDone.collect {
            snackbarHostState.showSnackbar(resetProgressDoneMessage)
        }
    }

    if (showResetProgressDialog) {
        AlertDialog(
            onDismissRequest = { showResetProgressDialog = false },
            title = { Text(stringResource(Res.string.dialog_reset_progress_title)) },
            text = { Text(stringResource(Res.string.dialog_reset_progress_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onEvent(ProjectDetailEvent.ResetProgress)
                        showResetProgressDialog = false
                    },
                    modifier = Modifier.testTag("resetProgressConfirmButton"),
                ) { Text(stringResource(Res.string.action_reset_progress)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetProgressDialog = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    chartImageToDelete?.let { imagePath ->
        AlertDialog(
            onDismissRequest = { chartImageToDelete = null },
            title = { Text(stringResource(Res.string.dialog_remove_chart_image_title)) },
            text = { Text(stringResource(Res.string.dialog_remove_chart_image_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onEvent(ProjectDetailEvent.DeleteChartImage(imagePath))
                    chartImageToDelete = null
                }) { Text(stringResource(Res.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { chartImageToDelete = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    noteToDelete?.let { progressId ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text(stringResource(Res.string.dialog_delete_note_title)) },
            text = { Text(stringResource(Res.string.dialog_delete_note_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onEvent(ProjectDetailEvent.DeleteNote(progressId))
                    noteToDelete = null
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.project?.title ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("backButton")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
                actions = {
                    if (state.project != null) {
                        IconButton(
                            onClick = { viewModel.onEvent(ProjectDetailEvent.ShareProject) },
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(Res.string.action_share_link),
                            )
                        }
                        IconButton(
                            onClick = { showUserPickerDialog = true },
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(Res.string.action_share_with_user),
                            )
                        }
                        IconButton(
                            onClick = { showEditDialog = true },
                            modifier = Modifier.testTag("editProjectButton"),
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(Res.string.action_edit_project),
                            )
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
                        text = stringResource(Res.string.state_project_not_found),
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
                                    stringResource(
                                        when (project.status) {
                                            ProjectStatus.NOT_STARTED -> Res.string.label_status_not_started
                                            ProjectStatus.IN_PROGRESS -> Res.string.label_status_in_progress
                                            // Shares label_status_completed with ProjectList card status chip.
                                            // The Phase-1 pre-i18n wording used "Completed!" with an
                                            // exclamation here for celebration; standardized on the
                                            // plain form during i18n rationalization.
                                            ProjectStatus.COMPLETED -> Res.string.label_status_completed
                                        },
                                    ),
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

                        // Phase 34 US-7: per-segment progress summary. Shows only when
                        // a structured chart is linked and has placed cells on visible
                        // layers. Tapping deep-links to ChartViewer for the pattern.
                        val segmentsDone = state.segmentsDone
                        val segmentsTotal = state.segmentsTotal
                        if (segmentsDone != null && segmentsTotal != null) {
                            item {
                                SegmentProgressSummary(
                                    done = segmentsDone,
                                    total = segmentsTotal,
                                    onClick = { onChartViewerClick(project.patternId) },
                                )
                            }
                        }

                        // Status toggle button
                        item {
                            StatusToggleButton(
                                status = project.status,
                                onComplete = { viewModel.onEvent(ProjectDetailEvent.CompleteProject) },
                                onReopen = { viewModel.onEvent(ProjectDetailEvent.ReopenProject) },
                            )
                        }

                        // Phase 34 US-4: Reset segment progress. Enabled only when
                        // the project has a linked structured chart AND at least one
                        // segment row exists — matches PRD AC-4.1.
                        if (state.hasStructuredChart && state.hasSegmentProgress) {
                            item {
                                ResetProgressButton(
                                    onClick = { showResetProgressDialog = true },
                                )
                            }
                        }

                        // Pattern metadata section
                        state.pattern?.let { pattern ->
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                PatternInfoSection(
                                    pattern = pattern,
                                    hasStructuredChart = state.hasStructuredChart,
                                    parentPattern = state.parentPattern,
                                    parentPatternAuthor = state.parentPatternAuthor,
                                    onChartViewerClick = {
                                        if (state.hasStructuredChart) {
                                            onChartViewerClick(pattern.id)
                                        }
                                    },
                                    onChartEditorClick = { onChartEditorClick(pattern.id) },
                                    onParentPatternClick = onParentPatternClick,
                                    onChartHistoryClick = { onChartHistoryClick(pattern.id) },
                                    onSuggestionsClick = {
                                        onSuggestionsClick(pattern.parentPatternId != null)
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
                                    text = stringResource(Res.string.label_notes_section),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                IconButton(onClick = { showAddNoteDialog = true }) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = stringResource(Res.string.action_add_note),
                                    )
                                }
                            }
                        }

                        // Notes list or empty state
                        if (progressNotes.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.state_no_notes),
                                    modifier =
                                        Modifier
                                            .padding(horizontal = 24.dp, vertical = 16.dp)
                                            .testTag("noNotesLabel"),
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
            // testTag enables locale-independent E2E assertion; the rendered
            // text is still i18n'd. Maestro flows use `id:` for presence only.
            modifier = Modifier.testTag("projectStatusLabel"),
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
                text = stringResource(Res.string.label_of_rows, totalRows),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("rowTotalLabel"),
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
                text = stringResource(Res.string.label_rows_only),
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
                    contentDescription = stringResource(Res.string.action_delete_note),
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
            Text(text = stringResource(Res.string.label_note_row_timestamp, note.rowNumber, timestamp))
        },
        trailingContent =
            if (photoSignedUrl != null) {
                {
                    AsyncImage(
                        model = photoSignedUrl,
                        contentDescription = stringResource(Res.string.label_progress_photo),
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
private fun SegmentProgressSummary(
    done: Int,
    total: Int,
    onClick: () -> Unit,
) {
    // Caller guarantees total > 0 (null-gated in ProjectDetailState), so integer
    // percent math is safe here. Floor-division matches the natural "X%" reading.
    val percent = (done * 100) / total
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.label_segments_completed, done, total, percent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .clickable(onClick = onClick)
                    .testTag("segmentProgressSummary")
                    .padding(vertical = 8.dp),
        )
    }
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
                    Text(stringResource(Res.string.action_reopen))
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
                    Text(stringResource(Res.string.action_mark_complete))
                }
            }
        }
    }
}

@Composable
private fun ResetProgressButton(onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.testTag("resetProgressButton"),
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(Res.string.action_reset_progress),
                color = MaterialTheme.colorScheme.error,
            )
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
        title = { Text(stringResource(Res.string.dialog_add_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(stringResource(Res.string.label_note)) },
                    placeholder = { Text(stringResource(Res.string.hint_note_example)) },
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
                            text = stringResource(Res.string.label_photo_attached),
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
                                contentDescription = stringResource(Res.string.action_remove_photo),
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
                        Text(stringResource(Res.string.action_add_photo))
                    }
                }

                if (isUploading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text(
                            text = stringResource(Res.string.state_uploading_photo),
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
                Text(stringResource(Res.string.action_add))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isUploading,
            ) {
                Text(stringResource(Res.string.action_cancel))
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
        // See CreateProjectDialog.kt for the dialog-cross-window semantics spike
        // rationale — `dialogTestTagsAsResourceId()` applies the Android
        // `testTagsAsResourceId = true` semantics property so Maestro `id:`
        // selectors resolve for descendant testTags inside the dialog Window.
        modifier =
            Modifier
                .testTag("editProjectDialog")
                .dialogTestTagsAsResourceId(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_edit_project_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(Res.string.label_title)) },
                    modifier = Modifier.fillMaxWidth().testTag("projectNameInput"),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = totalRowsText,
                    onValueChange = { totalRowsText = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(Res.string.label_total_rows_optional)) },
                    modifier = Modifier.fillMaxWidth().testTag("totalRowsInput"),
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
                modifier = Modifier.testTag("saveButton"),
            ) {
                Text(stringResource(Res.string.action_save))
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
private fun PatternInfoSection(
    pattern: Pattern,
    hasStructuredChart: Boolean,
    parentPattern: Pattern?,
    parentPatternAuthor: User?,
    onChartViewerClick: () -> Unit = {},
    onChartEditorClick: () -> Unit = {},
    onParentPatternClick: (String) -> Unit = {},
    onChartHistoryClick: () -> Unit = {},
    onSuggestionsClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.label_pattern_value, pattern.title),
            style = MaterialTheme.typography.titleMedium,
        )
        // Phase 36.5 (ADR-012 §6) "Forked from" attribution row. Three render
        // states keyed off `pattern.parentPatternId` + `parentPattern`:
        //   1. parentPatternId == null → not forked, render nothing.
        //   2. parentPatternId != null && parentPattern == null → source
        //      deleted/private/lookup-failed, render plain `state_forked_from_deleted`.
        //   3. parentPattern != null → render parametric `label_forked_from`.
        //      Tappable only when the source is still PUBLIC (still discoverable);
        //      private-source forks (a fork that was made while the source was
        //      public, then the author flipped to PRIVATE) render as plain text.
        if (pattern.parentPatternId != null) {
            if (parentPattern == null) {
                Text(
                    text = stringResource(Res.string.state_forked_from_deleted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("forkedFromDeletedLabel"),
                )
            } else {
                val authorName =
                    parentPatternAuthor?.displayName
                        ?: stringResource(Res.string.label_someone)
                val text =
                    stringResource(
                        Res.string.label_forked_from,
                        parentPattern.title,
                        authorName,
                    )
                if (parentPattern.visibility == Visibility.PUBLIC) {
                    // `role = Role.Button` so TalkBack announces the tappable
                    // text as a button (parity with the iOS `Button(...)` mirror).
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .clickable(role = Role.Button) {
                                    onParentPatternClick(parentPattern.id)
                                }.testTag("forkedFromLink"),
                    )
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("forkedFromLabel"),
                    )
                }
            }
        }
        if (hasStructuredChart) {
            Text(
                text = stringResource(Res.string.action_view_structured_chart),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .clickable(role = Role.Button, onClick = onChartViewerClick)
                        .testTag("openChartViewerLink"),
            )
            // Phase 37.2 (ADR-013 §6) "History" sibling link. Surfaces only
            // when a structured chart exists so there is at least one revision
            // to render — avoids a dead-end empty-state for metadata-only patterns.
            Text(
                text = stringResource(Res.string.title_chart_history),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .clickable(role = Role.Button, onClick = onChartHistoryClick)
                        .testTag("openChartHistoryLink"),
            )
        }
        // Phase 38.2 (ADR-014 §6) "Pull Requests" sibling link. Surfaces
        // unconditionally so both upstream owners (default = Incoming) and
        // forkers (default = Outgoing) can reach the PR list from
        // ProjectDetail. The default-filter selection is decided at the
        // outer screen level via `pattern.parentPatternId`.
        Text(
            text = stringResource(Res.string.title_pull_requests),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .clickable(role = Role.Button, onClick = onSuggestionsClick)
                    .testTag("openPullRequestsLink"),
        )
        Text(
            text =
                stringResource(
                    if (hasStructuredChart) {
                        Res.string.action_edit_structured_chart
                    } else {
                        Res.string.action_create_structured_chart
                    },
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .clickable(role = Role.Button, onClick = onChartEditorClick)
                    .testTag("openChartEditorLink"),
        )
        pattern.difficulty?.let { difficulty ->
            Text(
                text = stringResource(difficulty.labelKey),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        pattern.gauge?.let { gauge ->
            Text(
                text = stringResource(Res.string.label_gauge_value, gauge),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        pattern.yarnInfo?.let { yarnInfo ->
            Text(
                text = stringResource(Res.string.label_yarn_value, yarnInfo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        pattern.needleSize?.let { needleSize ->
            Text(
                text = stringResource(Res.string.label_needle_value, needleSize),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
