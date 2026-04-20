package io.github.b150005.knitnote.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import io.github.b150005.knitnote.domain.symbol.SymbolDefinition
import io.github.b150005.knitnote.domain.usecase.CreateStructuredChartUseCase
import io.github.b150005.knitnote.domain.usecase.GetStructuredChartByPatternIdUseCase
import io.github.b150005.knitnote.domain.usecase.UpdateStructuredChartUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Default editor grid size for a freshly-authored chart. Phase 32.2 will expose
 * a picker; the MVP hardcodes an 8×8 rectangle.
 */
private const val DEFAULT_GRID_SIZE = 8
private const val DEFAULT_LAYER_ID = "L1"
private const val DEFAULT_LAYER_NAME = "Main"

data class ChartEditorState(
    val patternId: String = "",
    val isLoading: Boolean = true,
    val original: StructuredChart? = null,
    val draftExtents: ChartExtents =
        ChartExtents.Rect(minX = 0, maxX = DEFAULT_GRID_SIZE - 1, minY = 0, maxY = DEFAULT_GRID_SIZE - 1),
    val draftLayers: List<ChartLayer> = listOf(ChartLayer(id = DEFAULT_LAYER_ID, name = DEFAULT_LAYER_NAME)),
    val selectedSymbolId: String? = null,
    val selectedCategory: SymbolCategory = SymbolCategory.KNIT,
    val paletteSymbols: List<SymbolDefinition> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val hasUnsavedChanges: Boolean = false,
)

sealed interface ChartEditorEvent {
    data class SelectSymbol(
        val symbolId: String?,
    ) : ChartEditorEvent

    data class SelectCategory(
        val category: SymbolCategory,
    ) : ChartEditorEvent

    data class PlaceCell(
        val x: Int,
        val y: Int,
    ) : ChartEditorEvent

    data object Undo : ChartEditorEvent

    data object Redo : ChartEditorEvent

    data object Save : ChartEditorEvent

    data object ClearError : ChartEditorEvent
}

