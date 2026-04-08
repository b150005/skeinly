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
}

class ProjectDetailViewModel(
    private val projectId: String,
    private val projectRepository: ProjectRepository,
    private val incrementRow: IncrementRowUseCase,
    private val decrementRow: DecrementRowUseCase,
    private val addProgressNote: AddProgressNoteUseCase,
    private val getProgressNotes: GetProgressNotesUseCase,
    private val deleteProgressNote: DeleteProgressNoteUseCase,
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
                        runCatching { incrementRow(projectId) }
                            .onFailure { e -> _error.value = e.message ?: "Failed to increment" }
                    }
                }
            }
            ProjectDetailEvent.DecrementRow -> {
                viewModelScope.launch {
                    counterMutex.withLock {
                        runCatching { decrementRow(projectId) }
                            .onFailure { e -> _error.value = e.message ?: "Failed to decrement" }
                    }
                }
            }
            ProjectDetailEvent.ClearError -> {
                _error.value = null
            }
            is ProjectDetailEvent.AddNote -> {
                val currentRow = state.value.project?.currentRow ?: 0
                viewModelScope.launch {
                    runCatching { addProgressNote(projectId, currentRow, event.note) }
                        .onFailure { e -> _error.value = e.message ?: "Failed to add note" }
                }
            }
            is ProjectDetailEvent.DeleteNote -> {
                viewModelScope.launch {
                    runCatching { deleteProgressNote(event.progressId) }
                        .onFailure { e -> _error.value = e.message ?: "Failed to delete note" }
                }
            }
        }
    }
}
