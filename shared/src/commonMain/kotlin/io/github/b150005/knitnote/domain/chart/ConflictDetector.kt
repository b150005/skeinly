package io.github.b150005.knitnote.domain.chart

import io.github.b150005.knitnote.domain.model.CellChange
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.LayerChange
import io.github.b150005.knitnote.domain.model.StructuredChart

/**
 * Phase 38.4 (ADR-014 §4) — pure 3-way conflict detection wrapper over
 * [ChartDiffAlgorithm].
 *
 * Compares ancestor / theirs / mine and partitions every per-cell-coordinate
 * change into three buckets:
 * - [ConflictReport.autoFromTheirs]: cell changed only on the source side.
 * - [ConflictReport.autoFromMine]: cell changed only on the target side.
 * - [ConflictReport.conflicts]: cell changed on both sides with different
 *   target values. Two sides producing the *same* target cell is rare but
 *   possible (both decided to draw the same symbol at the same coordinate);
 *   that case is auto-resolved with no user prompt — both contributions agree
 *   and either side's value can be applied.
 *
 * Layer-level edits are partitioned the same way into [layerConflicts] (both
 * sides modified the same layer differently) plus the layer-only changes
 * surfaced in [autoLayerFromTheirs] / [autoLayerFromMine].
 *
 * Polar charts are handled identically — `(cell.x, cell.y)` is `(stitch, ring)`
 * regardless of [io.github.b150005.knitnote.domain.model.CoordinateSystem]
 * (matches Phase 37 §5). Wide cells use the top-left anchor as the key.
 *
 * Algorithm complexity is O(N + M) over total cell counts since the underlying
 * [ChartDiffAlgorithm.diff] is already O(N + M) and the per-key partitioning
 * is one extra pass over the union of changed coordinates.
 */
object ConflictDetector {
    fun detect(
        ancestor: StructuredChart,
        theirs: StructuredChart,
        mine: StructuredChart,
    ): ConflictReport {
        val theirDiff = ChartDiffAlgorithm.diff(ancestor, theirs)
        val mineDiff = ChartDiffAlgorithm.diff(ancestor, mine)

        // Index cell changes by (layerId, x, y) so the union pass is a single
        // map lookup per coordinate. Wide cells anchor at top-left, matching
        // Phase 37 §5; the (x, y) read on CellChange already routes through
        // the cell's anchor coordinates.
        val theirCellChanges =
            theirDiff.cellChanges.associateBy { CellCoordinate(it.layerId, it.x, it.y) }
        val mineCellChanges =
            mineDiff.cellChanges.associateBy { CellCoordinate(it.layerId, it.x, it.y) }

        val autoFromTheirs = mutableListOf<CellChange>()
        val autoFromMine = mutableListOf<CellChange>()
        val conflicts = mutableListOf<CellConflict>()

        val ancestorLayersById = ancestor.layers.associateBy { it.id }
        val theirsLayersById = theirs.layers.associateBy { it.id }
        val mineLayersById = mine.layers.associateBy { it.id }

        val unionKeys = theirCellChanges.keys + mineCellChanges.keys
        for (key in unionKeys) {
            val theirChange = theirCellChanges[key]
            val mineChange = mineCellChanges[key]
            when {
                theirChange != null && mineChange == null -> autoFromTheirs += theirChange
                theirChange == null && mineChange != null -> autoFromMine += mineChange
                theirChange != null && mineChange != null -> {
                    val theirTarget = targetCellOf(theirChange)
                    val mineTarget = targetCellOf(mineChange)
                    if (theirTarget == mineTarget) {
                        // Both sides produced the same target cell; auto-resolved.
                        // Surface as autoFromTheirs since either side is fine —
                        // the resolved cell would be identical from autoFromMine.
                        autoFromTheirs += theirChange
                    } else {
                        val ancestorCell =
                            ancestorLayersById[key.layerId]
                                ?.cells
                                ?.firstOrNull { it.x == key.x && it.y == key.y }
                        conflicts +=
                            CellConflict(
                                layerId = key.layerId,
                                x = key.x,
                                y = key.y,
                                ancestor = ancestorCell,
                                theirs = theirTarget,
                                mine = mineTarget,
                            )
                    }
                }
            }
        }

        // Layer-level partitioning. Same shape as the cell-level one but keyed
        // on layerId. PropertyChanged on both sides where the after-states
        // disagree is a layer conflict; either-only is auto-applied; same
        // after-state on both sides is auto-resolved.
        val theirLayerChanges = theirDiff.layerChanges.associateBy { it.layerId }
        val mineLayerChanges = mineDiff.layerChanges.associateBy { it.layerId }

        val autoLayerFromTheirs = mutableListOf<LayerChange>()
        val autoLayerFromMine = mutableListOf<LayerChange>()
        val layerConflicts = mutableListOf<LayerConflict>()

        val unionLayerIds = theirLayerChanges.keys + mineLayerChanges.keys
        for (layerId in unionLayerIds) {
            val theirChange = theirLayerChanges[layerId]
            val mineChange = mineLayerChanges[layerId]
            when {
                theirChange != null && mineChange == null -> autoLayerFromTheirs += theirChange
                theirChange == null && mineChange != null -> autoLayerFromMine += mineChange
                theirChange != null && mineChange != null -> {
                    val theirAfter = afterLayerOf(theirChange)
                    val mineAfter = afterLayerOf(mineChange)
                    // Both sides agree on the after-state (e.g. both renamed
                    // the same layer to the same string, or both deleted it):
                    // auto-resolved.
                    if (sameAfterState(theirAfter, mineAfter)) {
                        autoLayerFromTheirs += theirChange
                    } else {
                        layerConflicts +=
                            LayerConflict(
                                layerId = layerId,
                                ancestor = ancestorLayersById[layerId],
                                theirs = theirsLayersById[layerId],
                                mine = mineLayersById[layerId],
                            )
                    }
                }
            }
        }

        return ConflictReport(
            autoFromTheirs = autoFromTheirs,
            autoFromMine = autoFromMine,
            conflicts = conflicts,
            autoLayerFromTheirs = autoLayerFromTheirs,
            autoLayerFromMine = autoLayerFromMine,
            layerConflicts = layerConflicts,
        )
    }

