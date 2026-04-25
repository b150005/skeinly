package io.github.b150005.knitnote.domain.model

/**
 * Result of comparing two chart revisions per ADR-013 §5.
 *
 * The diff is the union of two orthogonal change classes:
 * - [cellChanges] enumerates per-cell edits (add, remove, modify) within layers
 *   present in BOTH revisions. Cells in layers added or removed entirely are
 *   NOT enumerated here — see [layerChanges] for the rationale.
 * - [layerChanges] enumerates layer-level edits. A `LayerAdded` / `LayerRemoved`
 *   implies every cell in that layer; the renderer is responsible for applying
 *   highlight rects across the layer's full cell footprint without an N-cell
 *   blow-up in the algorithm output.
 *
 * Position-keyed (not symbol-keyed): a cell whose `symbol_id` changes in place
 * is one [CellChange.Modified], not paired add+remove. A cell whose position
 * moved is one add + one remove (no "moved" category) — knitters care about
 * "what's at this position now vs before".
 *
 * Initial commit: when [base] is null, the diff is computed against an empty
 * chart so every target layer surfaces as [LayerChange.Added]. The UI surfaces
 * an "Initial commit" affordance in lieu of the change-summary chip.
 */
data class ChartDiff(
    val base: StructuredChart?,
    val target: StructuredChart,
    val cellChanges: List<CellChange>,
    val layerChanges: List<LayerChange>,
) {
    val isInitialCommit: Boolean = base == null

    val addedCellCount: Int = cellChanges.count { it is CellChange.Added }
    val modifiedCellCount: Int = cellChanges.count { it is CellChange.Modified }
    val removedCellCount: Int = cellChanges.count { it is CellChange.Removed }

    val hasNoChanges: Boolean = cellChanges.isEmpty() && layerChanges.isEmpty()
}

/**
 * One per-cell edit between two revisions. Position [x] / [y] is the canonical
 * address regardless of `cell.width` — wide cells (`widthUnits > 1`) are highlighted
 * across `[x, x + cell.width)` columns by the renderer per ADR-013 §5.
 */
sealed interface CellChange {
    val layerId: String
    val x: Int
    val y: Int

    data class Added(
        override val layerId: String,
        val cell: ChartCell,
    ) : CellChange {
        override val x: Int get() = cell.x
        override val y: Int get() = cell.y
    }

    data class Removed(
        override val layerId: String,
        val cell: ChartCell,
    ) : CellChange {
        override val x: Int get() = cell.x
        override val y: Int get() = cell.y
    }

    /**
     * `before.x == after.x` and `before.y == after.y` by construction (position-keyed
     * diff). [x] / [y] resolve to the shared position.
     */
    data class Modified(
        override val layerId: String,
        val before: ChartCell,
        val after: ChartCell,
    ) : CellChange {
        override val x: Int get() = before.x
        override val y: Int get() = before.y
    }
}

/**
 * One layer-level edit between two revisions. Layer id is stable across renames,
 * so a renamed layer surfaces as [PropertyChanged] (plus any cell-level diffs in
 * the same layer); only an entirely new or removed layer becomes [Added] / [Removed].
 */
sealed interface LayerChange {
    val layerId: String

    data class Added(
        val layer: ChartLayer,
    ) : LayerChange {
        override val layerId: String get() = layer.id
    }

    data class Removed(
        val layer: ChartLayer,
    ) : LayerChange {
        override val layerId: String get() = layer.id
    }

    /**
     * [before] and [after] share [layerId]; consumers diff `name` / `visible` /
     * `locked` to derive the human-readable change set. Cells are NOT compared
     * here — those belong in [CellChange] under the same layer id.
     */
    data class PropertyChanged(
        override val layerId: String,
        val before: ChartLayer,
        val after: ChartLayer,
    ) : LayerChange
}
