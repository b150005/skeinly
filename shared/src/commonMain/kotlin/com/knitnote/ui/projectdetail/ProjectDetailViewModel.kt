package com.knitnote.ui.projectdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knitnote.domain.repository.StorageOperations
import com.knitnote.domain.model.Progress
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ShareLink
import com.knitnote.domain.model.SharePermission
import com.knitnote.domain.repository.PatternRepository
import com.knitnote.domain.repository.ProjectRepository
import com.knitnote.domain.usecase.AddProgressNoteUseCase
import com.knitnote.domain.usecase.CompleteProjectUseCase
import com.knitnote.domain.usecase.DecrementRowUseCase
import com.knitnote.domain.usecase.DeleteChartImageUseCase
import com.knitnote.domain.usecase.DeleteProgressNoteUseCase
import com.knitnote.domain.usecase.GetProgressNotesUseCase
import com.knitnote.domain.usecase.IncrementRowUseCase
import com.knitnote.domain.usecase.ReopenProjectUseCase
import com.knitnote.domain.usecase.ShareProjectUseCase
import com.knitnote.domain.usecase.UpdateProjectUseCase
import com.knitnote.domain.usecase.UploadChartImageUseCase
import com.knitnote.domain.usecase.UseCaseResult
import com.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProjectDetailState(
    val project: Project? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val shareLink: ShareLink? = null,
    val chartImagePaths: List<String> = emptyList(),
    val chartImageSignedUrls: List<String> = emptyList(),
    val isUploadingImage: Boolean = false,
    val selectedChartImageIndex: Int? = null,
)

sealed interface ProjectDetailEvent {
    data object IncrementRow : ProjectDetailEvent

    data object DecrementRow : ProjectDetailEvent

    data object ClearError : ProjectDetailEvent

    data class AddNote(val note: String) : ProjectDetailEvent

    data class DeleteNote(val progressId: String) : ProjectDetailEvent

    data class EditProject(val title: String, val totalRows: Int?) : ProjectDetailEvent

    data object CompleteProject : ProjectDetailEvent

    data object ReopenProject : ProjectDetailEvent

    data object ShareProject : ProjectDetailEvent

    data object DismissShareDialog : ProjectDetailEvent

    data class ShareWithUser(
        val userId: String,
        val permission: SharePermission,
    ) : ProjectDetailEvent

    class UploadChartImage(val data: ByteArray, val fileName: String) : ProjectDetailEvent

    data class DeleteChartImage(val imagePath: String) : ProjectDetailEvent

    data class SelectChartImage(val index: Int) : ProjectDetailEvent

    data object CloseChartViewer : ProjectDetailEvent
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
) : ViewModel() {
    private val counterMutex = Mutex()

    private val _uiOverlay = MutableStateFlow(UiOverlay())
    private val _directShareSuccessChannel = Channel<Unit>(Channel.BUFFERED)
    val directShareSuccess: Flow<Unit> = _directShareSuccessChannel.receiveAsFlow()

    val state: StateFlow<ProjectDetailState> =
        combine(
            projectRepository.observeById(projectId)
                .catch { e ->
                    _uiOverlay.update { it.copy(error = e.message ?: "Failed to load project") }
                    emit(null)
                },
            _uiOverlay,
        ) { project, overlay ->
            ProjectDetailState(
                project = project,
                isLoading = false,
                error = overlay.error,
                shareLink = overlay.shareLink,
                chartImagePaths = overlay.chartImagePaths,
                chartImageSignedUrls = overlay.chartImageSignedUrls,
                isUploadingImage = overlay.isUploadingImage,
                selectedChartImageIndex = overlay.selectedChartImageIndex,
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
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    init {
        loadChartImages()
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
                    when (val result = deleteProgressNote(event.progressId)) {
                        is UseCaseResult.Success -> { /* notes update via Flow */ }
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
            is ProjectDetailEvent.UploadChartImage -> handleUploadChartImage(event)
            is ProjectDetailEvent.DeleteChartImage -> handleDeleteChartImage(event)
            is ProjectDetailEvent.SelectChartImage -> {
                _uiOverlay.update { it.copy(selectedChartImageIndex = event.index) }
            }
            ProjectDetailEvent.CloseChartViewer -> {
                _uiOverlay.update { it.copy(selectedChartImageIndex = null) }
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
)
