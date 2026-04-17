package io.github.b150005.knitnote.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.usecase.ObserveStructuredChartUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChartViewerState(
    val chart: StructuredChart? = null,
    val isLoading: Boolean = true,
    val hiddenLayerIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
)

sealed interface ChartViewerEvent {
    data class ToggleLayer(
        val layerId: String,
    ) : ChartViewerEvent
}

class ChartViewerViewModel(
    private val patternId: String,
    private val observeStructuredChart: ObserveStructuredChartUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(ChartViewerState())
    val state: StateFlow<ChartViewerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeStructuredChart(patternId)
                .catch { throwable ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = throwable.message ?: "Failed to load chart",
                        )
                    }
                }.collect { chart ->
                    _state.update { it.copy(chart = chart, isLoading = false, errorMessage = null) }
                }
        }
    }

    fun onEvent(event: ChartViewerEvent) {
        when (event) {
            is ChartViewerEvent.ToggleLayer -> toggleLayer(event.layerId)
        }
    }

    private fun toggleLayer(layerId: String) {
        _state.update { current ->
            val hidden = current.hiddenLayerIds
            val next = if (layerId in hidden) hidden - layerId else hidden + layerId
            current.copy(hiddenLayerIds = next)
        }
    }
}
