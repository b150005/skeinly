package io.github.b150005.skeinly.domain.chart

import kotlin.math.max
import kotlin.math.min

/**
 * WCAG 2.5.8 Target Size (Minimum) helper for the rect chart editor.
 *
 * The editor fits the whole grid into the canvas, so on large grids the
 * per-cell touch target shrinks below the accessibility floor. This is the
 * single shared source of truth both the Compose (Android) and SwiftUI
 * (iOS) editors call to derive the initial zoom scale that keeps every
 * cell at or above the minimum target, without re-deriving the fit math
 * per platform.
 *
 * Polar editing keeps fitting (no zoom) — it is deferred to Phase 35.2+
 * per the chart-editor spec; this helper is rect-only by use, not by
 * signature.
 */
object WcagTargetSize {
    /**
     * WCAG 2.5.8 Target Size (Minimum), Level AA: interactive targets are
     * at least 24×24 CSS pixels. dp (Android) and pt (iOS) are the
     * platform analogues of the CSS pixel for this success criterion.
     */
    const val MIN_TARGET_DP: Double = 24.0

    /**
     * The minimum zoom scale at which every rect-grid cell renders at or
     * above [minTargetPx] on screen, satisfying WCAG 2.5.8.
     *
     * Returns `1.0` (no zoom) when the fitted cell already meets the
     * minimum, so small / default grids are unaffected and only large
     * grids auto-zoom on first layout. The result is never below `1.0`
     * and never above [maxScale] (a [maxScale] below `1.0` is treated as
     * `1.0`). Degenerate or non-finite inputs collapse to `1.0` — fail
     * safe: never force a transform on bad data.
     *
     * The base cell uses the same `min(canvasW / cols, canvasH / rows)`
     * fit math as the per-platform layout, floored at 1px to match what
     * the renderers actually draw.
     *
     * @param gridWidth number of columns (cells along x)
     * @param gridHeight number of rows (cells along y)
     * @param canvasWidthPx canvas width in device pixels
     * @param canvasHeightPx canvas height in device pixels
     * @param minTargetPx the WCAG floor expressed in the same device
     *   pixels as the canvas (e.g. `24.dp.toPx()` on Compose, `24.0` pt
     *   on iOS where pt ≈ device-independent px)
     * @param maxScale the editor's pinch-zoom ceiling
     */
    fun minimumScale(
        gridWidth: Int,
        gridHeight: Int,
        canvasWidthPx: Double,
        canvasHeightPx: Double,
        minTargetPx: Double,
        maxScale: Double,
    ): Double {
        if (gridWidth <= 0 || gridHeight <= 0) return 1.0
        if (!canvasWidthPx.isFinite() || !canvasHeightPx.isFinite()) return 1.0
        if (!minTargetPx.isFinite() || !maxScale.isFinite()) return 1.0
        if (canvasWidthPx <= 0.0 || canvasHeightPx <= 0.0) return 1.0
        if (minTargetPx <= 0.0) return 1.0

        val cap = max(1.0, maxScale)
        val rawCell = min(canvasWidthPx / gridWidth, canvasHeightPx / gridHeight)
        // Match the renderers, which clamp the drawn cell to a 1px floor.
        val baseCell = max(1.0, rawCell)
        if (baseCell >= minTargetPx) return 1.0

        val needed = minTargetPx / baseCell
        return needed.coerceIn(1.0, cap)
    }
}
