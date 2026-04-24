package io.github.b150005.knitnote.ui.projectdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.SegmentState
import io.github.b150005.knitnote.domain.model.ShareLink
import io.github.b150005.knitnote.domain.model.SharePermission
import io.github.b150005.knitnote.domain.repository.PatternRepository
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import io.github.b150005.knitnote.domain.repository.StorageOperations
import io.github.b150005.knitnote.domain.usecase.AddProgressNoteUseCase
import io.github.b150005.knitnote.domain.usecase.CompleteProjectUseCase
import io.github.b150005.knitnote.domain.usecase.DecrementRowUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteChartImageUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteProgressNoteUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteProgressPhotoUseCase
import io.github.b150005.knitnote.domain.usecase.GetProgressNotesUseCase
import io.github.b150005.knitnote.domain.usecase.IncrementRowUseCase
import io.github.b150005.knitnote.domain.usecase.ObserveProjectSegmentsUseCase
import io.github.b150005.knitnote.domain.usecase.ObserveStructuredChartUseCase
import io.github.b150005.knitnote.domain.usecase.ReopenProjectUseCase
import io.github.b150005.knitnote.domain.usecase.ResetProjectProgressUseCase
import io.github.b150005.knitnote.domain.usecase.ShareProjectUseCase
import io.github.b150005.knitnote.domain.usecase.UpdateProjectUseCase
import io.github.b150005.knitnote.domain.usecase.UploadChartImageUseCase
import io.github.b150005.knitnote.domain.usecase.UploadProgressPhotoUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProjectDetailState(
    val project: Project? = null,
    val pattern: Pattern? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val shareLink: ShareLink? = null,
    val chartImagePaths: List<String> = emptyList(),
    val chartImageSignedUrls: List<String> = emptyList(),
    val isUploadingImage: Boolean = false,
    val selectedChartImageIndex: Int? = null,
    val photoSignedUrls: Map<String, String> = emptyMap(),
    val isUploadingPhoto: Boolean = false,
    val hasStructuredChart: Boolean = false,
    /**
     * True when the linked chart has at least one marked segment. Gates the
     * Reset progress action per PRD AC-4.1.
     */
    val hasSegmentProgress: Boolean = false,
    /**
     * Phase 34 US-7: count of DONE segments on visible layers. Null when no
     * structured chart is linked OR the chart has no placed cells (nothing to
     * summarize). WIP segments are NOT counted — "done" here means the user
     * has explicitly marked the stitch complete.
     */
    val segmentsDone: Int? = null,
    /**
     * Phase 34 US-7: total placed-cell count on visible layers. Null when no
     * structured chart is linked OR the chart has no placed cells. Cells on
     * hidden layers are excluded so the summary matches the visible overlay
     * (matches Phase 34 UI AC-1.3 semantics).
     */
    val segmentsTotal: Int? = null,
)

sealed interface ProjectDetailEvent {
    data object IncrementRow : ProjectDetailEvent

    data object DecrementRow : ProjectDetailEvent

    data object ClearError : ProjectDetailEvent

    data class AddNote(
        val note: String,
    ) : ProjectDetailEvent

    data class DeleteNote(
        val progressId: String,
    ) : ProjectDetailEvent

    data class EditProject(
        val title: String,
        val totalRows: Int?,
    ) : ProjectDetailEvent

    data object CompleteProject : ProjectDetailEvent

    data object ReopenProject : ProjectDetailEvent

    data object ShareProject : ProjectDetailEvent

    data object DismissShareDialog : ProjectDetailEvent

    data class ShareWithUser(
        val userId: String,
        val permission: SharePermission,
    ) : ProjectDetailEvent

    class AddNoteWithPhoto(
        val note: String,
        val photoData: ByteArray?,
        val photoFileName: String?,
    ) : ProjectDetailEvent

    class UploadChartImage(
        val data: ByteArray,
        val fileName: String,
    ) : ProjectDetailEvent

