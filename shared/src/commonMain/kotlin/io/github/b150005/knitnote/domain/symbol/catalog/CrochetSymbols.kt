package io.github.b150005.knitnote.domain.symbol.catalog

import io.github.b150005.knitnote.domain.symbol.ParameterSlot
import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import io.github.b150005.knitnote.domain.symbol.SymbolDefinition

/**
 * JIS L 0201-1995 Table 2 (かぎ針編目) crochet symbol set, cross-referenced with the
 * Craft Yarn Council crochet chart. All entries live under the `jis.crochet.*`
 * namespace per ADR-008's symbol-sources policy; where JIS and CYC agree (most basic
 * stitches) the JIS reference is authoritative.
 *
 * Glyph conventions (Phase 30.2 first pass):
 * - Tall stitches (`hdc` → `qtr`) share a vertical stem with a top crossbar; height
 *   is encoded by the number of slashes crossing the stem (`dc`=1, `tr`=2, `dtr`=3,
 *   `qtr`=4) — the standard JIS/JP publisher convention.
 * - Decreases (`scNtog`/`dcNtog`) converge angled strokes at the top; crossbars /
 *   slashes are retained per-stroke so the stitch type is still recognisable.
 * - Clusters (`dc-cluster-N`) stand the N strokes upright and bundle them with a
 *   closed oval above, matching the 玉編み (bundle) convention.
 * - Pre/post stitches (`fpdc`, `bpdc`) extend `dc` with a bracket/hook at the base
 *   indicating which side of the post is worked.
 * - Loop-only markers (`sc-fl`, `sc-bl`) add a semicircle arc below `sc`.
 *
 * See `docs/en/symbol-review/phase-30.2.md` for the Knitter advisory review and
 * the open-geometry questions carried forward from Phase 30.1.
 */
internal object CrochetSymbols {
    private const val CATEGORY_ID_PREFIX = "jis.crochet"
    private const val JIS_REF = "JIS L 0201-1995 かぎ針編目"

    private fun id(suffix: String): String = "$CATEGORY_ID_PREFIX.$suffix"

    // region — basic stitches (JIS L 0201 Table 2) ---------------------------

    private val ch =
        SymbolDefinition(
            id = id("ch"),
            category = SymbolCategory.CROCHET,
            // Horizontal eye — an ellipse wider than it is tall, traced by four
            // cubic Béziers. The most recognisable crochet glyph; all chain-based
            // compound symbols (cluster, turning-ch, picot) reuse this shape.
            pathData =
                "M 0.2 0.5 " +
                    "C 0.2 0.37 0.33 0.25 0.5 0.25 " +
                    "C 0.67 0.25 0.8 0.37 0.8 0.5 " +
                    "C 0.8 0.63 0.67 0.75 0.5 0.75 " +
                    "C 0.33 0.75 0.2 0.63 0.2 0.5 Z",
            jaLabel = "鎖編み",
            enLabel = "Chain (ch)",
            jisReference = JIS_REF,
            cycName = "ch",
            jaDescription = "引き抜き編みを連続したもの。記号は横長の楕円。",
            enDescription = "Chain stitch; rendered as a horizontal oval.",
        )

    private val slSt =
        SymbolDefinition(
            id = id("sl-st"),
            category = SymbolCategory.CROCHET,
            // Small closed disc — stroked as a tight outlined ellipse. JIS L 0201
            // Table 2 specifies a filled dot/triangle; stroked rendering is a
            // first-pass approximation and is called out in the Phase 30.2 review.
            pathData =
                "M 0.4 0.5 " +
                    "C 0.4 0.42 0.45 0.35 0.5 0.35 " +
                    "C 0.55 0.35 0.6 0.42 0.6 0.5 " +
                    "C 0.6 0.58 0.55 0.65 0.5 0.65 " +
                    "C 0.45 0.65 0.4 0.58 0.4 0.5 Z",
            jaLabel = "引き抜き編み",
            enLabel = "Slip stitch (sl st)",
            jisReference = JIS_REF,
            cycName = "sl st",
            jaDescription = "最小の高さで糸を引き抜く。記号は小さな黒丸。",
            enDescription = "Shortest crochet stitch; rendered as a small dot.",
        )

