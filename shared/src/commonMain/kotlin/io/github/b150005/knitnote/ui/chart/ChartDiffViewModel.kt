package io.github.b150005.knitnote.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.ChartDiff
import io.github.b150005.knitnote.domain.usecase.GetChartDiffUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State rendered by [ChartDiffScreen] (Phase 37.3, ADR-013 §6).
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
data class ChartDiffState(
    val diff: ChartDiff? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

sealed interface ChartDiffEvent {
    data object ClearError : ChartDiffEvent
}

class ChartDiffViewModel(
    private val baseRevisionId: String?,
    private val targetRevisionId: String,
    private val getChartDiff: GetChartDiffUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(ChartDiffState())
    val state: StateFlow<ChartDiffState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: ChartDiffEvent) {
        when (event) {
            ChartDiffEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val result = getChartDiff(baseRevisionId, targetRevisionId)) {
                is UseCaseResult.Success ->
                    _state.update { it.copy(diff = result.value, isLoading = false, error = null) }
                is UseCaseResult.Failure ->
                    _state.update { it.copy(diff = null, isLoading = false, error = result.error.toMessage()) }
            }
        }
    }
}