    data class DeleteChartImage(
        val imagePath: String,
    ) : ProjectDetailEvent

    data class SelectChartImage(
        val index: Int,
    ) : ProjectDetailEvent

    data object CloseChartViewer : ProjectDetailEvent

    /** Phase 34 US-4. Clears every segment for this project. */
    data object ResetProgress : ProjectDetailEvent
}

class ProjectDetailViewModel(
    private val projectId: String,
    private val projectRepository: ProjectRepository,
    private val patternRepository: PatternRepository,
    private val incrementRow: IncrementRowUseCase,
    private val decrementRow: DecrementRowUseCase,
    private val addProgressNote: AddProgressNoteUseCase,
    private val getProgressNotes: GetProgressNotesUseCase,
    private val deleteProgressNote: DeleteProgressNoteUseCase,
    private val updateProject: UpdateProjectUseCase,
    private val completeProject: CompleteProjectUseCase,
    private val reopenProject: ReopenProjectUseCase,
    private val shareProject: ShareProjectUseCase,
    private val uploadChartImage: UploadChartImageUseCase,
    private val deleteChartImage: DeleteChartImageUseCase,
    private val remoteStorage: StorageOperations?,
    private val uploadProgressPhoto: UploadProgressPhotoUseCase,
    private val deleteProgressPhoto: DeleteProgressPhotoUseCase,
    private val progressPhotoStorage: StorageOperations?,
    private val observeStructuredChart: ObserveStructuredChartUseCase,
    private val observeProjectSegments: ObserveProjectSegmentsUseCase,
    private val resetProjectProgress: ResetProjectProgressUseCase,
) : ViewModel() {
    private val counterMutex = Mutex()
    private var cachedPhotoPaths = emptyList<String>()

    private val _uiOverlay = MutableStateFlow(UiOverlay())
    private val _directShareSuccessChannel = Channel<Unit>(Channel.BUFFERED)
    val directShareSuccess: Flow<Unit> = _directShareSuccessChannel.receiveAsFlow()

    private val _resetProgressDoneChannel = Channel<Unit>(Channel.BUFFERED)
    val resetProgressDone: Flow<Unit> = _resetProgressDoneChannel.receiveAsFlow()

    private val segmentsFlow =
        observeProjectSegments(projectId).catch { emit(emptyList()) }

    private val projectFlow =
        projectRepository
            .observeById(projectId)
            .catch { e ->
                _uiOverlay.update { it.copy(error = e.message ?: "Failed to load project") }
                emit(null)
            }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val patternFlow =
        projectFlow.flatMapLatest { project ->
            if (project != null && project.patternId != LocalUser.DEFAULT_PATTERN_ID) {
                patternRepository.observeById(project.patternId)
            } else {
                flowOf(null)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val structuredChartFlow =
        projectFlow.flatMapLatest { project ->
            if (project != null && project.patternId != LocalUser.DEFAULT_PATTERN_ID) {
                observeStructuredChart(project.patternId)
            } else {
                flowOf(null)
            }
        }

    val state: StateFlow<ProjectDetailState> =
        combine(
            projectFlow,
            patternFlow,
            structuredChartFlow,
            _uiOverlay,
            segmentsFlow,
        ) { project, pattern, structuredChart, overlay, segments ->
            // `segments` is pre-filtered to this projectId by observeProjectSegments —
            // no per-project guard needed here. See ProjectSegmentRepository contract.
            // Each ChartCell counts as 1 toward `total` regardless of `width`: a
            // `widthUnits=2` symbol (cable crossing, widthUnits=2 picot) counts as 1
            // segment since ProjectSegment is keyed by (layerId, cellX, cellY) — one
            // row per placed ChartCell, not per physical stitch column. AC-7.1.
            val visibleLayers = structuredChart?.layers?.filter { it.visible }.orEmpty()
            // Build a set of valid (layerId, x, y) triples so orphan segments from a
            // chart edit that deleted the cell don't inflate `done > total`. The
            // set is built only from visible layers, so membership implicitly
            // enforces the hidden-layer filter (AC-1.3).
            val visibleCellKeys =
                visibleLayers
                    .flatMap { layer -> layer.cells.map { Triple(layer.id, it.x, it.y) } }
                    .toSet()
            val total = visibleCellKeys.size
            val done =
                if (total > 0) {
                    segments.count {
                        it.state == SegmentState.DONE &&
                            Triple(it.layerId, it.cellX, it.cellY) in visibleCellKeys
                    }
                } else {
                    0
                }
            ProjectDetailState(
                project = project,
                pattern = pattern,
                isLoading = false,
                error = overlay.error,
                shareLink = overlay.shareLink,
                chartImagePaths = overlay.chartImagePaths,
                chartImageSignedUrls = overlay.chartImageSignedUrls,
                isUploadingImage = overlay.isUploadingImage,
                selectedChartImageIndex = overlay.selectedChartImageIndex,
                photoSignedUrls = overlay.photoSignedUrls,
                isUploadingPhoto = overlay.isUploadingPhoto,
                hasStructuredChart = structuredChart != null,
                hasSegmentProgress = segments.isNotEmpty(),
                segmentsDone = if (structuredChart != null && total > 0) done else null,
                segmentsTotal = if (structuredChart != null && total > 0) total else null,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProjectDetailState(isLoading = true),
        )

    val progressNotes: StateFlow<List<Progress>> =
        getProgressNotes(projectId)
            .catch { e ->
                _uiOverlay.update { it.copy(error = e.message ?: "Failed to load notes") }
                emit(emptyList())
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    init {
        loadChartImages()
        loadProgressPhotoUrls()
    }

    /** Show an error from an external component (e.g., CommentSection) via the shared snackbar. */
    fun showExternalError(message: String) {
        _uiOverlay.update { it.copy(error = message) }
    }

    fun onEvent(event: ProjectDetailEvent) {
        when (event) {
            ProjectDetailEvent.IncrementRow -> {
                viewModelScope.launch {
                    counterMutex.withLock {
                        when (val result = incrementRow(projectId)) {
                            is UseCaseResult.Success -> { /* state updates via Flow */ }
                            is UseCaseResult.Failure -> _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                        }
                    }
                }
            }
            ProjectDetailEvent.DecrementRow -> {
                viewModelScope.launch {
                    counterMutex.withLock {
                        when (val result = decrementRow(projectId)) {
                            is UseCaseResult.Success -> { /* state updates via Flow */ }
                            is UseCaseResult.Failure -> _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                        }
                    }
                }
            }
            ProjectDetailEvent.ClearError -> {
                _uiOverlay.update { it.copy(error = null) }
            }
            is ProjectDetailEvent.AddNote -> {
                val currentRow = state.value.project?.currentRow ?: 0
                viewModelScope.launch {
                    when (val result = addProgressNote(projectId, currentRow, event.note)) {
                        is UseCaseResult.Success -> { /* notes update via Flow */ }
                        is UseCaseResult.Failure -> _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                    }
                }
            }
            is ProjectDetailEvent.DeleteNote -> {
                viewModelScope.launch {
                    // Look up photoUrl before deletion for cleanup
                    val notePhoto = progressNotes.value.find { it.id == event.progressId }?.photoUrl
                    when (val result = deleteProgressNote(event.progressId)) {
                        is UseCaseResult.Success -> {
                            // Best-effort delete the photo from storage; log failures to trace orphan blobs
                            if (notePhoto != null) {
                                when (val photoResult = deleteProgressPhoto(notePhoto)) {
                                    is UseCaseResult.Success -> { /* blob cleaned up */ }
                                    is UseCaseResult.Failure -> {
                                        val msg = photoResult.error.toMessage()
                                        println("[ProjectDetailVM] Orphan blob: photo delete failed for note ${event.progressId}: $msg")
                                    }
                                }
                            }
                        }
                        is UseCaseResult.Failure -> _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                    }
                }
            }
            is ProjectDetailEvent.EditProject -> {
                viewModelScope.launch {
                    when (val result = updateProject(projectId, event.title, event.totalRows)) {
                        is UseCaseResult.Success -> { /* state updates via Flow */ }
                        is UseCaseResult.Failure -> _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                    }
                }
            }
            ProjectDetailEvent.CompleteProject -> {
                viewModelScope.launch {
                    when (val result = completeProject(projectId)) {
                        is UseCaseResult.Success -> { /* state updates via Flow */ }
                        is UseCaseResult.Failure -> _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                    }
                }
            }
            ProjectDetailEvent.ReopenProject -> {
                viewModelScope.launch {
                    when (val result = reopenProject(projectId)) {
                        is UseCaseResult.Success -> { /* state updates via Flow */ }
                        is UseCaseResult.Failure -> _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                    }
                }
            }
            ProjectDetailEvent.ShareProject -> {
                viewModelScope.launch {
                    when (val result = shareProject(projectId)) {
                        is UseCaseResult.Success -> _uiOverlay.update { it.copy(shareLink = result.value) }
                        is UseCaseResult.Failure -> _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                    }
                }
            }
            ProjectDetailEvent.DismissShareDialog -> {
                _uiOverlay.update { it.copy(shareLink = null) }
            }
            is ProjectDetailEvent.ShareWithUser -> {
                viewModelScope.launch {
                    when (
                        val result =
                            shareProject(
                                projectId = projectId,
                                toUserId = event.userId,
                                permission = event.permission,
                            )
                    ) {
                        is UseCaseResult.Success -> _directShareSuccessChannel.send(Unit)
                        is UseCaseResult.Failure -> _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                    }
                }
            }
            is ProjectDetailEvent.AddNoteWithPhoto -> handleAddNoteWithPhoto(event)
            is ProjectDetailEvent.UploadChartImage -> handleUploadChartImage(event)
            is ProjectDetailEvent.DeleteChartImage -> handleDeleteChartImage(event)
            is ProjectDetailEvent.SelectChartImage -> {
                _uiOverlay.update { it.copy(selectedChartImageIndex = event.index) }
            }
            ProjectDetailEvent.CloseChartViewer -> {
                _uiOverlay.update { it.copy(selectedChartImageIndex = null) }
            }
            ProjectDetailEvent.ResetProgress -> {
                viewModelScope.launch {
                    when (val result = resetProjectProgress(projectId)) {
                        is UseCaseResult.Success -> _resetProgressDoneChannel.send(Unit)
                        is UseCaseResult.Failure -> _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                    }
                }
            }
        }
    }

    private fun loadChartImages() {
        viewModelScope.launch {
            val project = projectRepository.getById(projectId) ?: return@launch
            val pattern = patternRepository.getById(project.patternId) ?: return@launch
            val paths = pattern.chartImageUrls
            if (paths.isEmpty()) return@launch

            val signedUrls = resolveSignedUrls(paths)
            _uiOverlay.update { it.copy(chartImagePaths = paths, chartImageSignedUrls = signedUrls) }
        }
    }

    private fun handleUploadChartImage(event: ProjectDetailEvent.UploadChartImage) {
        viewModelScope.launch {
            _uiOverlay.update { it.copy(isUploadingImage = true) }
            val project = state.value.project
            if (project == null) {
                _uiOverlay.update { it.copy(isUploadingImage = false, error = "Project not loaded") }
                return@launch
            }
            when (val result = uploadChartImage(project.patternId, event.data, event.fileName)) {
                is UseCaseResult.Success -> {
                    val paths = result.value.chartImageUrls
                    val signedUrls = resolveSignedUrls(paths)
                    _uiOverlay.update {
                        it.copy(
                            isUploadingImage = false,
                            chartImagePaths = paths,
                            chartImageSignedUrls = signedUrls,
                        )
                    }
                }
                is UseCaseResult.Failure -> {
                    _uiOverlay.update { it.copy(isUploadingImage = false, error = result.error.toMessage()) }
                }
            }
        }
    }

    private fun handleDeleteChartImage(event: ProjectDetailEvent.DeleteChartImage) {
        viewModelScope.launch {
            val project = state.value.project ?: return@launch
            when (val result = deleteChartImage(project.patternId, event.imagePath)) {
                is UseCaseResult.Success -> {
                    val paths = result.value.chartImageUrls
                    val signedUrls = resolveSignedUrls(paths)
                    _uiOverlay.update {
                        it.copy(
                            chartImagePaths = paths,
                            chartImageSignedUrls = signedUrls,
                            selectedChartImageIndex = null,
                        )
                    }
                }
                is UseCaseResult.Failure -> {
                    _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                }
            }
        }
    }

    private fun handleAddNoteWithPhoto(event: ProjectDetailEvent.AddNoteWithPhoto) {
        val currentRow = state.value.project?.currentRow ?: 0
        viewModelScope.launch {
            val photoData = event.photoData
            val photoFileName = event.photoFileName
            if (photoData != null && photoFileName != null) {
                _uiOverlay.update { it.copy(isUploadingPhoto = true) }
                when (val uploadResult = uploadProgressPhoto(projectId, photoData, photoFileName)) {
                    is UseCaseResult.Success -> {
                        _uiOverlay.update { it.copy(isUploadingPhoto = false) }
                        when (val result = addProgressNote(projectId, currentRow, event.note, uploadResult.value)) {
                            is UseCaseResult.Success -> { /* notes update via Flow */ }
                            is UseCaseResult.Failure -> _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                        }
                    }
                    is UseCaseResult.Failure -> {
                        _uiOverlay.update { it.copy(isUploadingPhoto = false, error = uploadResult.error.toMessage()) }
                    }
                }
            } else {
                when (val result = addProgressNote(projectId, currentRow, event.note)) {
                    is UseCaseResult.Success -> { /* notes update via Flow */ }
                    is UseCaseResult.Failure -> _uiOverlay.update { it.copy(error = result.error.toMessage()) }
                }
            }
        }
    }

    private fun loadProgressPhotoUrls() {
        viewModelScope.launch {
            progressNotes.collect { notes ->
                val notesWithPhotos = notes.filter { it.photoUrl != null }
                if (notesWithPhotos.isEmpty() || progressPhotoStorage == null) {
                    cachedPhotoPaths = emptyList()
                    _uiOverlay.update { it.copy(photoSignedUrls = emptyMap()) }
                    return@collect
                }
                val paths = notesWithPhotos.mapNotNull { it.photoUrl }
                // Skip re-fetching when photo paths haven't changed
                if (paths == cachedPhotoPaths) return@collect
                cachedPhotoPaths = paths
                try {
                    val signedUrls = progressPhotoStorage.createSignedUrls(paths)
                    val urlMap = notesWithPhotos.zip(signedUrls).associate { (note, url) -> note.id to url }
                    _uiOverlay.update { it.copy(photoSignedUrls = urlMap) }
                } catch (_: Exception) {
                    _uiOverlay.update { it.copy(error = "Failed to load progress photos") }
                }
            }
        }
    }

    private suspend fun resolveSignedUrls(paths: List<String>): List<String> {
        val storage = remoteStorage ?: return emptyList()
        return try {
            storage.createSignedUrls(paths)
        } catch (_: Exception) {
            _uiOverlay.update { it.copy(error = "Failed to load chart images") }
            emptyList()
        }
    }
}

private data class UiOverlay(
    val error: String? = null,
    val shareLink: ShareLink? = null,
    val chartImagePaths: List<String> = emptyList(),
    val chartImageSignedUrls: List<String> = emptyList(),
    val isUploadingImage: Boolean = false,
    val selectedChartImageIndex: Int? = null,
    val photoSignedUrls: Map<String, String> = emptyMap(),
    val isUploadingPhoto: Boolean = false,
)
