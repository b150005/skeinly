package com.knitnote.ui.projectdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knitnote.domain.model.Progress
import com.knitnote.domain.model.Project
import com.knitnote.domain.repository.ProjectRepository
import com.knitnote.domain.usecase.AddProgressNoteUseCase
import com.knitnote.domain.usecase.DecrementRowUseCase
import com.knitnote.domain.usecase.DeleteProgressNoteUseCase
import com.knitnote.domain.usecase.GetProgressNotesUseCase
import com.knitnote.domain.usecase.IncrementRowUseCase
import com.knitnote.domain.usecase.UpdateProjectUseCase
import com.knitnote.domain.usecase.UseCaseResult
import com.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProjectDetailState(
    val project: Project? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

sealed interface ProjectDetailEvent {
    data object IncrementRow : ProjectDetailEvent
    data object DecrementRow : ProjectDetailEvent
    data object ClearError : ProjectDetailEvent
    data class AddNote(val note: String) : ProjectDetailEvent
    data class DeleteNote(val progressId: String) : ProjectDetailEvent
    data class EditProject(val title: String, val totalRows: Int?) : ProjectDetailEvent
}

class ProjectDetailViewModel(
    private val projectId: String,
    private val projectRepository: ProjectRepository,
    private val incrementRow: IncrementRowUseCase,
    private val decrementRow: DecrementRowUseCase,
    private val addProgressNote: AddProgressNoteUseCase,
    private val getProgressNotes: GetProgressNotesUseCase,
    private val deleteProgressNote: DeleteProgressNoteUseCase,
    private val updateProject: UpdateProjectUseCase,
) : ViewModel() {

    private val counterMutex = Mutex()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val state: StateFlow<ProjectDetailState> =
        projectRepository.observeById(projectId)
            .map { project ->
                ProjectDetailState(project = project, isLoading = false)
            }
            .catch { e ->
                emit(ProjectDetailState(isLoading = false, error = e.message))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ProjectDetailState(isLoading = true),
            )

    val progressNotes: StateFlow<List<Progress>> =
        getProgressNotes(projectId)
            .catch { e ->
                _error.value = e.message ?: "Failed to load notes"
                emit(emptyList())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    fun onEvent(event: ProjectDetailEvent) {
        when (event) {
            ProjectDetailEvent.IncrementRow -> {
                viewModelScope.launch {
                    counterMutex.withLock {
                        when (val result = incrementRow(projectId)) {
                            is UseCaseResult.Success -> { /* state updates via Flow */ }
                            is UseCaseResult.Failure -> _error.value = result.error.toMessage()
                        }
                    }
                }
            }
            ProjectDetailEvent.DecrementRow -> {
                viewModelScope.launch {
                    counterMutex.withLock {
                        when (val result = decrementRow(projectId)) {
                            is UseCaseResult.Success -> { /* state updates via Flow */ }
                            is UseCaseResult.Failure -> _error.value = result.error.toMessage()
                        }
                    }
                }
            }
            ProjectDetailEvent.ClearError -> {
                _error.value = null
            }
            is ProjectDetailEvent.AddNote -> {
                val currentRow = state.value.project?.currentRow ?: 0
                viewModelScope.launch {
                    when (val result = addProgressNote(projectId, currentRow, event.note)) {
                        is UseCaseResult.Success -> { /* notes update via Flow */ }
                        is UseCaseResult.Failure -> _error.value = result.error.toMessage()
                    }
                }
            }
            is ProjectDetailEvent.DeleteNote -> {
                viewModelScope.launch {
                    when (val result = deleteProgressNote(event.progressId)) {
                        is UseCaseResult.Success -> { /* notes update via Flow */ }
                        is UseCaseResult.Failure -> _error.value = result.error.toMessage()
                    }
                }
            }
            is ProjectDetailEvent.EditProject -> {
                viewModelScope.launch {
                    when (val result = updateProject(projectId, event.title, event.totalRows)) {
                        is UseCaseResult.Success -> { /* state updates via Flow */ }
                        is UseCaseResult.Failure -> _error.value = result.error.toMessage()
                    }
                }
            }
        }
    }
}
