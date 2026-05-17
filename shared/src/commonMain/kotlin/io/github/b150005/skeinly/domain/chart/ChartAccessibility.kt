package io.github.b150005.skeinly.domain.chart

import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartLayer
import io.github.b150005.skeinly.domain.model.SegmentState

/**
 * Pure shared accessibility model for the chart grid (ADR-025 R1a).
 *
 * The chart `Canvas` is opaque to VoiceOver/TalkBack on both platforms. Per
 * ADR-025 the accessibility unit is the **grid row** (not per-cell — up to
 * 65 536 nodes is unusable — nor a whole-grid summary — perceivable but not
 * operable). This object turns `ChartExtents.Rect` + layers (+ optional
 * per-cell progress) into an ordered list of per-row descriptors that both
 * the Compose and SwiftUI overlays consume **identically**, and a pure
 * `spokenLabel` formatter so the final spoken text is identical by
 * construction (no per-platform string assembly to drift — ADR-025 §g).
 *
 * Mirrors the `GridHitTest` / `WcagTargetSize` single-source-of-truth
 * precedent: no Compose / SwiftUI / locale imports. Rect only; the polar
 * path is gated `extents is Polar` and deferred to Phase 35.2+ in lockstep
 * with the M5 polar-zoom deferral.
 */
object ChartAccessibility {
    /**
     * A maximal run of identical cells along a row, left→right in chart-x
     * order. [symbolId] is the topmost **visible** layer's symbol at each
     * column (mirrors the viewer's `topmostLayerAt`); `null` is a run of
     * blank cells.
     */
    data class SymbolRun(
        val symbolId: String?,
        val count: Int,
    )

    /**
     * Per-row progress, derived from the **same** cell set
     * `MarkRowSegmentsDoneUseCase` touches (every cell on the row across
     * `visible && !hidden` layers — locked layers included, exactly as the
     * use case does) so the spoken state never diverges from what the
     * mark-row-done action actually flips.
     */
    sealed interface RowProgress {
        /** No cells on the row, or every cell is implicit `todo` (no `WIP`, no `DONE`). */
        data object NotStarted : RowProgress

        /**
         * The row is started but not complete. [done] is the count of
         * `DONE` cells; an all-`WIP` row is `InProgress(done = 0, total)`
         * ("0 of M done") — never [NotStarted], so the spoken state matches
         * the distinct WIP visual (Differentiate-Without-Color parity).
         */
        data class InProgress(
            val done: Int,
            val total: Int,
        ) : RowProgress

        /** Every cell on the row is `DONE` (and the row has ≥1 cell). */
        data object Done : RowProgress
    }

    /**
     * One accessibility element's worth of a chart row. [rowNumber] is the
     * 1-based display number (1 = bottom row, matching the rendered row
     * labels and knitting work order); [chartY] is the chart y-coordinate
     * the `MarkRowDone(row)` event expects (already offset by `minY`).
     * [progress] is `null` when there is no project/progress context
     * (bare pattern inspection) — the spoken label then omits the state.
     */
    data class RowAccessibilityDescriptor(
        val rowNumber: Int,
        val rowCount: Int,
        val chartY: Int,
        val runs: List<SymbolRun>,
        val progress: RowProgress?,
    )

    /**
     * Localized templates supplied by the platform (Compose `stringResource`
     * / iOS `NSLocalizedString`, resolved raw — no args). The shared
     * [spokenLabel] does the substitution so the join order/separators are
     * identical on both platforms. Placeholders follow the codebase
     * `%1$s` / `%1$d` convention (manual replace — `String.format` is
     * JVM-only in common).
     */
    data class A11yStrings(
        val rowPositionFormat: String,
        val symbolRunFormat: String,
        val blankCellsName: String,
        val runSeparator: String,
        val sectionSeparator: String,
        val progressNotStarted: String,
        val progressDone: String,
        val progressInProgressFormat: String,
    )

    /**
     * Build per-row accessibility descriptors for a rect chart, ordered
     * row 1 (bottom, `chartY == minY`) → row N (top) so screen-reader
     * traversal matches the spoken row numbers and knitting work order.
     *
     * @param progressAt nullable per-cell progress lookup `(layerId, x, y)
     *   → SegmentState?`. `null` ⇒ no project context ⇒ every descriptor's
     *   [RowAccessibilityDescriptor.progress] is `null`. A function param
     *   (not a `Map<SegmentKey, …>`) deliberately mirrors the ViewModel's
     *   `segmentStateAt` bridge so iOS does not hit the Kotlin/Native
     *   `SegmentKey`-not-`Hashable` footgun.
     */
    fun rowDescriptors(
        extents: ChartExtents.Rect,
        layers: List<ChartLayer>,
        hiddenLayerIds: Set<String> = emptySet(),
        progressAt: ((layerId: String, x: Int, y: Int) -> SegmentState?)? = null,
    ): List<RowAccessibilityDescriptor> {
        if (extents.maxX < extents.minX || extents.maxY < extents.minY) return emptyList()

        val gridWidth = extents.maxX - extents.minX + 1
        val gridHeight = extents.maxY - extents.minY + 1
        // visible && not UI-hidden — identical predicate to the viewer draw
        // path and MarkRowSegmentsDoneUseCase (locked is NOT excluded).
        val visibleLayers = layers.filter { it.visible && it.id !in hiddenLayerIds }

        return (0 until gridHeight).map { gy ->
            val chartY = extents.minY + gy
            RowAccessibilityDescriptor(
                rowNumber = gy + 1,
                rowCount = gridHeight,
                chartY = chartY,
                runs = rowRuns(visibleLayers, chartY, extents.minX, gridWidth),
                progress = progressAt?.let { rowProgress(visibleLayers, chartY, it) },
            )
        }
    }

