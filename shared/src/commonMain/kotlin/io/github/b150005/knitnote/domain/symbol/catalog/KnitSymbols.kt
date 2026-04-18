package io.github.b150005.knitnote.domain.symbol.catalog

import io.github.b150005.knitnote.domain.symbol.ParameterSlot
import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import io.github.b150005.knitnote.domain.symbol.SymbolDefinition

/**
 * JIS L 0201-1995 knitting-needle (棒針編目) symbol set.
 *
 * Each glyph is a functional reproduction of the standard shape drawn into a unit
 * square (`viewBox 0 0 1 1`, y-down). Paths are original renderings guided by the
 * JIS standard descriptions — the shapes themselves are not copyrightable, and no
 * JIS bitmap or vector artwork is reproduced.
 *
 * See `docs/en/chart-coordinates.md` for cell-to-unit-square mapping.
 */
internal object KnitSymbols {
    private const val CATEGORY_ID_PREFIX = "jis.knit"

    /** Id helper — enforces the namespace convention at declaration time. */
    private fun id(suffix: String): String = "$CATEGORY_ID_PREFIX.$suffix"

    // region — basic stitches ------------------------------------------------

    private val k =
        SymbolDefinition(
            id = id("k"),
            category = SymbolCategory.KNIT,
            // Vertical bar.
            pathData = "M 0.5 0.1 L 0.5 0.9",
            jaLabel = "表目",
            enLabel = "Knit",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "knit",
            jaDescription = "表側から見て表編み。記号は縦線。",
            enDescription = "Knit stitch as seen from the right side.",
        )

    private val p =
        SymbolDefinition(
            id = id("p"),
            category = SymbolCategory.KNIT,
            // Short horizontal bar anchored near the base of the cell — the user-
            // reviewed convention in Phase 30.1-fix. Narrower than the cell so that
            // multiple purl-derived glyphs (twisted purl, purl below, etc.) share a
            // consistent visual signature without reaching the edges.
            pathData = "M 0.35 0.75 L 0.65 0.75",
            jaLabel = "裏目",
            enLabel = "Purl",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "purl",
            jaDescription = "表側から見て裏編み。記号は下部の短い横線。",
            enDescription = "Purl stitch; drawn as a short bar near the cell base.",
        )

    private val yo =
        SymbolDefinition(
            id = id("yo"),
            category = SymbolCategory.KNIT,
            // Circle approximated with four cubic Béziers (k ≈ 0.5523).
            pathData =
                "M 0.5 0.15 " +
                    "C 0.6934 0.15 0.85 0.3066 0.85 0.5 " +
                    "C 0.85 0.6934 0.6934 0.85 0.5 0.85 " +
                    "C 0.3066 0.85 0.15 0.6934 0.15 0.5 " +
                    "C 0.15 0.3066 0.3066 0.15 0.5 0.15 Z",
            jaLabel = "掛け目",
            enLabel = "Yarn over",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "yo",
            jaDescription = "糸を手前に掛けて新しい目を作る。記号は丸。",
            enDescription = "Yarn over — creates a new stitch with a hole.",
        )

    private val slKnitwise =
        SymbolDefinition(
            id = id("sl-k"),
            category = SymbolCategory.KNIT,
            // Vertical bar with small horizontal tick at centre marking a slip.
            pathData = "M 0.5 0.1 L 0.5 0.9 M 0.35 0.5 L 0.65 0.5",
            jaLabel = "すべり目（表）",
            enLabel = "Slip stitch knitwise",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "sl kwise",
        )

    private val slPurlwise =
        SymbolDefinition(
            id = id("sl-p"),
            category = SymbolCategory.KNIT,
            // Horizontal bar with small vertical tick at centre.
            pathData = "M 0.1 0.5 L 0.9 0.5 M 0.5 0.35 L 0.5 0.65",
            jaLabel = "すべり目（裏）",
            enLabel = "Slip stitch purlwise",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "sl pwise",
        )

    private val floatFront =
        SymbolDefinition(
            id = id("float-front"),
            category = SymbolCategory.KNIT,
            // Vertical bar with short horizontal on right — float in front.
            pathData = "M 0.5 0.1 L 0.5 0.9 M 0.5 0.5 L 0.75 0.5",
            jaLabel = "浮き目（糸を手前）",
            enLabel = "Slip with yarn in front",
            jisReference = "JIS L 0201-1995 棒針編目",
        )

    // endregion

