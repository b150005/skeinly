package io.github.b150005.knitnote.ui.projectlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.model.SortOrder
import io.github.b150005.knitnote.domain.usecase.CreateProjectUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteProjectUseCase
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.GetPatternsUseCase
import io.github.b150005.knitnote.domain.usecase.GetProjectsUseCase
import io.github.b150005.knitnote.domain.usecase.SignOutUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectListState(
    val projects: List<Project> = emptyList(),
    val patternsForCreate: List<Pattern> = emptyList(),
    val isLoading: Boolean = true,
    val error: ErrorMessage? = null,
    val showCreateDialog: Boolean = false,
    val searchQuery: String = "",
    val statusFilter: ProjectStatus? = null,
    val sortOrder: SortOrder = SortOrder.RECENT,
)

sealed interface ProjectListEvent {
    data class CreateProject(
        val title: String,
        val totalRows: Int?,
        val patternId: String? = null,
    ) : ProjectListEvent

    data class DeleteProject(
        val id: String,
    ) : ProjectListEvent

    data object ShowCreateDialog : ProjectListEvent

    data object DismissCreateDialog : ProjectListEvent

    data object ClearError : ProjectListEvent

    data object SignOut : ProjectListEvent

    data class UpdateSearchQuery(
        val query: String,
    ) : ProjectListEvent

    data class UpdateStatusFilter(
        val status: ProjectStatus?,
    ) : ProjectListEvent

    data class UpdateSortOrder(
        val order: SortOrder,
    ) : ProjectListEvent
}

private data class UiFlags(
    val error: ErrorMessage? = null,
    val showCreateDialog: Boolean = false,
)

private data class FilterState(
    val searchQuery: String = "",
    val statusFilter: ProjectStatus? = null,
    val sortOrder: SortOrder = SortOrder.RECENT,
)

class ProjectListViewModel(
    private val getProjects: GetProjectsUseCase,
    private val getPatterns: GetPatternsUseCase,
    private val createProject: CreateProjectUseCase,
    private val deleteProject: DeleteProjectUseCase,
    private val signOut: SignOutUseCase,
) : ViewModel() {
    private val uiFlags = MutableStateFlow(UiFlags())
    private val filterState = MutableStateFlow(FilterState())
    private val isLoading = MutableStateFlow(true)

    private val projectsFlow =
        getProjects()
            .onStart { isLoading.value = true }
            .onEach { isLoading.value = false }

    private val _patterns = MutableStateFlow<List<Pattern>>(emptyList())

    init {
        viewModelScope.launch {
            getPatterns().collect { _patterns.value = it }
        }
    }

    val state: StateFlow<ProjectListState> =
        combine(
            projectsFlow,
            uiFlags,
            filterState,
            isLoading,
        ) { allProjects, flags, filters, loading ->
            val filtered =
                allProjects
                    .filterBySearch(filters.searchQuery)
                    .filterByStatus(filters.statusFilter)
                    .sortedBy(filters.sortOrder)

            ProjectListState(
                projects = filtered,
                patternsForCreate = _patterns.value,
                isLoading = loading,
                error = flags.error,
                showCreateDialog = flags.showCreateDialog,
                searchQuery = filters.searchQuery,
                statusFilter = filters.statusFilter,
                sortOrder = filters.sortOrder,
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
                    when (val result = createProject(event.title, event.totalRows, event.patternId)) {
                        is UseCaseResult.Success -> {
                            uiFlags.update { it.copy(showCreateDialog = false) }
                        }
                        is UseCaseResult.Failure -> {
                            uiFlags.update { it.copy(error = result.error.toErrorMessage()) }
                        }
                    }
                }
            }
            is ProjectListEvent.DeleteProject -> {
                viewModelScope.launch {
                    when (val result = deleteProject(event.id)) {
                        is UseCaseResult.Success -> { /* list updates via Flow */ }
                        is UseCaseResult.Failure -> {
                            uiFlags.update { it.copy(error = result.error.toErrorMessage()) }
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
                            uiFlags.update { it.copy(error = result.error.toErrorMessage()) }
                        }
                    }
                }
            }
            is ProjectListEvent.UpdateSearchQuery -> {
                val capped = event.query.take(MAX_SEARCH_QUERY_LENGTH)
                filterState.update { it.copy(searchQuery = capped) }
            }
            is ProjectListEvent.UpdateStatusFilter -> {
                filterState.update { it.copy(statusFilter = event.status) }
            }
            is ProjectListEvent.UpdateSortOrder -> {
                filterState.update { it.copy(sortOrder = event.order) }
            }
        }
    }
}

private const val MAX_SEARCH_QUERY_LENGTH = 200

private fun List<Project>.filterBySearch(query: String): List<Project> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return filter { it.title.contains(trimmed, ignoreCase = true) }
}

private fun List<Project>.filterByStatus(status: ProjectStatus?): List<Project> {
    if (status == null) return this
    return filter { it.status == status }
}

private fun List<Project>.sortedBy(order: SortOrder): List<Project> =
    when (order) {
        SortOrder.RECENT -> sortedByDescending { it.createdAt }
        SortOrder.ALPHABETICAL -> sortedBy { it.title.lowercase() }
        SortOrder.PROGRESS ->
            sortedByDescending { project ->
                val total = project.totalRows
                if (total != null && total > 0) {
                    project.currentRow.toFloat() / total.toFloat()
                } else {
                    -1f
                }
            }
    }