    private val sc =
        SymbolDefinition(
            id = id("sc"),
            category = SymbolCategory.CROCHET,
            // Short plus sign — vertical + horizontal of equal half-height, kept
            // inside the cell so sc / hdc / dc visually scale with stitch height.
            pathData = "M 0.5 0.25 L 0.5 0.75 M 0.3 0.5 L 0.7 0.5",
            jaLabel = "細編み",
            enLabel = "Single crochet (sc)",
            jisReference = JIS_REF,
            cycName = "sc",
            jaDescription = "引き抜き編みの次に低い編み目。記号は短い十字。",
            enDescription = "Shortest standing crochet stitch; rendered as a short plus.",
        )

    private val hdc =
        SymbolDefinition(
            id = id("hdc"),
            category = SymbolCategory.CROCHET,
            // Full-height stem with a top crossbar only — no diagonal slash. JIS
            // differentiates hdc from dc by the absence of a slash.
            pathData = "M 0.5 0.1 L 0.5 0.9 M 0.3 0.1 L 0.7 0.1",
            jaLabel = "中長編み",
            enLabel = "Half double crochet (hdc)",
            jisReference = JIS_REF,
            cycName = "hdc",
            jaDescription = "長編みよりやや低い編み目。記号は縦線＋上部の横線（Tの形）。",
            enDescription = "Between sc and dc in height; rendered as a T.",
        )

    private val dc =
        SymbolDefinition(
            id = id("dc"),
            category = SymbolCategory.CROCHET,
            // hdc + one diagonal slash crossing the stem below the top crossbar.
            pathData =
                "M 0.5 0.1 L 0.5 0.9 " +
                    "M 0.3 0.1 L 0.7 0.1 " +
                    "M 0.3 0.3 L 0.7 0.4",
            jaLabel = "長編み",
            enLabel = "Double crochet (dc)",
            jisReference = JIS_REF,
            cycName = "dc",
            jaDescription = "糸を1回掛けて引き抜く。記号はT＋1本の斜線。",
            enDescription = "One yo before insertion; rendered as T with one slash.",
        )

    private val tr =
        SymbolDefinition(
            id = id("tr"),
            category = SymbolCategory.CROCHET,
            // hdc + two diagonal slashes.
            pathData =
                "M 0.5 0.1 L 0.5 0.9 " +
                    "M 0.3 0.1 L 0.7 0.1 " +
                    "M 0.3 0.25 L 0.7 0.35 " +
                    "M 0.3 0.45 L 0.7 0.55",
            jaLabel = "長々編み",
            enLabel = "Treble crochet (tr)",
            jisReference = JIS_REF,
            cycName = "tr",
            jaDescription = "糸を2回掛けて引き抜く。記号はT＋2本の斜線。",
            enDescription = "Two yo before insertion; rendered as T with two slashes.",
        )

    private val dtr =
        SymbolDefinition(
            id = id("dtr"),
            category = SymbolCategory.CROCHET,
            // hdc + three diagonal slashes; matches the JA `三つ巻長編み` notation.
            pathData =
                "M 0.5 0.1 L 0.5 0.9 " +
                    "M 0.3 0.1 L 0.7 0.1 " +
                    "M 0.3 0.25 L 0.7 0.32 " +
                    "M 0.3 0.4 L 0.7 0.47 " +
                    "M 0.3 0.55 L 0.7 0.62",
            jaLabel = "三つ巻長編み",
            enLabel = "Double treble crochet (dtr)",
            jisReference = JIS_REF,
            cycName = "dtr",
            jaDescription = "糸を3回掛けて引き抜く。記号はT＋3本の斜線。",
            enDescription = "Three yo before insertion; rendered as T with three slashes.",
        )

    private val qtr =
        SymbolDefinition(
            id = id("qtr"),
            category = SymbolCategory.CROCHET,
            // hdc + four diagonal slashes; the JA `四つ巻長編み`. The id, cycName
            // and labels all use the US "quadruple treble (qtr)" convention that
            // matches JIS + Nihon Vogue (where 四つ巻 = 4× yo = quad tr). The UK
            // term "triple treble" is registered as an alias so patterns written
            // in UK notation still resolve to this glyph.
            pathData =
                "M 0.5 0.1 L 0.5 0.9 " +
                    "M 0.3 0.1 L 0.7 0.1 " +
                    "M 0.3 0.22 L 0.7 0.28 " +
                    "M 0.3 0.36 L 0.7 0.42 " +
                    "M 0.3 0.5 L 0.7 0.56 " +
                    "M 0.3 0.64 L 0.7 0.7",
            jaLabel = "四つ巻長編み",
            enLabel = "Quadruple treble crochet (qtr)",
            jisReference = JIS_REF,
            cycName = "qtr",
            aliases = listOf("trtr", "triple treble"),
            jaDescription = "糸を4回掛けて引き抜く。記号はT＋4本の斜線。",
            enDescription = "Four yo before insertion; rendered as T with four slashes.",
        )

