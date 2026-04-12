package com.knitnote.ui.projectlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knitnote.domain.model.Project
import com.knitnote.domain.usecase.CreateProjectUseCase
import com.knitnote.domain.usecase.DeleteProjectUseCase
import com.knitnote.domain.usecase.GetProjectsUseCase
import com.knitnote.domain.usecase.SignOutUseCase
import com.knitnote.domain.usecase.UseCaseResult
import com.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectListState(
    val projects: List<Project> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val showCreateDialog: Boolean = false,
)

sealed interface ProjectListEvent {
    data class CreateProject(
        val title: String,
        val totalRows: Int?,
    ) : ProjectListEvent

    data class DeleteProject(
        val id: String,
    ) : ProjectListEvent

    data object ShowCreateDialog : ProjectListEvent

    data object DismissCreateDialog : ProjectListEvent

    data object ClearError : ProjectListEvent

    data object SignOut : ProjectListEvent
}

private data class UiFlags(
    val error: String? = null,
    val showCreateDialog: Boolean = false,
)

class ProjectListViewModel(
    private val getProjects: GetProjectsUseCase,
    private val createProject: CreateProjectUseCase,
    private val deleteProject: DeleteProjectUseCase,
    private val signOut: SignOutUseCase,
) : ViewModel() {
    private val uiFlags = MutableStateFlow(UiFlags())

    val state: StateFlow<ProjectListState> =
        combine(
            getProjects().catch { e ->
                uiFlags.update { it.copy(error = e.message) }
                emit(emptyList())
            },
            uiFlags,
        ) { projects, flags ->
            ProjectListState(
                projects = projects,
                isLoading = false,
                error = flags.error,
                showCreateDialog = flags.showCreateDialog,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProjectListState(),
        )

    fun onEvent(event: ProjectListEvent) {
        when (event) {
            is ProjectListEvent.CreateProject -> {
                viewModelScope.launch {
                    when (val result = createProject(event.title, event.totalRows)) {
                        is UseCaseResult.Success -> {
                            uiFlags.update { it.copy(showCreateDialog = false) }
                        }
                        is UseCaseResult.Failure -> {
                            uiFlags.update { it.copy(error = result.error.toMessage()) }
                        }
                    }
                }
            }
            is ProjectListEvent.DeleteProject -> {
                viewModelScope.launch {
                    when (val result = deleteProject(event.id)) {
                        is UseCaseResult.Success -> { /* list updates via Flow */ }
                        is UseCaseResult.Failure -> {
                            uiFlags.update { it.copy(error = result.error.toMessage()) }
                        }
                    }
                }
            }
            ProjectListEvent.ShowCreateDialog -> {
                uiFlags.update { it.copy(showCreateDialog = true) }
            }
            ProjectListEvent.DismissCreateDialog -> {
                uiFlags.update { it.copy(showCreateDialog = false) }
            }
            ProjectListEvent.ClearError -> {
                uiFlags.update { it.copy(error = null) }
            }
            ProjectListEvent.SignOut -> {
                viewModelScope.launch {
                    when (val result = signOut()) {
                        is UseCaseResult.Success -> { /* NavGraph observes AuthState.Unauthenticated */ }
                        is UseCaseResult.Failure -> {
                            uiFlags.update { it.copy(error = result.error.toMessage()) }
                        }
                    }
                }
            }
        }
    }
}
