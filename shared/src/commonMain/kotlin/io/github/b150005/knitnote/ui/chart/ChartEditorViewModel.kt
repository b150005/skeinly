package io.github.b150005.knitnote.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.chart.PolarSymmetry
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.CraftType
import io.github.b150005.knitnote.domain.model.ReadingConvention
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.symbol.ParameterSlot
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
    val draftCraftType: CraftType = CraftType.KNIT,
    val draftReadingConvention: ReadingConvention = ReadingConvention.KNIT_FLAT,
    val selectedSymbolId: String? = null,
    val selectedCategory: SymbolCategory = SymbolCategory.KNIT,
    val paletteSymbols: List<SymbolDefinition> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val hasUnsavedChanges: Boolean = false,
    val pendingParameterInput: PendingParameterInput? = null,
)

/**
 * Deferred placement or re-edit of a parametric cell — cell is not committed until the
 * user fills the inline input and confirms. See ADR-009 §7.
 */
data class PendingParameterInput(
    val symbolId: String,
    val x: Int,
    val y: Int,
    val slots: List<ParameterSlot>,
    val currentValues: Map<String, String>,
    val isEditingExisting: Boolean,
)

sealed interface ChartEditorEvent {
    data class SelectSymbol(
        val symbolId: String?,
    ) : ChartEditorEvent

    data class SelectCategory(
        val category: SymbolCategory,
    ) : ChartEditorEvent

    data class SelectCraft(
        val craftType: CraftType,
    ) : ChartEditorEvent

    data class SelectReading(
        val readingConvention: ReadingConvention,
    ) : ChartEditorEvent

    /**
     * Phase 35.2a: switch between rect and polar authoring on a **new** chart.
     * Rejected (silent no-op) when an original chart is loaded — switching
     * coordinate systems on an existing chart would leave cells whose `(x, y)`
     * indices do not map to the new geometry. A proper mid-chart conversion
     * needs an explicit projection step, deferred to Phase 35.2+.
     */
    data class SetExtents(
        val extents: ChartExtents,
    ) : ChartEditorEvent

    data class PlaceCell(
        val x: Int,
        val y: Int,
    ) : ChartEditorEvent

    data class ConfirmParameterInput(
        val values: Map<String, String>,
    ) : ChartEditorEvent

    data object CancelParameterInput : ChartEditorEvent

    /**
     * Phase 35.2b: replicate cells on the target layer with [fold]-way
     * rotational symmetry around each ring. Polar-only — silently ignored on
     * rect charts. Rings whose `stitchesPerRing` is not divisible by [fold]
     * pass through unchanged (see [PolarSymmetry.rotateCells]).
     */
    data class ApplyRotationalSymmetry(
        val fold: Int,
    ) : ChartEditorEvent