    private fun targetCellOf(change: CellChange): ChartCell? =
        when (change) {
            is CellChange.Added -> change.cell
            is CellChange.Removed -> null
            is CellChange.Modified -> change.after
        }

    private fun afterLayerOf(change: LayerChange): ChartLayer? =
        when (change) {
            is LayerChange.Added -> change.layer
            is LayerChange.Removed -> null
            is LayerChange.PropertyChanged -> change.after
        }

    /**
     * Two layer after-states are "the same" for conflict purposes when both
     * agree on name / visible / locked. Cell content is excluded — cells
     * partition independently into [conflicts] / [autoFromTheirs] /
     * [autoFromMine].
     */
    private fun sameAfterState(
        a: ChartLayer?,
        b: ChartLayer?,
    ): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.id == b.id && a.name == b.name && a.visible == b.visible && a.locked == b.locked
    }
}

/**
 * Composite key for a per-cell change. Layer id + (x, y) anchor.
 */
data class CellCoordinate(
    val layerId: String,
    val x: Int,
    val y: Int,
)

/**
 * Result of a 3-way diff (ADR-014 §4).
 *
 * - Auto-resolvable cell changes are split across [autoFromTheirs] and
 *   [autoFromMine] so the merger can apply them without prompting the user.
 * - [conflicts] is the only set that requires user resolution.
 * - Layer-level edits split the same way; [layerConflicts] surfaces conflicts
 *   the resolver UI must surface (e.g. both sides renamed the same layer
 *   differently).
 */
data class ConflictReport(
    val autoFromTheirs: List<CellChange>,
    val autoFromMine: List<CellChange>,
    val conflicts: List<CellConflict>,
    val autoLayerFromTheirs: List<LayerChange>,
    val autoLayerFromMine: List<LayerChange>,
    val layerConflicts: List<LayerConflict>,
) {
    /** True when no user interaction is required — every change auto-resolved. */
    val isClean: Boolean
        get() = conflicts.isEmpty() && layerConflicts.isEmpty()
}

/**
 * One conflicted cell. [ancestor] is the pre-fork value at this position
 * (null if the cell did not exist in ancestor); [theirs] is the source-side
 * after-value (null if removed on source); [mine] is the target-side
 * after-value (null if removed on target). Resolver picks one of these
 * three values per cell.
 *
 * [id] is a deterministic stable composite of `(layerId, x, y)` so SwiftUI
 * `ForEach` can use it without a custom Hashable conformance on the Kotlin
 * data class — the existing Phase 38.3 PR comment / PR list ForEach calls
 * use `id: \.id` and adding the same shape here keeps the iOS rendering
 * pattern consistent.
 */
data class CellConflict(
    val layerId: String,
    val x: Int,
    val y: Int,
    val ancestor: ChartCell?,
    val theirs: ChartCell?,
    val mine: ChartCell?,
) {
    val id: String get() = "$layerId#$x,$y"
}

/**
 * One conflicted layer. Surfaces as a divergent layer property edit (rename,
 * visibility flip, lock flip) where the source and target both modified the
 * same layer differently. Resolver picks one of [theirs] / [mine] / [ancestor]
 * for the layer's after-state. [id] aliases [layerId] for SwiftUI ForEach
 * consistency with [CellConflict.id].
 */
data class LayerConflict(
    val layerId: String,
    val ancestor: ChartLayer?,
    val theirs: ChartLayer?,
    val mine: ChartLayer?,
) {
    val id: String get() = layerId
}