    // endregion

    // region — decreases -----------------------------------------------------

    private val sc2tog =
        SymbolDefinition(
            id = id("sc2tog"),
            category = SymbolCategory.CROCHET,
            // Two sc strokes angled inward and meeting at a shared top apex, with
            // a single crossbar spanning both arms (the sc "+" signature).
            pathData =
                "M 0.25 0.9 L 0.5 0.25 " +
                    "M 0.75 0.9 L 0.5 0.25 " +
                    "M 0.3 0.55 L 0.7 0.55",
            jaLabel = "細編み2目一度",
            enLabel = "Single crochet 2 together (sc2tog)",
            jisReference = JIS_REF,
            cycName = "sc2tog",
        )

    private val sc3tog =
        SymbolDefinition(
            id = id("sc3tog"),
            category = SymbolCategory.CROCHET,
            // Three sc strokes (left / centre / right) converging at the apex
            // with a single crossbar spanning the group.
            pathData =
                "M 0.2 0.9 L 0.5 0.2 " +
                    "M 0.5 0.9 L 0.5 0.2 " +
                    "M 0.8 0.9 L 0.5 0.2 " +
                    "M 0.25 0.55 L 0.75 0.55",
            jaLabel = "細編み3目一度",
            enLabel = "Single crochet 3 together (sc3tog)",
            jisReference = JIS_REF,
            cycName = "sc3tog",
        )

    private val dc2tog =
        SymbolDefinition(
            id = id("dc2tog"),
            category = SymbolCategory.CROCHET,
            // Two dc strokes angled inward, joined by a short horizontal at the
            // apex (their shared top bar), with one slash per stroke.
            pathData =
                "M 0.2 0.9 L 0.4 0.18 " +
                    "M 0.8 0.9 L 0.6 0.18 " +
                    "M 0.4 0.18 L 0.6 0.18 " +
                    "M 0.15 0.5 L 0.35 0.55 " +
                    "M 0.65 0.55 L 0.85 0.5",
            jaLabel = "長編み2目一度",
            enLabel = "Double crochet 2 together (dc2tog)",
            jisReference = JIS_REF,
            cycName = "dc2tog",
        )

    private val dc3tog =
        SymbolDefinition(
            id = id("dc3tog"),
            category = SymbolCategory.CROCHET,
            // Three dc strokes (outer pair angled, centre vertical) sharing a top
            // bar, each carrying a slash.
            pathData =
                "M 0.15 0.9 L 0.4 0.18 " +
                    "M 0.5 0.9 L 0.5 0.18 " +
                    "M 0.85 0.9 L 0.6 0.18 " +
                    "M 0.4 0.18 L 0.6 0.18 " +
                    "M 0.1 0.5 L 0.3 0.55 " +
                    "M 0.4 0.55 L 0.6 0.55 " +
                    "M 0.7 0.55 L 0.9 0.5",
            jaLabel = "長編み3目一度",
            enLabel = "Double crochet 3 together (dc3tog)",
            jisReference = JIS_REF,
            cycName = "dc3tog",
        )

    // endregion

    // region — clusters (玉編み) --------------------------------------------

    private val dcCluster3 =
        SymbolDefinition(
            id = id("dc-cluster-3"),
            category = SymbolCategory.CROCHET,
            // Three vertical dc strokes bundled by a closed oval at the top (the
            // 玉 bundle). Each stroke carries one slash to confirm dc height.
            pathData =
                "M 0.25 0.9 L 0.25 0.3 " +
                    "M 0.5 0.9 L 0.5 0.3 " +
                    "M 0.75 0.9 L 0.75 0.3 " +
                    "M 0.15 0.2 C 0.15 0.08 0.35 0.03 0.5 0.03 " +
                    "C 0.65 0.03 0.85 0.08 0.85 0.2 " +
                    "C 0.85 0.32 0.65 0.32 0.5 0.32 " +
                    "C 0.35 0.32 0.15 0.32 0.15 0.2 Z " +
                    "M 0.15 0.55 L 0.35 0.6 " +
                    "M 0.4 0.55 L 0.6 0.6 " +
                    "M 0.65 0.55 L 0.85 0.6",
            jaLabel = "3目の長編み玉編み",
            enLabel = "3-dc cluster",
            jisReference = JIS_REF,
            cycName = "cl3",
            jaDescription = "3目の長編みを頂点でまとめて1目にする玉編み。",
            enDescription = "Three dc strokes bundled into a single stitch at the top.",
        )

