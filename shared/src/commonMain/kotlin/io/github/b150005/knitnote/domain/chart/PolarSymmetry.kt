package io.github.b150005.knitnote.domain.chart

import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents

/**
 * Pure polar-space symmetry ops on `List<ChartCell>` — first slice of
 * ADR-011 §3 scoped down to geometric replication without the
 * `mirrorHorizontal` catalog lookup.
 *
 * Both ops return a UNION of the input with the generated copies, deduped by
 * `(stitch, ring)` where the authored (input) cell wins over any generated
 * conflict. Cells in rings where the divisor/axis does not apply are
 * preserved unchanged.
 *
 * The `rotation` field on [ChartCell] is:
 * - unchanged by [rotateCells] — every copy sits at a different stitch index
 *   on the same ring, so `cellRadialUpRotation` already reorients the glyph
 *   outward along its new radial. The author's discrete rotation (0/90/180/
 *   270) composes inside the rotated bounds at render time.
 * - negated (`(360 - rotation) mod 360`) by [reflectCells] — the local X
 *   axis flips under a radial mirror so an author-set 90° rotation becomes
 *   270°.
 *
 * Symbol identity is not mutated here: asymmetric glyphs (e.g. left-leaning
 * cables under horizontal mirror) keep their original `symbolId`. The full
 * `SymbolDefinition.mirrorHorizontal` lookup from ADR-011 §3 ships in a
 * follow-up slice.
 */
object PolarSymmetry {
    /**
     * Replicate [cells] around each ring with `fold`-way rotational symmetry.
     *
     * For a ring whose `stitchesPerRing` is an integer multiple of [fold],
     * every input cell on that ring produces `fold - 1` additional copies at
     * angular positions `(stitch + k · stitchesInRing/fold) mod stitchesInRing`
     * for `k ∈ [1..fold-1]`. Rings where `stitchesInRing % fold != 0` are
     * skipped — their cells pass through unchanged.
     *
     * [fold] `<= 1` is a no-op.
     */
    fun rotateCells(
        cells: List<ChartCell>,
        extents: ChartExtents.Polar,
        fold: Int,
    ): List<ChartCell> {
        if (fold <= 1) return cells
        if (cells.isEmpty()) return cells

        val generated = mutableListOf<ChartCell>()
        for (cell in cells) {
            val ring = cell.y
            if (ring !in extents.stitchesPerRing.indices) continue
            val stitchesInRing = extents.stitchesPerRing[ring]
            if (stitchesInRing <= 0) continue
            if (stitchesInRing % fold != 0) continue
            val stride = stitchesInRing / fold
            for (k in 1 until fold) {
                val newStitch = ((cell.x + k * stride) % stitchesInRing + stitchesInRing) % stitchesInRing
                generated.add(cell.copy(x = newStitch))
            }
        }
        return dedup(cells, generated)
    }

    /**
     * Reflect [cells] across the radial ray through stitch index [axisStitch].
     *
     * For each ring the mirrored stitch index is
     * `(2 · axisStitch - stitch) mod stitchesInRing[ring]`. The `rotation`
     * field is negated because a radial mirror flips the cell's local X axis.
     * All rings participate (no divisibility constraint — reflection is
     * well-defined for any ring with `stitchesInRing > 0`).
     */
    fun reflectCells(
        cells: List<ChartCell>,
        extents: ChartExtents.Polar,
        axisStitch: Int,
    ): List<ChartCell> {
        if (cells.isEmpty()) return cells

        val generated = mutableListOf<ChartCell>()
        for (cell in cells) {
            val ring = cell.y
            if (ring !in extents.stitchesPerRing.indices) continue
            val stitchesInRing = extents.stitchesPerRing[ring]
            if (stitchesInRing <= 0) continue
            val mirrored = ((2 * axisStitch - cell.x) % stitchesInRing + stitchesInRing) % stitchesInRing
            val negatedRotation = ((360 - cell.rotation) % 360 + 360) % 360
            generated.add(cell.copy(x = mirrored, rotation = negatedRotation))
        }
        return dedup(cells, generated)
    }

    // Union of authored + generated, deduped by (x, y). Authored wins.
    private fun dedup(
        authored: List<ChartCell>,
        generated: List<ChartCell>,
    ): List<ChartCell> {
        if (generated.isEmpty()) return authored
        val occupied = authored.mapTo(mutableSetOf()) { it.x to it.y }
        val merged = ArrayList<ChartCell>(authored.size + generated.size)
        merged.addAll(authored)
        for (cell in generated) {
            val key = cell.x to cell.y
            if (occupied.add(key)) {
                merged.add(cell)
            }
        }
        return merged
    }
}
