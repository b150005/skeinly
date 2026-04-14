package com.knitnote.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knitnote.domain.model.Difficulty
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.SortOrder
import com.knitnote.domain.usecase.ForkPublicPatternUseCase
import com.knitnote.domain.usecase.GetPublicPatternsUseCase
import com.knitnote.domain.usecase.UseCaseResult
import com.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoveryState(
    val patterns: List<Pattern> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
    val difficultyFilter: Difficulty? = null,
    val sortOrder: SortOrder = SortOrder.RECENT,
    val forkingPatternId: String? = null,
)

sealed interface DiscoveryEvent {
    data class UpdateSearchQuery(
        val query: String,
    ) : DiscoveryEvent

    data class UpdateDifficultyFilter(
        val difficulty: Difficulty?,
    ) : DiscoveryEvent

    data class UpdateSortOrder(
        val order: SortOrder,
    ) : DiscoveryEvent

    data class ForkPattern(
        val patternId: String,
    ) : DiscoveryEvent

    data object Refresh : DiscoveryEvent

    data object ClearError : DiscoveryEvent
}

private data class UiFlags(
    val error: String? = null,
    val forkingPatternId: String? = null,
)

private data class FilterState(
    val searchQuery: String = "",
    val difficultyFilter: Difficulty? = null,
    val sortOrder: SortOrder = SortOrder.RECENT,
)

class DiscoveryViewModel(
    private val getPublicPatterns: GetPublicPatternsUseCase,
    private val forkPublicPattern: ForkPublicPatternUseCase,
) : ViewModel() {
    private val rawPatterns = MutableStateFlow<List<Pattern>>(emptyList())
    private val uiFlags = MutableStateFlow(UiFlags())
    private val filterState = MutableStateFlow(FilterState())
    private val isLoading = MutableStateFlow(true)

    private val _forkedProjectChannel = Channel<String>(Channel.BUFFERED)
    val forkedProjectId: Flow<String> = _forkedProjectChannel.receiveAsFlow()

    private var searchJob: Job? = null

    val state: StateFlow<DiscoveryState> =
        combine(
            rawPatterns,
            uiFlags,
            filterState,
            isLoading,
        ) { patterns, flags, filters, loading ->
            val filtered =
                patterns
                    .filterBySearch(filters.searchQuery)
                    .filterByDifficulty(filters.difficultyFilter)
                    .sortedBy(filters.sortOrder)

            DiscoveryState(
                patterns = filtered,
                isLoading = loading,
                error = flags.error,
                searchQuery = filters.searchQuery,
                difficultyFilter = filters.difficultyFilter,
                sortOrder = filters.sortOrder,
                forkingPatternId = flags.forkingPatternId,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DiscoveryState(),
        )

    init {
        load("")
    }

    fun onEvent(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.UpdateSearchQuery -> {
                filterState.update { it.copy(searchQuery = event.query) }
                debouncedSearch(event.query)
            }
            is DiscoveryEvent.UpdateDifficultyFilter -> {
                filterState.update { it.copy(difficultyFilter = event.difficulty) }
            }
            is DiscoveryEvent.UpdateSortOrder -> {
                filterState.update { it.copy(sortOrder = event.order) }
            }
            is DiscoveryEvent.ForkPattern -> fork(event.patternId)
            DiscoveryEvent.Refresh -> load(filterState.value.searchQuery)
            DiscoveryEvent.ClearError -> {
                uiFlags.update { it.copy(error = null) }
            }
        }
    }

    private fun debouncedSearch(query: String) {
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                load(query)
            }
    }

    private fun load(searchQuery: String) {
        viewModelScope.launch {
            isLoading.value = true
            when (val result = getPublicPatterns(searchQuery)) {
                is UseCaseResult.Success -> {
                    rawPatterns.value = result.value
                    isLoading.value = false
                }
                is UseCaseResult.Failure -> {
                    uiFlags.update { it.copy(error = result.error.toMessage()) }
                    isLoading.value = false
                }
            }
        }
    }

    private fun fork(patternId: String) {
        viewModelScope.launch {
            uiFlags.update { it.copy(forkingPatternId = patternId) }
            when (val result = forkPublicPattern(patternId)) {
                is UseCaseResult.Success -> {
                    uiFlags.update { it.copy(forkingPatternId = null) }
                    _forkedProjectChannel.send(result.value.project.id)
                }
                is UseCaseResult.Failure -> {
                    uiFlags.update { it.copy(forkingPatternId = null, error = result.error.toMessage()) }
                }
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
            SortOrder.PROGRESS -> this
        }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
