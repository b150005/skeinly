package io.github.b150005.skeinly.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.domain.model.ChartComparison
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.GetChartComparisonUseCase
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State rendered by [ChartComparisonScreen] (Phase 37.3, ADR-013 §6).
 *
 * `diff` holds both source revisions plus the cell + layer change lists. The
 * screen reads `diff.base` and `diff.target` directly to drive the side-by-side
 * canvases. A null `diff` means we are still loading or the load failed; the
 * screen routes to spinner / error state accordingly.
 *
 * `error` surfaces both transient repository failures and "revision not found"
 * misses (UseCaseError.NotFound). Phase 37 has no UseCase-error-localization
 * layer yet so the raw message is shown verbatim per the project-wide
 * "ViewModel error-message localization" Tech Debt deferral.
 */
data class ChartComparisonState(
    val diff: ChartComparison? = null,
    val isLoading: Boolean = true,
    val error: ErrorMessage? = null,
)

sealed interface ChartComparisonEvent {
    data object ClearError : ChartComparisonEvent
}

class ChartComparisonViewModel(
    private val baseRevisionId: String?,
    private val targetRevisionId: String,
    private val getChartComparison: GetChartComparisonUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(ChartComparisonState())
    val state: StateFlow<ChartComparisonState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: ChartComparisonEvent) {
        when (event) {
            ChartComparisonEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val result = getChartComparison(baseRevisionId, targetRevisionId)) {
                is UseCaseResult.Success ->
                    _state.update { it.copy(diff = result.value, isLoading = false, error = null) }
                is UseCaseResult.Failure ->
                    _state.update { it.copy(diff = null, isLoading = false, error = result.error.toErrorMessage()) }
            }
        }
    }
}