    /**
     * Phase 35.2b: reflect cells on the target layer across the radial ray
     * at stitch index [axisStitch]. Polar-only — silently ignored on rect
     * charts. Cell `rotation` is negated on reflection
     * (see [PolarSymmetry.reflectCells]).
     */
    data class ApplyReflection(
        val axisStitch: Int,
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
            is ChartEditorEvent.SelectCraft -> selectCraft(event.craftType)
            is ChartEditorEvent.SelectReading -> selectReading(event.readingConvention)
            is ChartEditorEvent.SetExtents -> setExtents(event.extents)
            is ChartEditorEvent.PlaceCell -> placeCell(event.x, event.y)
            is ChartEditorEvent.ConfirmParameterInput -> confirmParameterInput(event.values)
            ChartEditorEvent.CancelParameterInput -> cancelParameterInput()
            is ChartEditorEvent.ApplyRotationalSymmetry -> applyRotationalSymmetry(event.fold)
            is ChartEditorEvent.ApplyReflection -> applyReflection(event.axisStitch)
            ChartEditorEvent.Undo -> undo()
            ChartEditorEvent.Redo -> redo()
            ChartEditorEvent.Save -> save()
            ChartEditorEvent.ClearError -> _state.update { it.copy(errorMessage = null) }
        }
    }

    // Metadata (craft + reading) changes intentionally do NOT touch EditHistory —
    // the snapshot ring buffer captures drawing payload only (extents + layers).
    // Metadata is round-tripped through StructuredChart at save time.
    private fun selectCraft(craft: CraftType) {
        _state.update {
            if (it.draftCraftType == craft) return@update it
            it.copy(
                draftCraftType = craft,
                hasUnsavedChanges =
                    computeUnsavedChanges(
                        original = it.original,
                        draftExtents = it.draftExtents,
                        draftLayers = it.draftLayers,
                        draftCraftType = craft,
                        draftReadingConvention = it.draftReadingConvention,
                    ),
            )
        }
    }

    private fun selectReading(reading: ReadingConvention) {
        _state.update {
            if (it.draftReadingConvention == reading) return@update it
            it.copy(
                draftReadingConvention = reading,
                hasUnsavedChanges =
                    computeUnsavedChanges(
                        original = it.original,
                        draftExtents = it.draftExtents,
                        draftLayers = it.draftLayers,
                        draftCraftType = it.draftCraftType,
                        draftReadingConvention = reading,
                    ),
            )
        }
    }

    // Coordinate-system switch on a new chart. Rejected on existing charts —
    // see ChartEditorEvent.SetExtents kdoc. Records a history snapshot so undo
    // restores the prior extents + layers; resets draftLayers to a single empty
    // layer because cell indices are not portable across coordinate systems.
    private fun setExtents(newExtents: ChartExtents) {
        val current = _state.value
        if (current.original != null) return
        if (current.pendingParameterInput != null) return
        if (current.draftExtents == newExtents) return
        recordHistory(current)
        val resetLayers = listOf(ChartLayer(id = DEFAULT_LAYER_ID, name = DEFAULT_LAYER_NAME))
        _state.update {
            it.copy(
                draftExtents = newExtents,
                draftLayers = resetLayers,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                hasUnsavedChanges =
                    computeUnsavedChanges(
                        original = it.original,
                        draftExtents = newExtents,
                        draftLayers = resetLayers,
                        draftCraftType = it.draftCraftType,
                        draftReadingConvention = it.draftReadingConvention,
                    ),
            )
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
                            draftCraftType = chart.craftType,
                            draftReadingConvention = chart.readingConvention,
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
        // Defensive: ignore placement while a parameter dialog is open — the UI is modal,
        // but suppressing here keeps the ViewModel consistent even without UI cooperation.
        if (current.pendingParameterInput != null) return
        val layers = current.draftLayers
        if (layers.isEmpty()) return
        val targetLayer = layers[0]
        val existing = targetLayer.cells.firstOrNull { it.x == x && it.y == y }
        val selectedId = current.selectedSymbolId

        // Eraser mode is orthogonal to parametric-ness — always erase immediately.
        if (selectedId == null) {
            if (existing == null) return
            commitCells(current, targetLayer.cells.filterNot { it === existing })
            return
        }

        // Parametric fork (ADR-009 §7): if the selected symbol declares parameterSlots,
        // defer the write and open the inline input dialog. Cells are only committed on
        // ConfirmParameterInput.
        val selectedDef = symbolCatalog.get(selectedId)
        if (selectedDef != null && selectedDef.parameterSlots.isNotEmpty()) {
            val reEditCell = existing?.takeIf { it.symbolId == selectedId }
            _state.update {
                it.copy(
                    pendingParameterInput =
                        PendingParameterInput(
                            symbolId = selectedId,
                            x = x,
                            y = y,
                            slots = selectedDef.parameterSlots,
                            currentValues = reEditCell?.symbolParameters ?: emptyMap(),
                            isEditingExisting = reEditCell != null,
                        ),
                )
            }
            return
        }

        // Non-parametric: immediate place or overwrite (existing MVP behavior).
        // Overwrite intentionally wipes `symbolParameters` — ADR-009 §5 guarantees
        // round-trip preservation of unknown keys in storage, not across editor mutations.
        // Replacing a parametric cell with a non-parametric one means the previous values
        // no longer correspond to any declared slot, so dropping them prevents stale data
        // from lingering in the document.
        val newCells: List<ChartCell> =
            if (existing == null) {
                targetLayer.cells + ChartCell(symbolId = selectedId, x = x, y = y)
            } else {
                targetLayer.cells.map { cell ->
                    if (cell === existing) {
                        cell.copy(symbolId = selectedId, symbolParameters = emptyMap())
                    } else {
                        cell
                    }
                }
            }
        commitCells(current, newCells)
    }

    // Concurrency invariants for confirm / cancel / commitCells:
    //   - onEvent is dispatched on a single coroutine (Main) — no interleaved mutation.
    //   - placeCell / undo / redo all early-return when pendingParameterInput is set, so
    //     nothing can rewrite draftLayers between placeCell opening the dialog and
    //     confirmParameterInput committing.
    //   - save() is the only async path; it does not touch pendingParameterInput and
    //     short-circuits when !hasUnsavedChanges, so it cannot interleave with an
    //     in-flight dialog either.
    // This keeps the read-derive-write pattern below race-free without needing to move
    // the derivation inside _state.update (which would make recordHistory non-idempotent).

    private fun confirmParameterInput(values: Map<String, String>) {
        val current = _state.value
        val pending = current.pendingParameterInput ?: return
        val layers = current.draftLayers
        if (layers.isEmpty()) {
            _state.update { it.copy(pendingParameterInput = null) }
            return
        }
        val targetLayer = layers[0]
        val existing = targetLayer.cells.firstOrNull { it.x == pending.x && it.y == pending.y }

        val newCells: List<ChartCell> =
            if (existing == null) {
                targetLayer.cells +
                    ChartCell(
                        symbolId = pending.symbolId,
                        x = pending.x,
                        y = pending.y,
                        symbolParameters = values,
                    )
            } else {
                targetLayer.cells.map { cell ->
                    if (cell === existing) {
                        cell.copy(symbolId = pending.symbolId, symbolParameters = values)
                    } else {
                        cell
                    }
                }
            }
        commitCells(current, newCells, clearPending = true)
    }

    private fun cancelParameterInput() {
        _state.update { it.copy(pendingParameterInput = null) }
    }

    // Phase 35.2b polar authoring ops. Both ops mutate the target layer (MVP = layers[0])
    // and route through commitCells so EditHistory + hasUnsavedChanges stay consistent with
    // the tap-to-place path. Gated on polar extents and no in-flight parametric dialog.
    private fun applyRotationalSymmetry(fold: Int) {
        val current = _state.value
        if (current.pendingParameterInput != null) return
        val polar = current.draftExtents as? ChartExtents.Polar ?: return
        val layers = current.draftLayers
        if (layers.isEmpty()) return
        val targetLayer = layers[0]
        val newCells = PolarSymmetry.rotateCells(targetLayer.cells, polar, fold)
        if (newCells === targetLayer.cells) return
        if (newCells.size == targetLayer.cells.size && newCells == targetLayer.cells) return
        commitCells(current, newCells)
    }

    private fun applyReflection(axisStitch: Int) {
        val current = _state.value
        if (current.pendingParameterInput != null) return
        val polar = current.draftExtents as? ChartExtents.Polar ?: return
        val layers = current.draftLayers
        if (layers.isEmpty()) return
        val targetLayer = layers[0]
        val newCells = PolarSymmetry.reflectCells(targetLayer.cells, polar, axisStitch)
        if (newCells === targetLayer.cells) return
        if (newCells.size == targetLayer.cells.size && newCells == targetLayer.cells) return
        commitCells(current, newCells)
    }

    private fun commitCells(
        snapshotBefore: ChartEditorState,
        newCells: List<ChartCell>,
        clearPending: Boolean = false,
    ) {
        val layers = snapshotBefore.draftLayers
        if (layers.isEmpty()) return
        val targetLayer = layers[0]
        recordHistory(snapshotBefore)
        val updatedLayers = layers.toMutableList().apply { this[0] = targetLayer.copy(cells = newCells) }
        _state.update {
            it.copy(
                draftLayers = updatedLayers,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                pendingParameterInput = if (clearPending) null else it.pendingParameterInput,
                hasUnsavedChanges =
                    computeUnsavedChanges(
                        original = it.original,
                        draftExtents = it.draftExtents,
                        draftLayers = updatedLayers,
                        draftCraftType = it.draftCraftType,
                        draftReadingConvention = it.draftReadingConvention,
                    ),
            )
        }
    }

    private fun recordHistory(current: ChartEditorState) {
        history.record(EditHistory.Snapshot(extents = current.draftExtents, layers = current.draftLayers))
    }

    private fun undo() {
        val current = _state.value
        // Block undo while a parametric dialog is open — an undo between placeCell opening
        // the dialog and confirmParameterInput committing would leave the pending input
        // pointing at a cell whose context has been rewritten.
        if (current.pendingParameterInput != null) return
        val snapshot = EditHistory.Snapshot(extents = current.draftExtents, layers = current.draftLayers)
        val previous = history.undo(snapshot) ?: return
        _state.update {
            it.copy(
                draftExtents = previous.extents,
                draftLayers = previous.layers,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                hasUnsavedChanges =
                    computeUnsavedChanges(
                        original = it.original,
                        draftExtents = previous.extents,
                        draftLayers = previous.layers,
                        draftCraftType = it.draftCraftType,
                        draftReadingConvention = it.draftReadingConvention,
                    ),
            )
        }
    }

    private fun redo() {
        val current = _state.value
        if (current.pendingParameterInput != null) return
        val snapshot = EditHistory.Snapshot(extents = current.draftExtents, layers = current.draftLayers)
        val next = history.redo(snapshot) ?: return
        _state.update {
            it.copy(
                draftExtents = next.extents,
                draftLayers = next.layers,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                hasUnsavedChanges =
                    computeUnsavedChanges(
                        original = it.original,
                        draftExtents = next.extents,
                        draftLayers = next.layers,
                        draftCraftType = it.draftCraftType,
                        draftReadingConvention = it.draftReadingConvention,
                    ),
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
                    val coordinateSystem =
                        when (snapshot.draftExtents) {
                            is ChartExtents.Rect -> CoordinateSystem.RECT_GRID
                            is ChartExtents.Polar -> CoordinateSystem.POLAR_ROUND
                        }
                    createStructuredChart(
                        patternId = patternId,
                        coordinateSystem = coordinateSystem,
                        extents = snapshot.draftExtents,
                        layers = snapshot.draftLayers,
                        craftType = snapshot.draftCraftType,
                        readingConvention = snapshot.draftReadingConvention,
                    )
                } else {
                    updateStructuredChart(
                        current = original,
                        extents = snapshot.draftExtents,
                        layers = snapshot.draftLayers,
                        craftType = snapshot.draftCraftType,
                        readingConvention = snapshot.draftReadingConvention,
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
                            draftCraftType = result.value.craftType,
                            draftReadingConvention = result.value.readingConvention,
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
        draftCraftType: CraftType,
        draftReadingConvention: ReadingConvention,
    ): Boolean {
        if (original == null) {
            // New chart — treat as dirty only when there is at least one cell somewhere.
            // Metadata changes alone on a never-saved chart don't count as dirty because
            // Create requires at least one cell-bearing layer to be meaningful.
            return draftLayers.any { it.cells.isNotEmpty() }
        }
        return original.extents != draftExtents ||
            original.layers != draftLayers ||
            original.craftType != draftCraftType ||
            original.readingConvention != draftReadingConvention
    }
}