    // region — twisted stitches ---------------------------------------------

    private val ktblRight =
        SymbolDefinition(
            id = id("twist-r"),
            category = SymbolCategory.KNIT,
            // Vertical bar with a right-leaning crossbar at top showing the twist.
            pathData = "M 0.5 0.1 L 0.5 0.9 M 0.3 0.25 L 0.7 0.1",
            jaLabel = "ねじり目（右）",
            enLabel = "Right twisted knit (k-tbl)",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "k1-tbl",
            // `ねじり増し目` is a common JA term for a right-twisted knit used as
            // an increase; the old `jis.knit.kfb` entry mislabelled that concept,
            // so the alias makes it searchable from the correct glyph.
            aliases = listOf("ねじり増し目"),
        )

    private val ktblLeft =
        SymbolDefinition(
            id = id("twist-l"),
            category = SymbolCategory.KNIT,
            pathData = "M 0.5 0.1 L 0.5 0.9 M 0.3 0.1 L 0.7 0.25",
            jaLabel = "ねじり目（左）",
            enLabel = "Left twisted knit",
            jisReference = "JIS L 0201-1995 棒針編目",
        )

    private val ptblRight =
        SymbolDefinition(
            id = id("twist-p-r"),
            category = SymbolCategory.KNIT,
            // Purl short bar at cell base plus right-leaning twist diagonal above.
            pathData = "M 0.35 0.75 L 0.65 0.75 M 0.3 0.35 L 0.7 0.2",
            jaLabel = "裏ねじり目（右）",
            enLabel = "Right twisted purl (p-tbl)",
            jisReference = "JIS L 0201-1995 棒針編目",
        )

    // endregion

    // region — decreases -----------------------------------------------------

    private val k2togRight =
        SymbolDefinition(
            id = id("k2tog-r"),
            category = SymbolCategory.KNIT,
            // Full-height stem plus one right-leaning slash; the stem tail remains
            // visible below the crossing so the glyph is visually distinct from the
            // M1 increase family (which uses a stem + foot).
            pathData = "M 0.5 0.05 L 0.5 0.95 M 0.85 0.25 L 0.15 0.65",
            jaLabel = "右上2目一度",
            enLabel = "Right-leaning k2tog (SSK)",
            widthUnits = 1,
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "ssk",
            jaDescription = "左針の目を右針へ移してから表編みで一緒に編む。記号は右上がりの射線。",
            enDescription = "Slip-slip-knit decrease; resulting stitch leans to the right.",
        )

    private val k2togLeft =
        SymbolDefinition(
            id = id("k2tog-l"),
            category = SymbolCategory.KNIT,
            // Mirror of k2tog-r — left-leaning slash crossing the stem.
            pathData = "M 0.5 0.05 L 0.5 0.95 M 0.15 0.25 L 0.85 0.65",
            jaLabel = "左上2目一度",
            enLabel = "Left-leaning k2tog",
            widthUnits = 1,
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "k2tog",
        )

    private val p2togRight =
        SymbolDefinition(
            id = id("p2tog-r"),
            category = SymbolCategory.KNIT,
            // k2tog glyph with a short purl bar added at the top indicating the
            // purl character of the decrease.
            pathData =
                "M 0.35 0.1 L 0.65 0.1 " +
                    "M 0.5 0.15 L 0.5 0.95 " +
                    "M 0.85 0.3 L 0.15 0.65",
            jaLabel = "右上2目一度（裏）",
            enLabel = "Right-leaning p2tog",
            jisReference = "JIS L 0201-1995 棒針編目",
        )

    private val p2togLeft =
        SymbolDefinition(
            id = id("p2tog-l"),
            category = SymbolCategory.KNIT,
            pathData =
                "M 0.35 0.1 L 0.65 0.1 " +
                    "M 0.5 0.15 L 0.5 0.95 " +
                    "M 0.15 0.3 L 0.85 0.65",
            jaLabel = "左上2目一度（裏）",
            enLabel = "Left-leaning p2tog",
            jisReference = "JIS L 0201-1995 棒針編目",
        )

    private val k3togCentre =
        SymbolDefinition(
            id = id("k3tog-c"),
            category = SymbolCategory.KNIT,
            // Central decrease — three lines converge at top.
            pathData =
                "M 0.5 0.55 L 0.5 0.9 " +
                    "M 0.15 0.55 L 0.5 0.2 " +
                    "M 0.85 0.55 L 0.5 0.2 " +
                    "M 0.5 0.2 L 0.5 0.55",
            jaLabel = "中上3目一度",
            enLabel = "Centred double decrease (CDD)",
            widthUnits = 1,
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "cdd",
        )

