package io.github.b150005.skeinly.domain.chart

import io.github.b150005.skeinly.domain.model.CellChange
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartLayer
import io.github.b150005.skeinly.domain.model.LayerChange
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
     * R1b: one in-row adjustable cursor cell — what the Editor's TalkBack
     * swipe-up/down / VoiceOver-adjustable action announces (ADR-025 §c).
     * [chartX]/[chartY] are the clamped chart-space coordinates (already
     * offset by `minX`/`minY`); [colNumber]/[rowNumber] are the 1-based
     * display indices (left/bottom = 1, matching the row-overlay numbering
     * and knitting work order). [symbolIdAt] is the topmost-visible layer's
     * symbol at the cursor cell, or `null` for a blank cell — identical
     * "last wins" resolution to [SymbolRun] (i.e. `topmostLayerAt`).
     */
    data class CellAccessibilityDescriptor(
        val chartX: Int,
        val chartY: Int,
        val colNumber: Int,
        val colCount: Int,
        val rowNumber: Int,
        val rowCount: Int,
        val symbolIdAt: String?,
    )

    /**
     * R1b localized templates for the in-row cell cursor + place/erase
     * action. Placeholders follow the same `%1$d`/`%1$s` convention as
     * [A11yStrings] so the substitution is identical to the row-overlay
     * formatter — manual replace (`String.format` is JVM-only in common).
     *
     * [cellSymbolFormat] takes five placeholders in order: row number, row
     * total, col number, col total, symbol-or-blank. Blank cells substitute
     * [cellBlank] into the symbol slot so the format itself is a single
     * template per locale. [actionPlaceFormat] takes one placeholder (the
     * resolved palette symbol name); [actionErase] is the bare verb used
     * when no palette symbol is selected.
     */
    data class CellA11yStrings(
        val cellSymbolFormat: String,
        val cellBlank: String,
        val actionPlaceFormat: String,
        val actionErase: String,
    )

    /**
     * R1b: build the cursor cell descriptor at chart coordinates
     * ([cursorX], [cursorY]). The cursor is clamped to
     * `extents.minX..maxX × extents.minY..maxY` so the platform
     * adjustable-action state machine never escapes the grid — pure clamp,
     * no off-by-one at col 1 / col N. Returns `null` on degenerate extents
     * (no descriptor surface to expose).
     *
     * [hiddenLayerIds] + `layer.visible` follow the same predicate as
     * [rowDescriptors] so the cell cursor stays in lockstep with the row
     * overlay's run-length summary — never announce a symbol on a row
     * whose runs do not include it.
     */
    fun cellDescriptor(
        extents: ChartExtents.Rect,
        layers: List<ChartLayer>,
        hiddenLayerIds: Set<String> = emptySet(),
        cursorX: Int,
        cursorY: Int,
    ): CellAccessibilityDescriptor? {
        if (extents.maxX < extents.minX || extents.maxY < extents.minY) return null
        val gridWidth = extents.maxX - extents.minX + 1
        val gridHeight = extents.maxY - extents.minY + 1
        val clampedX = cursorX.coerceIn(extents.minX, extents.maxX)
        val clampedY = cursorY.coerceIn(extents.minY, extents.maxY)
        val visibleLayers = layers.filter { it.visible && it.id !in hiddenLayerIds }
        // Topmost wins: iterate first→last (paint order) and keep the last
        // match. Mirrors `rowRuns` (cols[col] = cell.symbolId in declared
        // order) and the viewer's `topmostLayerAt` so the cursor announcement
        // matches what the user visually sees on top.
        val symbolIdAt =
            visibleLayers
                .asSequence()
                .flatMap { it.cells.asSequence() }
                .filter { it.x == clampedX && it.y == clampedY }
                .lastOrNull()
                ?.symbolId
        return CellAccessibilityDescriptor(
            chartX = clampedX,
            chartY = clampedY,
            colNumber = clampedX - extents.minX + 1,
            colCount = gridWidth,
            rowNumber = clampedY - extents.minY + 1,
            rowCount = gridHeight,
            symbolIdAt = symbolIdAt,
        )
    }

    /**
     * Pure formatter for the cursor cell announcement — Compose and SwiftUI
     * produce byte-identical text by construction (ADR-025 §g). A blank
     * cursor cell substitutes [CellA11yStrings.cellBlank] into the symbol
     * slot; the [symbolName] resolver is consulted only when
     * [CellAccessibilityDescriptor.symbolIdAt] is non-null.
     */
    fun spokenCellLabel(
        descriptor: CellAccessibilityDescriptor,
        strings: CellA11yStrings,
        symbolName: (symbolId: String) -> String,
    ): String {
        val name = descriptor.symbolIdAt?.let(symbolName) ?: strings.cellBlank
        return strings.cellSymbolFormat
            .replace("%1\$d", descriptor.rowNumber.toString())
            .replace("%2\$d", descriptor.rowCount.toString())
            .replace("%3\$d", descriptor.colNumber.toString())
            .replace("%4\$d", descriptor.colCount.toString())
            .replace("%5\$s", name)
    }

    /**
     * Resolve the editor's place/erase custom-action label from the
     * currently-selected palette symbol. The action wires to the existing
     * `ChartEditorEvent.PlaceCell(cursorX, cursorY)` — the ViewModel already
     * routes `selectedSymbolId == null` to an immediate erase and any
     * non-null id to place/overwrite, so a single custom action with a
     * label that flips between "Place &lt;sym&gt;" and "Erase" faithfully
     * mirrors the touch affordance (ADR-025 §c).
     */
    fun placeOrEraseActionLabel(
        strings: CellA11yStrings,
        selectedSymbolId: String?,
        symbolName: (symbolId: String) -> String,
    ): String {
        if (selectedSymbolId == null) return strings.actionErase
        return strings.actionPlaceFormat.replace("%1\$s", symbolName(selectedSymbolId))
    }

    // ---------------------------------------------------------------------
    // R1c — Chart Comparison per-row change list (ADR-025 §c Comparison row).
    // Closes audit §3.1 B4: per-cell diff is 100% traffic-light fill with no
    // a11y element; the per-row overlay reads each changed row as a spoken
    // change list so VoiceOver/TalkBack + color-blind users can perceive the
    // diff. Read-only: no actions (the Comparison surface is itself read-
    // only); aggregate `DiffSummaryRow` is preserved as-is.
    // ---------------------------------------------------------------------

    /** Kind of per-cell diff change, mirroring [CellChange] / [LayerChange.Added] / [LayerChange.Removed]. */
    enum class DiffChangeKind { ADDED, REMOVED, MODIFIED }

    /**
     * One change on a row, used by the per-row spoken description. [colNumber]
     * is the 1-based display column (left = 1, matching the row-overlay
     * numbering). [symbolId] is the symbol the *current* state has at the
     * position — [DiffChangeKind.MODIFIED] uses the AFTER cell's symbol per
     * `ChartComparison` "knitters care about what's at this position now"
     * docs; [DiffChangeKind.ADDED] / [DiffChangeKind.REMOVED] use the added /
     * removed cell's own symbol. `null` denotes a blank cell which the formatter
     * substitutes via [DiffA11yStrings.blankCellsName].
     */
    data class RowDiffChange(
        val colNumber: Int,
        val kind: DiffChangeKind,
        val symbolId: String?,
    )

    /**
     * Per-row diff descriptor for an accessibility row element on the
     * Comparison surface. Only rows that have ≥1 change are emitted — SR
     * users do not swipe through unchanged rows. [rowNumber] is the 1-based
     * display number (1 = bottom, matching R1a/R1b's row numbering and the
     * knitting work order); [chartY] is the chart y-coordinate.
     */
    data class RowDiffDescriptor(
        val rowNumber: Int,
        val rowCount: Int,
        val chartY: Int,
        val changes: List<RowDiffChange>,
    )

    /**
     * Localized templates for the Comparison spoken text. Placeholders follow
     * the same `%1$d` / `%2$s` manual-replace convention as [A11yStrings] and
     * [CellA11yStrings] so substitution is identical across Compose + SwiftUI
     * by construction (ADR-025 §g) — `String.format` is JVM-only in common.
     *
     * [rowPositionFormat] / [sectionSeparator] / [blankCellsName] are reused
     * verbatim from [A11yStrings] (R1a `a11y_chart_row_position` /
     * `a11y_chart_section_separator` / `a11y_chart_blank_cells`); only the
     * change-format trio + change-separator are R1c-new keys.
     *
     * [changeAddedFormat] / [changeRemovedFormat] / [changeModifiedFormat]
     * take two placeholders each: column number then localized symbol name
     * (`%1$d` `%2$s`). The Modified template ends in "to" / "を…に変更" — the
     * AFTER symbol is the one substituted.
     */
    data class DiffA11yStrings(
        val rowPositionFormat: String,
        val changeSeparator: String,
        val changeAddedFormat: String,
        val changeRemovedFormat: String,
        val changeModifiedFormat: String,
        val sectionSeparator: String,
        val blankCellsName: String,
    )

    /**
     * Build per-row diff descriptors for a rect Comparison surface. The
     * [targetExtents] anchors row indexing (target is always non-null per
     * [io.github.b150005.skeinly.domain.model.ChartComparison]; base may be
     * `null` on initial commit). Changes whose `x` / `y` falls outside
     * [targetExtents] (the shrunken-target case where base had rows the new
     * target lost) are silently dropped — those cells are already highlighted
     * on the base pane visually; surfacing them in the unified spoken row
     * description would force a phantom out-of-bounds row number on the SR
     * user. (Tracked as a Follow-up if the audit revisits.) Rows with zero
     * changes are NOT emitted.
     *
     * Polar is intentionally NOT handled here: per ADR-025 §e the polar
     * a11y path is gated to Phase 35.2+ in lockstep with M5; the caller is
     * responsible for not invoking this on a polar chart (Compose/SwiftUI
     * already gate on `is Rect`).
     */
    fun rowDiffDescriptors(
        targetExtents: ChartExtents.Rect,
        cellChanges: List<CellChange>,
        layerChanges: List<LayerChange>,
    ): List<RowDiffDescriptor> {
        if (targetExtents.maxX < targetExtents.minX || targetExtents.maxY < targetExtents.minY) {
            return emptyList()
        }
        val gridHeight = targetExtents.maxY - targetExtents.minY + 1

        // Buckets keyed by chartY -> list of RowDiffChange. LinkedHashMap to
        // keep insertion order stable across cellChanges then layerChanges,
        // then sort each bucket by colNumber asc at the end.
        val buckets = linkedMapOf<Int, MutableList<RowDiffChange>>()

        fun emit(
            x: Int,
            y: Int,
            kind: DiffChangeKind,
            symbolId: String?,
        ) {
            if (x < targetExtents.minX || x > targetExtents.maxX) return
            if (y < targetExtents.minY || y > targetExtents.maxY) return
            val col = x - targetExtents.minX + 1
            buckets.getOrPut(y) { mutableListOf() }.add(RowDiffChange(col, kind, symbolId))
        }

        cellChanges.forEach { change ->
            when (change) {
                is CellChange.Added -> emit(change.cell.x, change.cell.y, DiffChangeKind.ADDED, change.cell.symbolId)
                is CellChange.Removed ->
                    emit(change.cell.x, change.cell.y, DiffChangeKind.REMOVED, change.cell.symbolId)
                is CellChange.Modified ->
                    // Position-keyed: before.x/y == after.x/y by construction.
                    // Announce the AFTER symbol — "what's at this position now"
                    // per ChartComparison docs.
                    emit(change.after.x, change.after.y, DiffChangeKind.MODIFIED, change.after.symbolId)
            }
        }
        layerChanges.forEach { change ->
            when (change) {
                is LayerChange.Added ->
                    change.layer.cells.forEach { cell ->
                        emit(cell.x, cell.y, DiffChangeKind.ADDED, cell.symbolId)
                    }
                is LayerChange.Removed ->
                    change.layer.cells.forEach { cell ->
                        emit(cell.x, cell.y, DiffChangeKind.REMOVED, cell.symbolId)
                    }
                is LayerChange.PropertyChanged -> {
                    // No per-cell enumeration — mirrors the existing renderer
                    // rule in ChartComparisonScreen.classifyCells. The banner
                    // already surfaces the layer-property change separately.
                }
            }
        }

        // Sort each row's changes by colNumber asc, then build descriptors in
        // chartY-ascending order (bottom=row 1 traversal — matches R1a/R1b).
        // `toSortedMap()` is JVM-only; in commonMain (Kotlin/Native + JVM) we
        // sort the keys explicitly. Explicit `bucket[chartY]` access instead of
        // entry destructuring — destructuring a `Map<Int, MutableList<…>>`
        // entry trips an ambiguous `component2()` error on Kotlin/Native.
        val sortedYs = buckets.keys.sorted()
        return sortedYs.map { chartY ->
            val raw = buckets.getValue(chartY)
            RowDiffDescriptor(
                rowNumber = chartY - targetExtents.minY + 1,
                rowCount = gridHeight,
                chartY = chartY,
                changes = raw.sortedBy { it.colNumber },
            )
        }
    }

    /**
     * Compose the spoken accessibility label for a Comparison row from its
     * descriptor + platform-supplied localized templates + a symbol-name
     * resolver. Pure and shared so Compose and SwiftUI produce byte-identical
     * text by construction (ADR-025 §g). `null` symbol substitutes
     * [DiffA11yStrings.blankCellsName].
     *
     * @param symbolName `symbolId → localized name` (platform resolves
     *   `catalog.jaLabel` / `enLabel` by locale; falls back to the id until
     *   R2 localizes the catalog — same fallback as [spokenLabel] / [spokenCellLabel]).
     */
    fun spokenDiffLabel(
        descriptor: RowDiffDescriptor,
        strings: DiffA11yStrings,
        symbolName: (symbolId: String) -> String,
    ): String {
        val position =
            strings.rowPositionFormat
                .replace("%1\$d", descriptor.rowNumber.toString())
                .replace("%2\$d", descriptor.rowCount.toString())

        val changesText =
            descriptor.changes.joinToString(strings.changeSeparator) { change ->
                val name = change.symbolId?.let(symbolName) ?: strings.blankCellsName
                val template =
                    when (change.kind) {
                        DiffChangeKind.ADDED -> strings.changeAddedFormat
                        DiffChangeKind.REMOVED -> strings.changeRemovedFormat
                        DiffChangeKind.MODIFIED -> strings.changeModifiedFormat
                    }
                template
                    .replace("%1\$d", change.colNumber.toString())
                    .replace("%2\$s", name)
            }
        return listOf(position, changesText).joinToString(strings.sectionSeparator)
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
