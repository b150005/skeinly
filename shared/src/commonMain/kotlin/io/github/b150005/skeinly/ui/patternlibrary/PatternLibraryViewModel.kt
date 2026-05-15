package io.github.b150005.skeinly.ui.patternlibrary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.data.wipe.WipeCompletionNotifier
import io.github.b150005.skeinly.domain.model.Difficulty
import io.github.b150005.skeinly.domain.model.Pattern
import io.github.b150005.skeinly.domain.model.SortOrder
import io.github.b150005.skeinly.domain.usecase.DeletePatternUseCase
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.GetPatternsUseCase
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toErrorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /**
     * Phase 27.2 (ADR-023 §UX) — true while the post-wipe success
     * banner is showing. Flipped on each [WipeCompletionNotifier]
     * emission; auto-clears after [WIPE_BANNER_DURATION_MS] ms.
     */
    val wipeBannerVisible: Boolean = false,
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

    /**
     * Phase 27.2 (ADR-023 §UX) — dismiss the post-wipe banner. The
     * banner also self-dismisses after [WIPE_BANNER_DURATION_MS], but
     * the user can tap to dismiss earlier.
     */
    data object DismissWipeBanner : PatternLibraryEvent
}

private data class UiFlags(
    val error: ErrorMessage? = null,
    val wipeBannerVisible: Boolean = false,
)

private data class FilterState(
    val searchQuery: String = "",
    val difficultyFilter: Difficulty? = null,
    val sortOrder: SortOrder = SortOrder.RECENT,
)

/**
 * Phase 27.2 (ADR-023 §UX) — banner stays up for 8 s after a successful
 * wipe RPC, then auto-clears. Tunable in one place so unit tests can
 * advance virtual time deterministically.
 */
internal const val WIPE_BANNER_DURATION_MS: Long = 8_000L

class PatternLibraryViewModel(
    private val getPatterns: GetPatternsUseCase,
    private val deletePattern: DeletePatternUseCase,
    // Phase 27.2 (ADR-023 §UX) — singleton event bus that signals
    // "the wipe RPC just succeeded; show the banner". Default-injected
    // via Koin; tests can pass a per-test instance to emit at the
    // right point on the virtual clock.
    private val wipeCompletionNotifier: WipeCompletionNotifier,
) : ViewModel() {
    private val uiFlags = MutableStateFlow(UiFlags())
    private val filterState = MutableStateFlow(FilterState())
    private val isLoading = MutableStateFlow(true)

    /** Tracks the currently-running banner auto-dismiss timer so a
     *  back-to-back wipe (extreme edge case — VM-layer re-entry guard
     *  prevents this from the user surface) restarts the 8 s window
     *  rather than clearing the banner mid-display. */
    private var wipeBannerTimerJob: Job? = null

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
                wipeBannerVisible = flags.wipeBannerVisible,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PatternLibraryState(),
        )

    init {
        viewModelScope.launch {
            wipeCompletionNotifier.events.collect {
                onWipeCompleted()
            }
        }
    }

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
            PatternLibraryEvent.DismissWipeBanner -> {
                wipeBannerTimerJob?.cancel()
                wipeBannerTimerJob = null
                uiFlags.update { it.copy(wipeBannerVisible = false) }
            }
        }
    }

    private fun onWipeCompleted() {
        wipeBannerTimerJob?.cancel()
        uiFlags.update { it.copy(wipeBannerVisible = true) }
        wipeBannerTimerJob =
            viewModelScope.launch {
                delay(WIPE_BANNER_DURATION_MS)
                uiFlags.update { it.copy(wipeBannerVisible = false) }
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
