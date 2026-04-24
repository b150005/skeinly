package io.github.b150005.knitnote.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.model.SegmentState
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.usecase.MarkRowSegmentsDoneUseCase
import io.github.b150005.knitnote.domain.usecase.MarkSegmentDoneUseCase
import io.github.b150005.knitnote.domain.usecase.ObserveProjectSegmentsUseCase
import io.github.b150005.knitnote.domain.usecase.ObserveStructuredChartUseCase
import io.github.b150005.knitnote.domain.usecase.ToggleSegmentStateUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Deterministic key for a segment in the overlay map. Absence of a key means
 * the segment is in the implicit `todo` state (ADR-010 §2).
 */
data class SegmentKey(
    val layerId: String,
    val x: Int,
    val y: Int,
)

data class ChartViewerState(
    val chart: StructuredChart? = null,
    val isLoading: Boolean = true,
    val hiddenLayerIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
    /**
     * Per-segment progress overlay. Map-based truth model — absence ⇒ `todo`.
     * Populated only when [projectId] is non-null (viewer opened from a project
     * context); viewers opened for bare pattern inspection stay at an empty map
     * and the overlay paints nothing.
     */
    val segments: Map<SegmentKey, SegmentState> = emptyMap(),
    /**
     * True for `POLAR_ROUND` charts. Phase 34 ships rect-grid only per PRD AC-1.4;
     * the viewer paints no overlay and shows an inline notice.
     */
    val isPolar: Boolean = false,
)

sealed interface ChartViewerEvent {
    data class ToggleLayer(
        val layerId: String,
    ) : ChartViewerEvent

    /** Cell tap — cycles the segment state todo → wip → done → todo (ADR-010 §2). */
    data class TapCell(
        val layerId: String,
        val x: Int,
        val y: Int,
    ) : ChartViewerEvent

    /** Long-press — forces the segment to `done` regardless of prior state. */
    data class LongPressCell(
        val layerId: String,
        val x: Int,
        val y: Int,
    ) : ChartViewerEvent

    /**
     * Long-press on a row-number (rect) or ring-number (polar) label — marks
     * every cell on that row/ring as `done` across visible layers. Per
     * ADR-011 §4; [row] maps to chart y-coordinate on rect or ring index on
     * polar without reinterpretation.
     */
    data class MarkRowDone(
        val row: Int,
    ) : ChartViewerEvent
}

class ChartViewerViewModel(
    private val patternId: String,
    private val projectId: String?,
    private val observeStructuredChart: ObserveStructuredChartUseCase,
    private val observeProjectSegments: ObserveProjectSegmentsUseCase,
    private val toggleSegmentState: ToggleSegmentStateUseCase,
    private val markSegmentDone: MarkSegmentDoneUseCase,
    private val markRowSegmentsDone: MarkRowSegmentsDoneUseCase,
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
                    _state.update {
                        it.copy(
                            chart = chart,
                            isLoading = false,
                            errorMessage = null,
                            isPolar = chart?.coordinateSystem == CoordinateSystem.POLAR_ROUND,
                        )
                    }
                }
        }

        if (projectId != null) {
            viewModelScope.launch {
                observeProjectSegments(projectId)
                    .catch { /* overlay simply stays empty on transient errors */ }
                    .collect { rows ->
                        _state.update { it.copy(segments = rows.toMap()) }
                    }
            }
        }
    }

    fun onEvent(event: ChartViewerEvent) {
        when (event) {
            is ChartViewerEvent.ToggleLayer -> toggleLayer(event.layerId)
            is ChartViewerEvent.TapCell -> tapCell(event.layerId, event.x, event.y)
            is ChartViewerEvent.LongPressCell -> longPressCell(event.layerId, event.x, event.y)
            is ChartViewerEvent.MarkRowDone -> markRowDone(event.row)
        }
    }

    /**
     * iOS bridge: reads a segment's current state from the in-flight overlay map.
     * Swift cannot as-cast the exported Kotlin `Map<SegmentKey, SegmentState>`
     * to a Swift dictionary because `SegmentKey` does not conform to Swift's
     * `Hashable` protocol out of the Kotlin/Native bridge — this helper avoids
     * the bridging footgun while keeping Compose's `state.segments` lookup fast.
     */
    fun segmentStateAt(
        layerId: String,
        x: Int,
        y: Int,
    ): SegmentState? = _state.value.segments[SegmentKey(layerId, x, y)]

    private fun toggleLayer(layerId: String) {
        _state.update { current ->
            val hidden = current.hiddenLayerIds
            val next = if (layerId in hidden) hidden - layerId else hidden + layerId
            current.copy(hiddenLayerIds = next)
        }
    }

    private fun tapCell(
        layerId: String,
        x: Int,
        y: Int,
    ) {
        val pid = projectId ?: return
        if (layerId in _state.value.hiddenLayerIds) return
        viewModelScope.launch {
            when (val result = toggleSegmentState(pid, layerId, x, y)) {
                is UseCaseResult.Success -> { /* overlay updates via Flow */ }
                is UseCaseResult.Failure ->
                    _state.update { it.copy(errorMessage = result.error.toMessage()) }
            }
        }
    }

    private fun longPressCell(
        layerId: String,
        x: Int,
        y: Int,
    ) {
        val pid = projectId ?: return
        if (layerId in _state.value.hiddenLayerIds) return
        viewModelScope.launch {
            when (val result = markSegmentDone(pid, layerId, x, y)) {
                is UseCaseResult.Success -> { /* overlay updates via Flow */ }
                is UseCaseResult.Failure ->
                    _state.update { it.copy(errorMessage = result.error.toMessage()) }
            }
        }
    }

    private fun markRowDone(row: Int) {
        val pid = projectId ?: return
        viewModelScope.launch {
            val hiddenLayerIds = _state.value.hiddenLayerIds
            when (val result = markRowSegmentsDone(patternId, pid, row, hiddenLayerIds)) {
                is UseCaseResult.Success -> { /* overlay updates via Flow */ }
                is UseCaseResult.Failure ->
                    _state.update { it.copy(errorMessage = result.error.toMessage()) }
            }
        }
    }
}

private fun List<ProjectSegment>.toMap(): Map<SegmentKey, SegmentState> =
    associate { SegmentKey(it.layerId, it.cellX, it.cellY) to it.state }