    private val k3togRight =
        SymbolDefinition(
            id = id("k3tog-r"),
            category = SymbolCategory.KNIT,
            // Full-height stem with two parallel right-leaning slashes; extends the
            // k2tog-r convention (one slash per decreased stitch above 1).
            pathData =
                "M 0.5 0.05 L 0.5 0.95 " +
                    "M 0.85 0.2 L 0.15 0.55 " +
                    "M 0.85 0.4 L 0.15 0.75",
            jaLabel = "右上3目一度",
            enLabel = "Right-leaning k3tog (sssk)",
            widthUnits = 1,
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "sssk",
        )

    private val k3togLeft =
        SymbolDefinition(
            id = id("k3tog-l"),
            category = SymbolCategory.KNIT,
            pathData =
                "M 0.5 0.05 L 0.5 0.95 " +
                    "M 0.15 0.2 L 0.85 0.55 " +
                    "M 0.15 0.4 L 0.85 0.75",
            jaLabel = "左上3目一度",
            enLabel = "Left-leaning k3tog",
            widthUnits = 1,
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "k3tog",
        )

    // endregion

    // region — increases -----------------------------------------------------

    private val m1Right =
        SymbolDefinition(
            id = id("m1-r"),
            category = SymbolCategory.KNIT,
            // Vertical bar with a small right-leaning foot at the base.
            pathData = "M 0.5 0.1 L 0.5 0.9 M 0.5 0.9 L 0.75 0.75",
            jaLabel = "右増目",
            enLabel = "Make one right (M1R)",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "m1r",
        )

    private val m1Left =
        SymbolDefinition(
            id = id("m1-l"),
            category = SymbolCategory.KNIT,
            pathData = "M 0.5 0.1 L 0.5 0.9 M 0.5 0.9 L 0.25 0.75",
            jaLabel = "左増目",
            enLabel = "Make one left (M1L)",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "m1l",
        )

    // kfb moved to the `std.cyc.*` namespace — see [CycSymbols.kfb]. JIS L 0201
    // does not standardise a kfb glyph, and the previous JA label (`ねじり増し目`)
    // was a mis-label of twisted-knit (`jis.knit.twist-r`), which now carries
    // that term as an alias.

    private val castOn =
        SymbolDefinition(
            id = id("cast-on"),
            category = SymbolCategory.KNIT,
            // Stem with a horizontal base and a numeric slot above.
            pathData = "M 0.5 0.45 L 0.5 0.9 M 0.2 0.9 L 0.8 0.9",
            jaLabel = "編出し増目",
            enLabel = "Cast-on increase",
            jisReference = "JIS L 0201-1995 棒針編目",
            parameterSlots =
                listOf(
                    ParameterSlot(
                        key = "count",
                        x = 0.5,
                        y = 0.25,
                        defaultValue = "n",
                        jaLabel = "目数",
                        enLabel = "Stitch count",
                    ),
                ),
            jaDescription = "記号内の数字が作り目の目数。",
            enDescription = "Number inside the glyph is the number of stitches to cast on.",
        )

    // endregion

    // region — bind-off ------------------------------------------------------

    private val bindOff =
        SymbolDefinition(
            id = id("bind-off"),
            category = SymbolCategory.KNIT,
            // Thick horizontal stroke (top bar) plus a numeric slot below.
            pathData = "M 0.1 0.15 L 0.9 0.15 M 0.1 0.25 L 0.9 0.25",
            jaLabel = "伏せ目",
            enLabel = "Bind-off",
            jisReference = "JIS L 0201-1995 棒針編目",
            parameterSlots =
                listOf(
                    ParameterSlot(
                        key = "count",
                        x = 0.5,
                        y = 0.65,
                        defaultValue = "n",
                        jaLabel = "目数",
                        enLabel = "Stitch count",
                    ),
                ),
        )

    // endregion

    // region — cables --------------------------------------------------------

    // Cable convention (Phase 30.1-fix): the over-stroke is drawn unbroken; the
    // under-stroke is broken with a ~20% gap centred on the intersection so that
    // right-over and left-over glyphs are visually distinguishable (JIS house
    // style, also used by most JP publishers).

