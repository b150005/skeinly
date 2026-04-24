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

// Phase 35.2f: pattern for auto-named layers created via AddLayer. The name is
// user-visible default copy and intentionally English-only at the ViewModel
// boundary — the layer panel renders the name as-is (user can rename inline),
// and i18n of the default literal is tracked under the Phase 35.2f-ui slice.
private const val DEFAULT_LAYER_NAME_PATTERN = "Layer {n}"

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
    /**
     * Phase 35.2f: id of the layer that receives placement and symmetry writes.
     * Null is a valid state (user removed every layer) — `PlaceCell` is a no-op
     * in that case rather than auto-creating, per the ADR-011 §5 addendum.
     * Initial state matches [DEFAULT_LAYER_ID] so single-layer charts behave
     * identically to the Phase 32 `layers[0]` hardcode.
     */
    val selectedLayerId: String? = DEFAULT_LAYER_ID,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val hasUnsavedChanges: Boolean = false,
    val pendingParameterInput: PendingParameterInput? = null,
    /**
     * Phase 35.2c: when true, the next canvas tap is interpreted as "pick the
     * reflection axis stitch" rather than "place/erase a cell." Polar-only —
     * set by [ChartEditorEvent.StartPickReflectionAxis] from the symmetry menu
     * and cleared when the reflection actually applies, the user cancels, or
     * the user places a cell in a mode that invalidates the pick (e.g. switches
     * craft / reading / extents mid-pick).
     */
    val isPickingReflectionAxis: Boolean = false,
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

    /**
     * Phase 35.2c: enter axis-picking mode. The next canvas tap is rerouted
     * to [ApplyReflection] with `axisStitch` resolved from the tapped stitch
     * index. Polar-only — silently ignored on rect charts. Idempotent.
     */
    data object StartPickReflectionAxis : ChartEditorEvent

    /**
     * Phase 35.2c: exit axis-picking mode without applying. Invoked by the
     * dedicated cancel affordance on the picking-mode banner or by the
     * system-back handler when the banner is visible.
     */
    data object CancelPickReflectionAxis : ChartEditorEvent

    data object Undo : ChartEditorEvent

    data object Redo : ChartEditorEvent

    data object Save : ChartEditorEvent

    data object ClearError : ChartEditorEvent

    /**
     * Phase 35.2f layer ops. Each op writes a full-layer-list
     * [EditHistory.Snapshot] — undo walks back through reorder, rename,
     * visibility, lock, add, and remove uniformly.
     */
    data class SelectLayer(
        val layerId: String,
    ) : ChartEditorEvent

    /**
     * Append a blank layer. Id auto-generated as `L{n}` where n is one greater
     * than the maximum existing `L`-prefixed numeric id; name auto-generated
     * as `"Layer {n}"`. Auto-selects the new layer so the next placement
     * lands on it.
     */
    data object AddLayer : ChartEditorEvent

    data class RemoveLayer(
        val layerId: String,
    ) : ChartEditorEvent

    data class RenameLayer(
        val layerId: String,
        val newName: String,
    ) : ChartEditorEvent

    data class ReorderLayer(
        val fromIndex: Int,
        val toIndex: Int,
    ) : ChartEditorEvent

    data class ToggleLayerVisibility(
        val layerId: String,
    ) : ChartEditorEvent

    data class ToggleLayerLock(
        val layerId: String,
    ) : ChartEditorEvent
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
            ChartEditorEvent.StartPickReflectionAxis -> startPickReflectionAxis()
            ChartEditorEvent.CancelPickReflectionAxis -> cancelPickReflectionAxis()
            ChartEditorEvent.Undo -> undo()
            ChartEditorEvent.Redo -> redo()
            ChartEditorEvent.Save -> save()
            ChartEditorEvent.ClearError -> _state.update { it.copy(errorMessage = null) }
            is ChartEditorEvent.SelectLayer -> selectLayer(event.layerId)
            ChartEditorEvent.AddLayer -> addLayer()
            is ChartEditorEvent.RemoveLayer -> removeLayer(event.layerId)
            is ChartEditorEvent.RenameLayer -> renameLayer(event.layerId, event.newName)
            is ChartEditorEvent.ReorderLayer -> reorderLayer(event.fromIndex, event.toIndex)
            is ChartEditorEvent.ToggleLayerVisibility -> toggleLayerVisibility(event.layerId)
            is ChartEditorEvent.ToggleLayerLock -> toggleLayerLock(event.layerId)
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
                // Metadata change via the overflow menu cancels any in-flight axis pick
                // per the contract in `isPickingReflectionAxis` KDoc.
                isPickingReflectionAxis = false,
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
                // Same rationale as selectCraft — metadata changes cancel the pick.
                isPickingReflectionAxis = false,
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
                // Phase 35.2f: layer reset invalidates any non-default selection;
                // snap back to the default L1 which the reset list contains.
                selectedLayerId = DEFAULT_LAYER_ID,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                // Coordinate-system switch invalidates any in-flight axis pick —
                // stitch indices are not portable across the polar↔rect boundary.
                isPickingReflectionAxis = false,
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
                            // Phase 35.2f: pin selection to the first layer of the loaded
                            // chart. Null if the chart has no layers (a theoretically-possible
                            // malformed state — placeCell will no-op per the ADR addendum).
                            selectedLayerId = chart.layers.firstOrNull()?.id,
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
        // Phase 35.2c: reroute the tap to apply-reflection when axis-picking mode is
        // active. Polar-only guarantee: `StartPickReflectionAxis` only sets the flag on
        // polar extents (see [startPickReflectionAxis]), so [applyReflection]'s own
        // polar guard is not load-bearing here — it's a second line of defense.
        if (current.isPickingReflectionAxis) {
            applyReflection(axisStitch = x)
            return
        }
        // Phase 35.2f: route placement via selectedLayerId. A null id or a
        // locked target layer silently no-ops per the ADR-011 §5 addendum
        // lock-semantics decision.
        val targetLayer = current.selectedTargetLayer() ?: return
        if (targetLayer.locked) return
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
        // Phase 35.2f: target the selected layer. SelectLayer is blocked while
        // pendingParameterInput is set (see [selectLayer]) so the target is the
        // same layer the dialog was opened against.
        val targetLayer = current.selectedTargetLayer()
        if (targetLayer == null || targetLayer.locked) {
            _state.update { it.copy(pendingParameterInput = null) }
            return
        }
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
        // Phase 35.2f: symmetry writes follow selectedLayerId + lock guard.
        // Phase 32.2b used `layers[0]` unconditionally — symmetry and placement
        // now share the same targeting convention.
        val targetLayer = current.selectedTargetLayer() ?: return
        if (targetLayer.locked) return
        val newCells = PolarSymmetry.rotateCells(targetLayer.cells, polar, fold)
        if (newCells === targetLayer.cells) return
        if (newCells.size == targetLayer.cells.size && newCells == targetLayer.cells) return
        commitCells(current, newCells)
    }

    private fun applyReflection(axisStitch: Int) {
        val current = _state.value
        if (current.pendingParameterInput != null) return
        val polar = current.draftExtents as? ChartExtents.Polar ?: return
        val targetLayer = current.selectedTargetLayer()
        if (targetLayer == null || targetLayer.locked) {
            // Clear picking flag even on a locked / missing target so the
            // banner doesn't strand the editor in picking mode.
            if (current.isPickingReflectionAxis) {
                _state.update { it.copy(isPickingReflectionAxis = false) }
            }
            return
        }
        val newCells = PolarSymmetry.reflectCells(targetLayer.cells, polar, axisStitch)
        // Always clear the pick flag on an ApplyReflection dispatch, even when the
        // reflection is a geometric no-op (e.g. empty layer, or authored cells that
        // are already symmetric about the chosen axis). Leaving the flag set after a
        // no-op would silently strand the editor in picking mode.
        if (newCells === targetLayer.cells ||
            (newCells.size == targetLayer.cells.size && newCells == targetLayer.cells)
        ) {
            if (current.isPickingReflectionAxis) {
                _state.update { it.copy(isPickingReflectionAxis = false) }
            }
            return
        }
        commitCells(current, newCells, clearPickingAxis = current.isPickingReflectionAxis)
    }

    private fun startPickReflectionAxis() {
        val current = _state.value
        if (current.pendingParameterInput != null) return
        if (current.draftExtents !is ChartExtents.Polar) return
        if (current.isPickingReflectionAxis) return
        _state.update { it.copy(isPickingReflectionAxis = true) }
    }

    private fun cancelPickReflectionAxis() {
        val current = _state.value
        if (!current.isPickingReflectionAxis) return
        _state.update { it.copy(isPickingReflectionAxis = false) }
    }

    private fun commitCells(
        snapshotBefore: ChartEditorState,
        newCells: List<ChartCell>,
        clearPending: Boolean = false,
        clearPickingAxis: Boolean = false,
    ) {
        val layers = snapshotBefore.draftLayers
        if (layers.isEmpty()) return
        // Phase 35.2f: resolve target layer by id. Callers have already validated
        // the target is present + unlocked, so these branches are defensive.
        val targetId = snapshotBefore.selectedLayerId ?: return
        val targetIndex = layers.indexOfFirst { it.id == targetId }
        if (targetIndex < 0) return
        val targetLayer = layers[targetIndex]
        recordHistory(snapshotBefore)
        val updatedLayers =
            layers.toMutableList().apply { this[targetIndex] = targetLayer.copy(cells = newCells) }
        _state.update {
            it.copy(
                draftLayers = updatedLayers,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                pendingParameterInput = if (clearPending) null else it.pendingParameterInput,
                isPickingReflectionAxis = if (clearPickingAxis) false else it.isPickingReflectionAxis,
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
                // Phase 35.2f: reconcile selectedLayerId against the restored list.
                // If the current selection still exists, keep it; otherwise fall
                // back to the first layer (or null if the restored list is empty).
                selectedLayerId = reconcileSelectedLayer(it.selectedLayerId, previous.layers),
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                // Undo restores prior drawing state, which may predate the axis pick —
                // clear the flag so the banner + ring hint don't survive the restore.
                isPickingReflectionAxis = false,
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
                // Phase 35.2f: same reconciliation as undo — restored list may
                // predate the current selection.
                selectedLayerId = reconcileSelectedLayer(it.selectedLayerId, next.layers),
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                // Symmetric to undo — redo should not leave the banner stranded either.
                isPickingReflectionAxis = false,
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
                            // Phase 35.2f: defensive reconciliation — current
                            // UseCase implementations pass layers through
                            // unchanged, so layer ids are stable round-trip
                            // and this is a no-op. If a future UseCase ever
                            // normalizes ids, this keeps the selection valid.
                            selectedLayerId =
                                reconcileSelectedLayer(
                                    it.selectedLayerId,
                                    result.value.layers,
                                ),
                            // Defensive: a save that lands between StartPickReflectionAxis and
                            // the tap that resolves the axis should not leave the banner
                            // stranded post-save. _saved.send triggers onBack() in the normal
                            // path, so this is a narrow race guard.
                            isPickingReflectionAxis = false,
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

    // --------------------------------------------------------------------
    // Phase 35.2f: layer ops
    // --------------------------------------------------------------------

    private fun selectLayer(layerId: String) {
        val current = _state.value
        // SelectLayer is blocked while a parametric dialog is open so the
        // eventual ConfirmParameterInput commits to the layer the dialog was
        // opened against (see ADR-011 §5 addendum decision 2).
        if (current.pendingParameterInput != null) return
        if (current.selectedLayerId == layerId) return
        if (current.draftLayers.none { it.id == layerId }) return
        _state.update { it.copy(selectedLayerId = layerId) }
    }

    private fun addLayer() {
        val current = _state.value
        if (current.pendingParameterInput != null) return
        // Id scheme assumption: every layer was created by this editor and
        // follows `L{n}`. A future import path that accepts non-numeric ids
        // (e.g. `"LA"`, `"LB"`) would leave `existingNums` empty here and
        // generate `"L1"`, which may collide with an already-present literal
        // `"L1"`. Flagged in ADR-011 §5 addendum out-of-scope list; revisit
        // when import lands.
        val existingNums =
            current.draftLayers.mapNotNull { layer ->
                layer.id
                    .takeIf { it.startsWith("L") }
                    ?.removePrefix("L")
                    ?.toIntOrNull()
            }
        val nextNum = (existingNums.maxOrNull() ?: 0) + 1
        val newLayer =
            ChartLayer(
                id = "L$nextNum",
                name = DEFAULT_LAYER_NAME_PATTERN.replace("{n}", nextNum.toString()),
            )
        val updatedLayers = current.draftLayers + newLayer
        commitLayerOp(current, updatedLayers, newSelection = newLayer.id)
    }

    private fun removeLayer(layerId: String) {
        val current = _state.value
        if (current.pendingParameterInput != null) return
        val layers = current.draftLayers
        val index = layers.indexOfFirst { it.id == layerId }
        if (index < 0) return
        val updatedLayers = layers.toMutableList().apply { removeAt(index) }
        // Re-select the nearest sibling: prior layer, or next if none prior, or
        // null if the list becomes empty.
        val newSelection =
            when {
                updatedLayers.isEmpty() -> null
                // The current selection is exactly the layer being removed —
                // pick the layer now at the prior index (which is `index - 1`
                // if index > 0, else `0` which is the new first layer).
                current.selectedLayerId == layerId ->
                    updatedLayers[if (index > 0) index - 1 else 0].id
                // Another layer is selected — preserve it.
                else -> current.selectedLayerId
            }
        commitLayerOp(current, updatedLayers, newSelection = newSelection)
    }

    private fun renameLayer(
        layerId: String,
        newName: String,
    ) {
        val current = _state.value
        if (current.pendingParameterInput != null) return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val layers = current.draftLayers
        val index = layers.indexOfFirst { it.id == layerId }
        if (index < 0) return
        if (layers[index].name == trimmed) return
        val updatedLayers =
            layers.toMutableList().apply {
                this[index] = this[index].copy(name = trimmed)
            }
        commitLayerOp(current, updatedLayers, newSelection = current.selectedLayerId)
    }

    private fun reorderLayer(
        fromIndex: Int,
        toIndex: Int,
    ) {
        val current = _state.value
        if (current.pendingParameterInput != null) return
        val layers = current.draftLayers
        if (fromIndex == toIndex) return
        if (fromIndex !in layers.indices) return
        if (toIndex !in layers.indices) return
        val updatedLayers =
            layers.toMutableList().apply {
                val moving = removeAt(fromIndex)
                add(toIndex, moving)
            }
        commitLayerOp(current, updatedLayers, newSelection = current.selectedLayerId)
    }

    private fun toggleLayerVisibility(layerId: String) {
        val current = _state.value
        if (current.pendingParameterInput != null) return
        val layers = current.draftLayers
        val index = layers.indexOfFirst { it.id == layerId }
        if (index < 0) return
        val updatedLayers =
            layers.toMutableList().apply {
                this[index] = this[index].copy(visible = !this[index].visible)
            }
        commitLayerOp(current, updatedLayers, newSelection = current.selectedLayerId)
    }

    private fun toggleLayerLock(layerId: String) {
        val current = _state.value
        if (current.pendingParameterInput != null) return
        val layers = current.draftLayers
        val index = layers.indexOfFirst { it.id == layerId }
        if (index < 0) return
        val updatedLayers =
            layers.toMutableList().apply {
                this[index] = this[index].copy(locked = !this[index].locked)
            }
        commitLayerOp(current, updatedLayers, newSelection = current.selectedLayerId)
    }

    /**
     * Shared write path for every layer-list mutation. Records a full
     * [EditHistory.Snapshot] so undo walks back across the op uniformly, and
     * recomputes [ChartEditorState.hasUnsavedChanges] against [draftLayers].
     *
     * History behaviour by [newSelection] / [updatedLayers] combination:
     * - layers differ → history entry recorded, selection applied.
     * - layers equal, selection differs → no history entry, selection applied.
     *   (Pure selection changes are not undoable — matches ADR-011 §5
     *   addendum decision 2 where `SelectLayer` is excluded from the
     *   history-writing op list.)
     * - both equal → no-op early return.
     */
    private fun commitLayerOp(
        snapshotBefore: ChartEditorState,
        updatedLayers: List<ChartLayer>,
        newSelection: String?,
    ) {
        if (updatedLayers == snapshotBefore.draftLayers &&
            newSelection == snapshotBefore.selectedLayerId
        ) {
            return
        }
        if (updatedLayers != snapshotBefore.draftLayers) {
            recordHistory(snapshotBefore)
        }
        _state.update {
            it.copy(
                draftLayers = updatedLayers,
                selectedLayerId = newSelection,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                // Any layer-list mutation clears the pick banner. Strictly
                // load-bearing on RemoveLayer (target may disappear) and
                // ToggleLayerLock (target may become locked); for rename +
                // visibility toggle it is over-conservative but not a UX
                // regression — banner is dismissible and the user can
                // re-enter picking mode with one tap.
                isPickingReflectionAxis = false,
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
}

/**
 * Resolve the selected layer from [ChartEditorState.selectedLayerId]. Returns
 * null when the selection is null or no matching layer exists.
 * Exposed as an extension on the state class so handler call sites read
 * uniformly without threading a layers-by-id map.
 */
internal fun ChartEditorState.selectedTargetLayer(): ChartLayer? {
    val id = selectedLayerId ?: return null
    return draftLayers.firstOrNull { it.id == id }
}

/**
 * Clamp [previousSelection] against [restoredLayers]: keep it if the layer
 * still exists, else fall back to the first layer id (or null when the
 * restored list is empty). Used by undo / redo to survive a layer-list op
 * that removed the currently-selected layer.
 */
internal fun reconcileSelectedLayer(
    previousSelection: String?,
    restoredLayers: List<ChartLayer>,
): String? {
    if (previousSelection != null && restoredLayers.any { it.id == previousSelection }) {
        return previousSelection
    }
    return restoredLayers.firstOrNull()?.id
}
