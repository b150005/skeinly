package io.github.b150005.knitnote.domain.symbol

/**
 * Immutable description of a single knitting symbol. Path geometry is stored as an
 * SVG path `d` attribute in unit-square space (viewBox `0 0 1 1`, y-down per SVG
 * conventions) so all symbols share a common coordinate frame and renderers can
 * trivially map to any cell rect.
 *
 * @see io.github.b150005.knitnote.domain.symbol.SymbolCatalog
 */
data class SymbolDefinition(
    /** Stable id matching [io.github.b150005.knitnote.domain.model.StructuredChart.isValidSymbolId]. */
    val id: String,
    val category: SymbolCategory,
    /** SVG path `d` attribute. Parsed by [SvgPathParser]. */
    val pathData: String,
    /** Japanese label — primary JIS terminology. */
    val jaLabel: String,
    /** English label — CYC or common international terminology where available. */
    val enLabel: String,
    /**
     * Visual width in stitch units. Most symbols are 1×1; multi-cell symbols such as
     * `k3tog-c` (3-into-1) occupy 3 stitch columns. Cells placing this symbol should
     * set [io.github.b150005.knitnote.domain.model.ChartCell.width] to match.
     */
    val widthUnits: Int = 1,
    val heightUnits: Int = 1,
    /** Authoritative reference, e.g. "JIS L 0201-1995 表1 No.3". */
    val jisReference: String? = null,
    /** Craft Yarn Council name, e.g. "knit" / "purl" for JA↔EN cross-referencing. */
    val cycName: String? = null,
    /** Parametric slots; empty for glyph-only symbols. */
    val parameterSlots: List<ParameterSlot> = emptyList(),
    /** Optional long-form description shown in Phase 31 dictionary UI. */
    val jaDescription: String? = null,
    val enDescription: String? = null,
    /**
     * Searchable alternate labels (JA or EN) that should resolve to this symbol.
     * Used by Phase 30.2+ dictionary search so that e.g. `ねじり増し目` finds
     * `jis.knit.twist-r`. Not shown as the primary label in the gallery.
     */
    val aliases: List<String> = emptyList(),
) {
    init {
        require(widthUnits > 0) { "widthUnits must be positive; got $widthUnits for $id" }
        require(heightUnits > 0) { "heightUnits must be positive; got $heightUnits for $id" }
    }
}