    private val dcCluster5 =
        SymbolDefinition(
            id = id("dc-cluster-5"),
            category = SymbolCategory.CROCHET,
            // Wider 5-dc cluster spanning two stitch columns. widthUnits=2 tells
            // the renderer to stretch this unit-square path across two cells.
            pathData =
                "M 0.1 0.9 L 0.1 0.3 " +
                    "M 0.3 0.9 L 0.3 0.3 " +
                    "M 0.5 0.9 L 0.5 0.3 " +
                    "M 0.7 0.9 L 0.7 0.3 " +
                    "M 0.9 0.9 L 0.9 0.3 " +
                    "M 0.05 0.2 C 0.05 0.08 0.25 0.03 0.5 0.03 " +
                    "C 0.75 0.03 0.95 0.08 0.95 0.2 " +
                    "C 0.95 0.32 0.75 0.32 0.5 0.32 " +
                    "C 0.25 0.32 0.05 0.32 0.05 0.2 Z " +
                    "M 0.02 0.55 L 0.18 0.6 " +
                    "M 0.22 0.55 L 0.38 0.6 " +
                    "M 0.42 0.55 L 0.58 0.6 " +
                    "M 0.62 0.55 L 0.78 0.6 " +
                    "M 0.82 0.55 L 0.98 0.6",
            widthUnits = 2,
            jaLabel = "5目の長編み玉編み",
            enLabel = "5-dc cluster",
            jisReference = JIS_REF,
            cycName = "cl5",
            jaDescription = "5目の長編みをまとめて1目にする玉編み。2目分の幅を占める。",
            enDescription = "Five dc strokes bundled into one stitch; spans two columns.",
        )

    // endregion

    // region — increases (2-in-1) -------------------------------------------

    private val incSc =
        SymbolDefinition(
            id = id("inc-sc"),
            category = SymbolCategory.CROCHET,
            // Two sc glyphs diverging from a shared base — the crochet V-spread
            // that signals "work two sc into the same stitch".
            pathData =
                "M 0.5 0.9 L 0.25 0.35 " +
                    "M 0.5 0.9 L 0.75 0.35 " +
                    "M 0.2 0.55 L 0.4 0.55 " +
                    "M 0.6 0.55 L 0.8 0.55",
            jaLabel = "細編み2目編み入れる",
            enLabel = "2 sc in one stitch",
            jisReference = JIS_REF,
            cycName = "inc sc",
        )

    private val incDc =
        SymbolDefinition(
            id = id("inc-dc"),
            category = SymbolCategory.CROCHET,
            // Two dc glyphs diverging from a shared base, each with its own top
            // crossbar and slash.
            pathData =
                "M 0.5 0.9 L 0.25 0.2 " +
                    "M 0.5 0.9 L 0.75 0.2 " +
                    "M 0.15 0.2 L 0.35 0.2 " +
                    "M 0.65 0.2 L 0.85 0.2 " +
                    "M 0.18 0.45 L 0.3 0.5 " +
                    "M 0.7 0.5 L 0.82 0.45",
            jaLabel = "長編み2目編み入れる",
            enLabel = "2 dc in one stitch",
            jisReference = JIS_REF,
            cycName = "inc dc",
        )

    // endregion

    // region — round-specific ------------------------------------------------

