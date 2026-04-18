package io.github.b150005.knitnote.domain.symbol.catalog

import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import io.github.b150005.knitnote.domain.symbol.SymbolDefinition

/**
 * Craft Yarn Council (CYC) symbol overlays under the `std.cyc.*` namespace.
 *
 * These are glyphs that CYC publishes but that are either absent from JIS L 0201
 * or shaped differently. Per ADR-008's symbol-sources policy, `std.cyc.*` sits
 * alongside `jis.*` rather than editing `jis.knit.*` entries.
 *
 * Phase 30.1-fix introduces the first entry: `std.cyc.kfb` (knit front and back).
 * JIS L 0201-1995 does not standardise a `kfb` glyph, so the previous
 * `jis.knit.kfb` entry was a mis-classification. The JA term `ねじり増し目` resolves
 * to `jis.knit.twist-r` via its `aliases` list.
 */
internal object CycSymbols {
    private const val CYC_ID_PREFIX = "std.cyc"

    private fun id(suffix: String): String = "$CYC_ID_PREFIX.$suffix"

    private val kfb =
        SymbolDefinition(
            id = id("kfb"),
            category = SymbolCategory.KNIT,
            // CYC kfb glyph: two diagonal arms meeting at a central vertex (an
            // upward-opening V on screen, since our unit square is y-down), with a
            // short horizontal bar below — a knit with a purl on the same stitch,
            // producing one extra stitch.
            pathData = "M 0.2 0.1 L 0.5 0.55 M 0.5 0.55 L 0.8 0.1 M 0.3 0.7 L 0.7 0.7",
            jaLabel = "巻き増し目",
            enLabel = "Knit front and back (kfb)",
            jisReference = null,
            cycName = "kfb",
            jaDescription = "同じ目に表編み・裏編みを続けて1目増やす。CYC の kfb 記号。",
            enDescription = "Knit then purl into the same stitch to create an extra stitch.",
        )

    val all: List<SymbolDefinition> =
        listOf(
            kfb,
        )
}