    /**
     * Topmost-visible symbol per column for [chartY], run-length encoded
     * left→right. Layers are applied first→last so a later (topmost) layer
     * overwrites an earlier one at the same column — same "last wins"
     * resolution as the viewer's `topmostLayerAt`.
     */
    private fun rowRuns(
        visibleLayers: List<ChartLayer>,
        chartY: Int,
        minX: Int,
        gridWidth: Int,
    ): List<SymbolRun> {
        // gridWidth >= 1 is guaranteed by the rowDescriptors() degenerate
        // guard (maxX < minX → emptyList), so cols[0] below is always safe.
        val cols = arrayOfNulls<String>(gridWidth)
        visibleLayers.forEach { layer ->
            layer.cells.forEach { cell ->
                if (cell.y != chartY) return@forEach
                val col = cell.x - minX
                if (col in 0 until gridWidth) cols[col] = cell.symbolId
            }
        }

        val runs = ArrayList<SymbolRun>()
        var current = cols[0]
        var count = 1
        for (i in 1 until gridWidth) {
            if (cols[i] == current) {
                count++
            } else {
                runs.add(SymbolRun(current, count))
                current = cols[i]
                count = 1
            }
        }
        runs.add(SymbolRun(current, count))
        return runs
    }

    /**
     * Done/WIP/total tally over every cell on [chartY] across
     * [visibleLayers] — the exact set `MarkRowSegmentsDoneUseCase` upserts
     * (no `cell.x` bound is applied here, deliberately: that use case marks
     * every `cell.y == row` cell regardless of x, so the spoken progress
     * stays in lockstep with what the mark-row-done action actually flips.
     * The run-length *summary* in [rowRuns] does apply an x-bound because it
     * describes only the drawable in-grid columns — the two functions
     * answer different questions and intentionally differ here).
     *
     * `SegmentState` has only `WIP` / `DONE`; absence ⇒ implicit `todo`
     * (ADR-010 §2). A row the user has *started* (≥1 `WIP`) but completed
     * none must NOT speak as "not started" — that would mis-parity the
     * visual (WIP renders a distinct stroke, not blank) and fail the
     * Differentiate-Without-Color intent. State mapping:
     * - `total == 0` (no cells) → [RowProgress.NotStarted]
     * - `done == total` (and `total > 0`) → [RowProgress.Done]
     * - `done == 0 && wip == 0` (all `todo`) → [RowProgress.NotStarted]
     * - otherwise (any `done` or any `wip`, not all done) →
     *   [RowProgress.InProgress] — an all-`WIP` row reads "0 of M done".
     */
    private fun rowProgress(
        visibleLayers: List<ChartLayer>,
        chartY: Int,
        progressAt: (layerId: String, x: Int, y: Int) -> SegmentState?,
    ): RowProgress {
        var total = 0
        var done = 0
        var wip = 0
        visibleLayers.forEach { layer ->
            layer.cells.forEach { cell ->
                if (cell.y != chartY) return@forEach
                total++
                when (progressAt(layer.id, cell.x, cell.y)) {
                    SegmentState.DONE -> done++
                    SegmentState.WIP -> wip++
                    null -> Unit // implicit todo
                }
            }
        }
        return when {
            total == 0 -> RowProgress.NotStarted
            done == total -> RowProgress.Done
            done == 0 && wip == 0 -> RowProgress.NotStarted
            else -> RowProgress.InProgress(done = done, total = total)
        }
    }

    /**
     * Compose the spoken accessibility label for a row from its descriptor +
     * platform-supplied localized templates + a symbol-name resolver. Pure
     * and shared so Compose and SwiftUI produce byte-identical text by
     * construction (ADR-025 §g). A run of `count == 1` drops the "×N"
     * suffix ("knit", not "knit ×1"); a `null` [RowAccessibilityDescriptor.progress]
     * omits the trailing state section.
     *
     * @param symbolName `symbolId → localized name` (platform resolves
     *   `catalog.jaLabel` / `enLabel` by locale; falls back to the id until
     *   R2 localizes the catalog — ADR-025 §f).
     */
    fun spokenLabel(
        descriptor: RowAccessibilityDescriptor,
        strings: A11yStrings,
        symbolName: (symbolId: String) -> String,
    ): String {
        val position =
            strings.rowPositionFormat
                .replace("%1\$d", descriptor.rowNumber.toString())
                .replace("%2\$d", descriptor.rowCount.toString())

        val runsText =
            descriptor.runs.joinToString(strings.runSeparator) { run ->
                val name = run.symbolId?.let(symbolName) ?: strings.blankCellsName
                if (run.count == 1) {
                    name
                } else {
                    strings.symbolRunFormat
                        .replace("%1\$s", name)
                        .replace("%2\$d", run.count.toString())
                }
            }

        val parts = mutableListOf(position, runsText)
        when (val p = descriptor.progress) {
            null -> Unit
            RowProgress.NotStarted -> parts.add(strings.progressNotStarted)
            RowProgress.Done -> parts.add(strings.progressDone)
            is RowProgress.InProgress ->
                parts.add(
                    strings.progressInProgressFormat
                        .replace("%1\$d", p.done.toString())
                        .replace("%2\$d", p.total.toString()),
                )
        }
        return parts.joinToString(strings.sectionSeparator)
    }
}