    private val magicRing =
        SymbolDefinition(
            id = id("magic-ring"),
            category = SymbolCategory.CROCHET,
            // Large open circle with an internal cross marking the ring centre —
            // visually distinct from `jis.knit.yo` which is smaller and unmarked.
            pathData =
                "M 0.5 0.1 " +
                    "C 0.72 0.1 0.9 0.28 0.9 0.5 " +
                    "C 0.9 0.72 0.72 0.9 0.5 0.9 " +
                    "C 0.28 0.9 0.1 0.72 0.1 0.5 " +
                    "C 0.1 0.28 0.28 0.1 0.5 0.1 Z " +
                    "M 0.45 0.5 L 0.55 0.5 " +
                    "M 0.5 0.45 L 0.5 0.55",
            jaLabel = "輪の作り目",
            enLabel = "Magic ring",
            jisReference = JIS_REF,
            cycName = "magic ring",
            jaDescription = "円編みの中心に作る可変リング。記号は中心に印のある大きな円。",
            enDescription = "Adjustable ring for working rounds; drawn as a large marked circle.",
        )

    // endregion

    // region — post stitches -------------------------------------------------

    private val fpdc =
        SymbolDefinition(
            id = id("fpdc"),
            category = SymbolCategory.CROCHET,
            // dc base shortened, plus a right-pointing hook at the base indicating
            // the hook reaches around the front of the post.
            pathData =
                "M 0.5 0.1 L 0.5 0.8 " +
                    "M 0.3 0.1 L 0.7 0.1 " +
                    "M 0.3 0.3 L 0.7 0.4 " +
                    "M 0.5 0.8 L 0.75 0.8 " +
                    "M 0.75 0.8 L 0.75 0.95",
            jaLabel = "表引き上げ長編み",
            enLabel = "Front-post dc (fpdc)",
            jisReference = JIS_REF,
            cycName = "fpdc",
            jaDescription = "前段の目の柱を手前側から拾って編む。記号は長編み＋右向きの鉤。",
            enDescription = "dc worked around the front of a post; rendered as dc with a right hook.",
        )

    private val bpdc =
        SymbolDefinition(
            id = id("bpdc"),
            category = SymbolCategory.CROCHET,
            // Mirror of fpdc — the hook points left, indicating the back of the
            // post.
            pathData =
                "M 0.5 0.1 L 0.5 0.8 " +
                    "M 0.3 0.1 L 0.7 0.1 " +
                    "M 0.3 0.3 L 0.7 0.4 " +
                    "M 0.5 0.8 L 0.25 0.8 " +
                    "M 0.25 0.8 L 0.25 0.95",
            jaLabel = "裏引き上げ長編み",
            enLabel = "Back-post dc (bpdc)",
            jisReference = JIS_REF,
            cycName = "bpdc",
            jaDescription = "前段の目の柱を奥側から拾って編む。記号は長編み＋左向きの鉤。",
            enDescription = "dc worked around the back of a post; rendered as dc with a left hook.",
        )

    // endregion

    // region — loop-only markers --------------------------------------------

    private val scFl =
        SymbolDefinition(
            id = id("sc-fl"),
            category = SymbolCategory.CROCHET,
            // sc glyph with a downward semicircle below indicating "work into the
            // front loop only". The arc opens downward.
            pathData =
                "M 0.5 0.2 L 0.5 0.7 " +
                    "M 0.3 0.45 L 0.7 0.45 " +
                    "M 0.3 0.78 Q 0.5 0.95 0.7 0.78",
            jaLabel = "細編み（すじ編み 手前側）",
            enLabel = "sc in front loop only (FLO)",
            jisReference = JIS_REF,
            cycName = "sc flo",
        )

    private val scBl =
        SymbolDefinition(
            id = id("sc-bl"),
            category = SymbolCategory.CROCHET,
            // sc glyph with an upward (inverted) semicircle below indicating
            // "back loop only". The arc opens upward.
            pathData =
                "M 0.5 0.2 L 0.5 0.7 " +
                    "M 0.3 0.45 L 0.7 0.45 " +
                    "M 0.3 0.92 Q 0.5 0.75 0.7 0.92",
            jaLabel = "細編み（すじ編み 向こう側）",
            enLabel = "sc in back loop only (BLO)",
            jisReference = JIS_REF,
            cycName = "sc blo",
        )

    // endregion

    // region — decorative & special ----------------------------------------