class ChartEditorViewModel(
    private val patternId: String,
    private val getStructuredChart: GetStructuredChartByPatternIdUseCase,
    private val createStructuredChart: CreateStructuredChartUseCase,
    private val updateStructuredChart: UpdateStructuredChartUseCase,
    private val symbolCatalog: SymbolCatalog,
) : ViewModel() {
    private val history = EditHistory()

    private val _state =
        MutableStateFlow(
            ChartEditorState(
                patternId = patternId,
                paletteSymbols = symbolCatalog.listByCategory(SymbolCategory.KNIT),
            ),
        )
    val state: StateFlow<ChartEditorState> = _state.asStateFlow()

    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved: Flow<Unit> = _saved.receiveAsFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun onEvent(event: ChartEditorEvent) {
        when (event) {
            is ChartEditorEvent.SelectSymbol -> _state.update { it.copy(selectedSymbolId = event.symbolId) }
            is ChartEditorEvent.SelectCategory ->
                _state.update {
                    it.copy(
                        selectedCategory = event.category,
                        paletteSymbols = symbolCatalog.listByCategory(event.category),
                    )
                }
            is ChartEditorEvent.PlaceCell -> placeCell(event.x, event.y)
            ChartEditorEvent.Undo -> undo()
            ChartEditorEvent.Redo -> redo()
            ChartEditorEvent.Save -> save()
            ChartEditorEvent.ClearError -> _state.update { it.copy(errorMessage = null) }
        }
    }

    private suspend fun load() {
        when (val result = getStructuredChart(patternId)) {
            is UseCaseResult.Success -> {
                val chart = result.value
                _state.update {
                    if (chart == null) {
                        it.copy(isLoading = false, original = null, hasUnsavedChanges = false)
                    } else {
                        it.copy(
                            isLoading = false,
                            original = chart,
                            draftExtents = chart.extents,
                            draftLayers = chart.layers,
                            hasUnsavedChanges = false,
                        )
                    }
                }
            }
            is UseCaseResult.Failure ->
                _state.update {
                    it.copy(isLoading = false, errorMessage = result.error.toMessage())
                }
        }
    }

    private fun placeCell(
        x: Int,
        y: Int,
    ) {
        val current = _state.value
        val layers = current.draftLayers
        if (layers.isEmpty()) return
        val targetLayer = layers[0]
        val existing = targetLayer.cells.firstOrNull { it.x == x && it.y == y }
        val selectedId = current.selectedSymbolId

        val newCells: List<ChartCell> =
            when {
                selectedId == null -> {
                    // Eraser: no-op if no existing cell.
                    if (existing == null) return
                    targetLayer.cells.filterNot { it === existing }
                }
                existing == null -> targetLayer.cells + ChartCell(symbolId = selectedId, x = x, y = y)
                else ->
                    targetLayer.cells.map { cell ->
                        if (cell === existing) cell.copy(symbolId = selectedId) else cell
                    }
            }

        recordHistory(current)
        val updatedLayers = layers.toMutableList().apply { this[0] = targetLayer.copy(cells = newCells) }
        _state.update {
            it.copy(
                draftLayers = updatedLayers,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                hasUnsavedChanges = computeUnsavedChanges(it.original, it.draftExtents, updatedLayers),
            )
        }
    }

    private fun recordHistory(current: ChartEditorState) {
        history.record(EditHistory.Snapshot(extents = current.draftExtents, layers = current.draftLayers))
    }

    private fun undo() {
        val current = _state.value
        val snapshot = EditHistory.Snapshot(extents = current.draftExtents, layers = current.draftLayers)
        val previous = history.undo(snapshot) ?: return
        _state.update {
            it.copy(
                draftExtents = previous.extents,
                draftLayers = previous.layers,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                hasUnsavedChanges = computeUnsavedChanges(it.original, previous.extents, previous.layers),
            )
        }
    }

    private fun redo() {
        val current = _state.value
        val snapshot = EditHistory.Snapshot(extents = current.draftExtents, layers = current.draftLayers)
        val next = history.redo(snapshot) ?: return
        _state.update {
            it.copy(
                draftExtents = next.extents,
                draftLayers = next.layers,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                hasUnsavedChanges = computeUnsavedChanges(it.original, next.extents, next.layers),
            )
        }
    }

    private fun save() {
        val initial = _state.value
        if (!initial.hasUnsavedChanges) return
        if (initial.isSaving) return

        _state.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            // Re-read inside the coroutine so any edit that landed between the save
            // tap and the launch is included in the payload. Reading _state.value
            // after the isSaving=true commit above is safe because `save()` rejects
            // re-entrant calls while isSaving is true.
            val snapshot = _state.value
            val original = snapshot.original
            val result =
                if (original == null) {
                    createStructuredChart(
                        patternId = patternId,
                        extents = snapshot.draftExtents,
                        layers = snapshot.draftLayers,
                    )
                } else {
                    updateStructuredChart(
                        current = original,
                        extents = snapshot.draftExtents,
                        layers = snapshot.draftLayers,
                    )
                }

            when (result) {
                is UseCaseResult.Success -> {
                    _state.update {
                        it.copy(
                            isSaving = false,
                            original = result.value,
                            draftExtents = result.value.extents,
                            draftLayers = result.value.layers,
                            hasUnsavedChanges = false,
                        )
                    }
                    _saved.send(Unit)
                }
                is UseCaseResult.Failure -> {
                    _state.update {
                        it.copy(isSaving = false, errorMessage = result.error.toMessage())
                    }
                }
            }
        }
    }

    private fun computeUnsavedChanges(
        original: StructuredChart?,
        draftExtents: ChartExtents,
        draftLayers: List<ChartLayer>,
    ): Boolean {
        if (original == null) {
            // New chart — treat as dirty only when there is at least one cell somewhere.
            return draftLayers.any { it.cells.isNotEmpty() }
        }
        return original.extents != draftExtents || original.layers != draftLayers
    }
}
