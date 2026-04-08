package com.knitnote.ui.projectlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knitnote.domain.model.Project
import com.knitnote.domain.usecase.CreateProjectUseCase
import com.knitnote.domain.usecase.DeleteProjectUseCase
import com.knitnote.domain.usecase.GetProjectsUseCase
import com.knitnote.domain.usecase.UseCaseResult
import com.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectListState(
    val projects: List<Project> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val showCreateDialog: Boolean = false,
)

sealed interface ProjectListEvent {
    data class CreateProject(val title: String, val totalRows: Int?) : ProjectListEvent
    data class DeleteProject(val id: String) : ProjectListEvent
    data object ShowCreateDialog : ProjectListEvent
    data object DismissCreateDialog : ProjectListEvent
}

class ProjectListViewModel(
    private val getProjects: GetProjectsUseCase,
    private val createProject: CreateProjectUseCase,
    private val deleteProject: DeleteProjectUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectListState())
    val state: StateFlow<ProjectListState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            getProjects()
                .catch { e ->
                    _state.update { it.copy(isLoading = false, error = e.message) }
                }
                .collect { projects ->
                    _state.update { it.copy(projects = projects, isLoading = false, error = null) }
                }
        }
    }

    fun onEvent(event: ProjectListEvent) {
        when (event) {
            is ProjectListEvent.CreateProject -> {
                viewModelScope.launch {
                    when (val result = createProject(event.title, event.totalRows)) {
                        is UseCaseResult.Success -> {
                            _state.update { it.copy(showCreateDialog = false) }
                        }
                        is UseCaseResult.Failure -> {
                            _state.update { it.copy(error = result.error.toMessage()) }
                        }
                    }
                }
            }
            is ProjectListEvent.DeleteProject -> {
                viewModelScope.launch {
                    when (val result = deleteProject(event.id)) {
                        is UseCaseResult.Success -> { /* list updates via Flow */ }
                        is UseCaseResult.Failure -> {
                            _state.update { it.copy(error = result.error.toMessage()) }
                        }
                    }
                }
            }
            ProjectListEvent.ShowCreateDialog -> {
                _state.update { it.copy(showCreateDialog = true) }
            }
            ProjectListEvent.DismissCreateDialog -> {
                _state.update { it.copy(showCreateDialog = false) }
            }
        }
    }
}
