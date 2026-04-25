package io.github.b150005.knitnote.domain.chart

import io.github.b150005.knitnote.domain.model.CellChange
import io.github.b150005.knitnote.domain.model.ChartDiff
import io.github.b150005.knitnote.domain.model.LayerChange
import io.github.b150005.knitnote.domain.model.StructuredChart

/**
 * Pure cell-level + layer-property diff per ADR-013 §5.
 *
 * Algorithm complexity is O(N + M) where N, M are total cell counts in base
 * and target. For a 100×100 chart with 10K cells each, runs well under a
 * millisecond — no memoization required.
 *
 * Polar charts diff identically: `cell.x = stitch`, `cell.y = ring` is the
 * canonical address regardless of [io.github.b150005.knitnote.domain.model.CoordinateSystem],
 * so the (x, y) keying applies directly.
 *
 * Parametric symbols diff via [io.github.b150005.knitnote.domain.model.ChartCell] data
 * class equality — `symbolParameters` participate in `==` so a parameter edit
 * surfaces as [CellChange.Modified].
 *
 * Initial commit: pass `base = null` to diff against an empty chart. Every
 * target layer surfaces as [LayerChange.Added] and `cellChanges` is empty
 * (the algorithm intersects layer ids; an empty base intersects with nothing).
 */
object ChartDiffAlgorithm {
    fun diff(
        base: StructuredChart?,
        target: StructuredChart,
    ): ChartDiff {
        val baseLayers = base?.layers.orEmpty()
        val baseLayersById = baseLayers.associateBy { it.id }
        val targetLayersById = target.layers.associateBy { it.id }

        val layerChanges = mutableListOf<LayerChange>()
        val unionLayerIds = baseLayersById.keys + targetLayersById.keys
        for (layerId in unionLayerIds) {
            val b = baseLayersById[layerId]
            val t = targetLayersById[layerId]
            when {
                b == null && t != null -> layerChanges += LayerChange.Added(t)
                t == null && b != null -> layerChanges += LayerChange.Removed(b)
                b != null && t != null && (b.name != t.name || b.visible != t.visible || b.locked != t.locked) ->
                    layerChanges += LayerChange.PropertyChanged(layerId, b, t)
            }
        }

        val cellChanges = mutableListOf<CellChange>()
        val sharedLayerIds = baseLayersById.keys.intersect(targetLayersById.keys)
        for (layerId in sharedLayerIds) {
            val baseCells =
                baseLayersById.getValue(layerId).cells.associateBy { it.x to it.y }
            val targetCells =
                targetLayersById.getValue(layerId).cells.associateBy { it.x to it.y }
            val union = baseCells.keys + targetCells.keys
            for (xy in union) {
                val bc = baseCells[xy]
                val tc = targetCells[xy]
                when {
                    bc == null && tc != null -> cellChanges += CellChange.Added(layerId, tc)
                    tc == null && bc != null -> cellChanges += CellChange.Removed(layerId, bc)
                    bc != null && tc != null && bc != tc ->
                        cellChanges += CellChange.Modified(layerId, bc, tc)
                }
            }
        }

        return ChartDiff(
            base = base,
            target = target,
            cellChanges = cellChanges,
            layerChanges = layerChanges,
        )
    }
}
