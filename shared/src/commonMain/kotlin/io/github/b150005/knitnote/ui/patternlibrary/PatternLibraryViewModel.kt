package io.github.b150005.knitnote.ui.patternlibrary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.SortOrder
import io.github.b150005.knitnote.domain.usecase.DeletePatternUseCase
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.GetPatternsUseCase
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

data class PatternLibraryState(
    val patterns: List<Pattern> = emptyList(),
    val isLoading: Boolean = true,
    val error: ErrorMessage? = null,
    val searchQuery: String = "",
    val difficultyFilter: Difficulty? = null,
    val sortOrder: SortOrder = SortOrder.RECENT,
)

sealed interface PatternLibraryEvent {
    data class UpdateSearchQuery(
        val query: String,
    ) : PatternLibraryEvent

    data class UpdateDifficultyFilter(
        val difficulty: Difficulty?,
    ) : PatternLibraryEvent

    data class UpdateSortOrder(
        val order: SortOrder,
    ) : PatternLibraryEvent

    data class DeletePattern(
        val id: String,
    ) : PatternLibraryEvent

    data object ClearError : PatternLibraryEvent
}

private data class UiFlags(
    val error: ErrorMessage? = null,
)

private data class FilterState(
    val searchQuery: String = "",
    val difficultyFilter: Difficulty? = null,
    val sortOrder: SortOrder = SortOrder.RECENT,
)

class PatternLibraryViewModel(
    private val getPatterns: GetPatternsUseCase,
    private val deletePattern: DeletePatternUseCase,
) : ViewModel() {
    private val uiFlags = MutableStateFlow(UiFlags())
    private val filterState = MutableStateFlow(FilterState())
    private val isLoading = MutableStateFlow(true)

    private val patternsFlow =
        getPatterns()
            .onStart { isLoading.value = true }
            .onEach { isLoading.value = false }

    val state: StateFlow<PatternLibraryState> =
        combine(
            patternsFlow,
            uiFlags,
            filterState,
            isLoading,
        ) { allPatterns, flags, filters, loading ->
            val filtered =
                allPatterns
                    .filterBySearch(filters.searchQuery)
                    .filterByDifficulty(filters.difficultyFilter)
                    .sortedBy(filters.sortOrder)

            PatternLibraryState(
                patterns = filtered,
                isLoading = loading,
                error = flags.error,
                searchQuery = filters.searchQuery,
                difficultyFilter = filters.difficultyFilter,
                sortOrder = filters.sortOrder,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PatternLibraryState(),
        )

    fun onEvent(event: PatternLibraryEvent) {
        when (event) {
            is PatternLibraryEvent.UpdateSearchQuery -> {
                filterState.update { it.copy(searchQuery = event.query) }
            }
            is PatternLibraryEvent.UpdateDifficultyFilter -> {
                filterState.update { it.copy(difficultyFilter = event.difficulty) }
            }
            is PatternLibraryEvent.UpdateSortOrder -> {
                filterState.update { it.copy(sortOrder = event.order) }
            }
            is PatternLibraryEvent.DeletePattern -> {
                viewModelScope.launch {
                    when (val result = deletePattern(event.id)) {
                        is UseCaseResult.Success -> { /* list updates via Flow */ }
                        is UseCaseResult.Failure -> {
                            uiFlags.update { it.copy(error = result.error.toErrorMessage()) }
                        }
                    }
                }
            }
            PatternLibraryEvent.ClearError -> {
                uiFlags.update { it.copy(error = null) }
            }
        }
    }

    private fun List<Pattern>.filterBySearch(query: String): List<Pattern> =
        if (query.isBlank()) this else filter { it.title.contains(query, ignoreCase = true) }

    private fun List<Pattern>.filterByDifficulty(difficulty: Difficulty?): List<Pattern> =
        if (difficulty == null) this else filter { it.difficulty == difficulty }

    private fun List<Pattern>.sortedBy(order: SortOrder): List<Pattern> =
        when (order) {
            SortOrder.RECENT -> sortedByDescending { it.createdAt }
            SortOrder.ALPHABETICAL -> sortedBy { it.title.lowercase() }
            SortOrder.PROGRESS -> this // Not applicable for patterns; keep original order
        }
}