    private val popcorn =
        SymbolDefinition(
            id = id("popcorn"),
            category = SymbolCategory.CROCHET,
            // Closed circle with four short radial bursts — distinguishes popcorn
            // from plain yo (a simple open circle) at a glance.
            pathData =
                "M 0.5 0.25 " +
                    "C 0.64 0.25 0.75 0.36 0.75 0.5 " +
                    "C 0.75 0.64 0.64 0.75 0.5 0.75 " +
                    "C 0.36 0.75 0.25 0.64 0.25 0.5 " +
                    "C 0.25 0.36 0.36 0.25 0.5 0.25 Z " +
                    "M 0.5 0.1 L 0.5 0.2 " +
                    "M 0.5 0.8 L 0.5 0.9 " +
                    "M 0.1 0.5 L 0.2 0.5 " +
                    "M 0.8 0.5 L 0.9 0.5",
            jaLabel = "パプコーン編み",
            enLabel = "Popcorn",
            jisReference = JIS_REF,
            cycName = "pc",
            jaDescription = "複数目を立ち上がりから1目に引き抜いて膨らませる。記号は放射状の円。",
            enDescription = "Multiple stitches pulled together; drawn as a circle with rays.",
        )

    private val picot3 =
        SymbolDefinition(
            id = id("picot-3"),
            category = SymbolCategory.CROCHET,
            // Short stem rising into a loop (3-chain picot) — represented as a
            // stem with a small closed chain-loop at the top.
            pathData =
                "M 0.5 0.55 L 0.5 0.9 " +
                    "M 0.3 0.55 C 0.3 0.25 0.7 0.25 0.7 0.55 Z",
            jaLabel = "ピコット",
            enLabel = "Picot (3-ch)",
            jisReference = JIS_REF,
            cycName = "picot",
            jaDescription = "3目の鎖を輪にしたピコット。装飾に用いる。",
            enDescription = "Decorative 3-chain loop; rendered as a small loop over a stem.",
        )

    private val turningCh =
        SymbolDefinition(
            id = id("turning-ch"),
            category = SymbolCategory.CROCHET,
            // Three stacked chain-ovals — the standard JA chart marker for a
            // turning chain at the start of a row / round (立ち上がり).
            pathData =
                "M 0.3 0.88 C 0.3 0.8 0.7 0.8 0.7 0.88 " +
                    "C 0.7 0.96 0.3 0.96 0.3 0.88 Z " +
                    "M 0.3 0.65 C 0.3 0.57 0.7 0.57 0.7 0.65 " +
                    "C 0.7 0.73 0.3 0.73 0.3 0.65 Z " +
                    "M 0.3 0.42 C 0.3 0.34 0.7 0.34 0.7 0.42 " +
                    "C 0.7 0.5 0.3 0.5 0.3 0.42 Z",
            jaLabel = "立ち上がりの鎖",
            enLabel = "Turning chain",
            jisReference = JIS_REF,
            cycName = "turning ch",
            jaDescription = "段の始まりに編む鎖編み。記号は鎖を縦に積み重ねた形。",
            enDescription = "Chain at the start of a row; rendered as stacked chain ovals.",
        )

    private val chSpace =
        SymbolDefinition(
            id = id("ch-space"),
            category = SymbolCategory.CROCHET,
            // Arc spanning the stitch width with a parametric `count` slot below
            // — a ch-space bracket typically labelled `ch-N` on pattern charts.
            pathData = "M 0.15 0.55 C 0.15 0.25 0.85 0.25 0.85 0.55",
            jaLabel = "鎖編み（スペース）",
            enLabel = "Chain space",
            jisReference = JIS_REF,
            cycName = "ch-sp",
            parameterSlots =
                listOf(
                    ParameterSlot(
                        key = "count",
                        x = 0.5,
                        y = 0.75,
                        defaultValue = "n",
                        jaLabel = "目数",
                        enLabel = "Chain count",
                    ),
                ),
            jaDescription = "鎖編みで空けた間隔。記号下の数字が鎖の目数。",
            enDescription = "Skipped span of chains; the number below is the chain count.",
        )

    // endregion

    val all: List<SymbolDefinition> =
        listOf(
            // basic stitches
            ch,
            slSt,
            sc,
            hdc,
            dc,
            tr,
            dtr,
            qtr,
            // decreases
            sc2tog,
            sc3tog,
            dc2tog,
            dc3tog,
            // clusters
            dcCluster3,
            dcCluster5,
            // increases
            incSc,
            incDc,
            // round-specific
            magicRing,
            // post stitches
            fpdc,
            bpdc,
            // loop markers
            scFl,
            scBl,
            // decorative & special
            popcorn,
            picot3,
            turningCh,
            chSpace,
        )
}