    private val cable1Over1Right =
        SymbolDefinition(
            id = id("cable-1x1-r"),
            category = SymbolCategory.KNIT,
            // Over: BL→TR unbroken. Under: TL→BR, broken at cell centre.
            pathData =
                "M 0.1 0.9 L 0.9 0.1 " +
                    "M 0.1 0.1 L 0.4 0.4 " +
                    "M 0.6 0.6 L 0.9 0.9",
            widthUnits = 2,
            jaLabel = "1目交差 右上",
            enLabel = "1/1 right cross",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "1/1 RC",
        )

    private val cable1Over1Left =
        SymbolDefinition(
            id = id("cable-1x1-l"),
            category = SymbolCategory.KNIT,
            // Over: TL→BR unbroken. Under: BL→TR, broken at cell centre.
            pathData =
                "M 0.1 0.1 L 0.9 0.9 " +
                    "M 0.1 0.9 L 0.4 0.6 " +
                    "M 0.6 0.4 L 0.9 0.1",
            widthUnits = 2,
            jaLabel = "1目交差 左上",
            enLabel = "1/1 left cross",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "1/1 LC",
        )

    private val cable2Over2Right =
        SymbolDefinition(
            id = id("cable-2x2-r"),
            category = SymbolCategory.KNIT,
            pathData =
                "M 0.05 0.9 L 0.95 0.1 " +
                    "M 0.05 0.1 L 0.4 0.4 " +
                    "M 0.6 0.6 L 0.95 0.9",
            widthUnits = 2,
            jaLabel = "2目交差 右上",
            enLabel = "2/2 right cross",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "2/2 RC",
        )

    private val cable2Over2Left =
        SymbolDefinition(
            id = id("cable-2x2-l"),
            category = SymbolCategory.KNIT,
            pathData =
                "M 0.05 0.1 L 0.95 0.9 " +
                    "M 0.05 0.9 L 0.4 0.6 " +
                    "M 0.6 0.4 L 0.95 0.1",
            widthUnits = 2,
            jaLabel = "2目交差 左上",
            enLabel = "2/2 left cross",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "2/2 LC",
        )

    private val cable3Over3Right =
        SymbolDefinition(
            id = id("cable-3x3-r"),
            category = SymbolCategory.KNIT,
            pathData =
                "M 0.02 0.9 L 0.98 0.1 " +
                    "M 0.02 0.1 L 0.4 0.4 " +
                    "M 0.6 0.6 L 0.98 0.9",
            widthUnits = 3,
            jaLabel = "3目交差 右上",
            enLabel = "3/3 right cross",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "3/3 RC",
        )

    private val cable3Over3Left =
        SymbolDefinition(
            id = id("cable-3x3-l"),
            category = SymbolCategory.KNIT,
            pathData =
                "M 0.02 0.1 L 0.98 0.9 " +
                    "M 0.02 0.9 L 0.4 0.6 " +
                    "M 0.6 0.4 L 0.98 0.1",
            widthUnits = 3,
            jaLabel = "3目交差 左上",
            enLabel = "3/3 left cross",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "3/3 LC",
        )

    private val cable1Over1RightPurl =
        SymbolDefinition(
            id = id("cable-1x1-r-p"),
            category = SymbolCategory.KNIT,
            // Same broken-under convention as cable-1x1-r plus a short purl tick
            // on the lower-right cell indicating the back stitch is purled.
            pathData =
                "M 0.1 0.9 L 0.9 0.1 " +
                    "M 0.1 0.1 L 0.4 0.4 " +
                    "M 0.6 0.6 L 0.9 0.9 " +
                    "M 0.7 0.5 L 0.9 0.5",
            widthUnits = 2,
            jaLabel = "1目交差 右上（下が裏）",
            enLabel = "1/1 right cross with purl",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "1/1 RPC",
        )

    private val cable1Over1LeftPurl =
        SymbolDefinition(
            id = id("cable-1x1-l-p"),
            category = SymbolCategory.KNIT,
            pathData =
                "M 0.1 0.1 L 0.9 0.9 " +
                    "M 0.1 0.9 L 0.4 0.6 " +
                    "M 0.6 0.4 L 0.9 0.1 " +
                    "M 0.1 0.5 L 0.3 0.5",
            widthUnits = 2,
            jaLabel = "1目交差 左上（下が裏）",
            enLabel = "1/1 left cross with purl",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "1/1 LPC",
        )

    // endregion

    // region — bobbles & wraps ----------------------------------------------

