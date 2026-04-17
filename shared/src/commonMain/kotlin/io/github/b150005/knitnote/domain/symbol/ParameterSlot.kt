package io.github.b150005.knitnote.domain.symbol

/**
 * Position within a symbol's unit square where a caller-supplied value is rendered.
 * Used by parametric symbols such as "cast-on n stitches" where the numeric `n`
 * is drawn on top of the base glyph.
 *
 * Coordinates are in unit-square space (0..1 in both axes, y-down for consistency
 * with SVG path coordinates). See docs/en/chart-coordinates.md for axis conventions.
 */
data class ParameterSlot(
    /** Stable key written into [io.github.b150005.knitnote.domain.model.ChartCell.symbolParameters]. */
    val key: String,
    /** Unit-square x of the slot anchor (0..1). */
    val x: Double,
    /** Unit-square y of the slot anchor (0..1). */
    val y: Double,
    /** Optional placeholder rendered when a cell omits the value. */
    val defaultValue: String? = null,
    /** Human-readable label for editors. */
    val jaLabel: String,
    /** Human-readable label for editors. */
    val enLabel: String,
)