    private val bobble =
        SymbolDefinition(
            id = id("bobble"),
            category = SymbolCategory.KNIT,
            // Lens shape: two symmetric cubic Béziers joined at top & bottom.
            pathData =
                "M 0.5 0.15 " +
                    "C 0.75 0.25 0.75 0.75 0.5 0.85 " +
                    "C 0.25 0.75 0.25 0.25 0.5 0.15 Z",
            jaLabel = "玉編み",
            enLabel = "Bobble",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "mb",
        )

    private val wrapAndTurn =
        SymbolDefinition(
            id = id("w-and-t"),
            category = SymbolCategory.KNIT,
            // Vertical bar wrapped by a small loop around the base.
            pathData =
                "M 0.5 0.1 L 0.5 0.75 " +
                    "M 0.35 0.85 Q 0.5 0.65 0.65 0.85",
            jaLabel = "引き返し編み",
            enLabel = "Wrap and turn",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "w&t",
            parameterSlots =
                listOf(
                    ParameterSlot(
                        key = "rowLabel",
                        x = 0.5,
                        y = 0.4,
                        defaultValue = null,
                        jaLabel = "段ラベル",
                        enLabel = "Row label",
                    ),
                ),
        )

    private val knitBelow =
        SymbolDefinition(
            id = id("k-below"),
            category = SymbolCategory.KNIT,
            // Vertical stem with an extra short tick at the bottom.
            pathData = "M 0.5 0.1 L 0.5 0.9 M 0.35 0.95 L 0.65 0.95",
            jaLabel = "引き上げ編み（表）",
            enLabel = "Knit 1 below",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "k1b",
        )

    private val purlBelow =
        SymbolDefinition(
            id = id("p-below"),
            category = SymbolCategory.KNIT,
            // Purl bar at y=0.65 plus a "below" tick at y=0.95. The purl bar sits
            // slightly higher than the plain-purl convention (y=0.75) to preserve
            // ~0.3-unit clearance from the bottom tick so both strokes stay
            // distinguishable at small cell sizes (≥~7px separation at 24dp).
            pathData = "M 0.35 0.65 L 0.65 0.65 M 0.35 0.95 L 0.65 0.95",
            jaLabel = "引き上げ編み（裏）",
            enLabel = "Purl 1 below",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "p1b",
        )

    // endregion

    // region — pass stitch over ---------------------------------------------

    private val psso =
        SymbolDefinition(
            id = id("psso"),
            category = SymbolCategory.KNIT,
            // Dotted-style horizontal bar suggesting the passed-over stitch.
            pathData = "M 0.15 0.5 L 0.35 0.5 M 0.45 0.5 L 0.55 0.5 M 0.65 0.5 L 0.85 0.5",
            jaLabel = "かぶせ目",
            enLabel = "Pass slipped stitch over",
            jisReference = "JIS L 0201-1995 棒針編目",
            cycName = "psso",
        )

    // endregion

    // region — placeholder for no-stitch grid cells -------------------------

    private val noStitch =
        SymbolDefinition(
            id = id("no-stitch"),
            category = SymbolCategory.KNIT,
            // Filled square indicated by diagonal cross.
            pathData = "M 0.15 0.15 L 0.85 0.85 M 0.85 0.15 L 0.15 0.85",
            jaLabel = "なし（目なし）",
            enLabel = "No stitch",
            jisReference = "JIS L 0201-1995 棒針編目",
            jaDescription = "この位置に目がないことを示す。",
            enDescription = "Grid filler for charts that represent variable-width rows.",
        )

    // endregion

    val all: List<SymbolDefinition> =
        listOf(
            // basics
            k,
            p,
            yo,
            slKnitwise,
            slPurlwise,
            floatFront,
            // twists
            ktblRight,
            ktblLeft,
            ptblRight,
            // decreases
            k2togRight,
            k2togLeft,
            p2togRight,
            p2togLeft,
            k3togCentre,
            k3togRight,
            k3togLeft,
            // increases
            m1Right,
            m1Left,
            castOn,
            // bind-off
            bindOff,
            // cables
            cable1Over1Right,
            cable1Over1Left,
            cable2Over2Right,
            cable2Over2Left,
            cable3Over3Right,
            cable3Over3Left,
            cable1Over1RightPurl,
            cable1Over1LeftPurl,
            // bobbles & wraps
            bobble,
            wrapAndTurn,
            knitBelow,
            purlBelow,
            // misc
            psso,
            noStitch,
        )
}
