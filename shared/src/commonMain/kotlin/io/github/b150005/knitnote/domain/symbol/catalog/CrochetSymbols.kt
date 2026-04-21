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
            // Small filled disc — JIS L 0201 Table 2 + CYC + every JP publisher
            // (Vogue / Bunka / Ondori) render sl-st as a solid filled dot. Phase
            // 30.2-fix flipped this from a stroked outline to a filled glyph by
            // setting `fill = true`; the path itself is the same closed ellipse.
            pathData =
                "M 0.4 0.5 " +
                    "C 0.4 0.42 0.45 0.35 0.5 0.35 " +
                    "C 0.55 0.35 0.6 0.42 0.6 0.5 " +
                    "C 0.6 0.58 0.55 0.65 0.5 0.65 " +
                    "C 0.45 0.65 0.4 0.58 0.4 0.5 Z",
            fill = true,
            jaLabel = "引き抜き編み",
            enLabel = "Slip stitch (sl st)",
            jisReference = JIS_REF,
            cycName = "sl st",
            jaDescription = "最小の高さで糸を引き抜く。記号は小さな黒丸。",
            enDescription = "Shortest crochet stitch; rendered as a small filled dot.",
        )

    private val sc =
        SymbolDefinition(
            id = id("sc"),
            category = SymbolCategory.CROCHET,
            // Short inclined cross (×) — JIS L 0201 Table 2 form; most JP
            // publishers (Vogue / Bunka) use the same. Phase 30.2-fix switched
            // from the CYC `+` to JIS `×` to align with the `jis.*` namespace
            // authority chosen in ADR-008.
            pathData = "M 0.3 0.3 L 0.7 0.7 M 0.7 0.3 L 0.3 0.7",
            jaLabel = "細編み",
            enLabel = "Single crochet (sc)",
            jisReference = JIS_REF,
            cycName = "sc",
            jaDescription = "引き抜き編みの次に低い編み目。記号は短い斜め十字（×）。",
            enDescription = "Shortest standing crochet stitch; rendered as a short inclined cross.",
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

    // region — decreases & edge stitches ------------------------------------

    private val reverseSc =
        SymbolDefinition(
            id = id("reverse-sc"),
            category = SymbolCategory.CROCHET,
            // JIS is silent on a dedicated reverse-sc glyph; JP publishers
            // (Vogue / Bunka) and CYC overlay the sc `×` with a small
            // directional marker. The sc cross is compressed to y∈[0.15, 0.6]
            // (clear of the base chevron) and a symmetric left-pointing V at
            // y∈[0.77, 0.97] centred around y=0.87 signals the L→R working
            // direction (arrow-head style rather than a checkmark). The
            // chevron form (rather than a filled arrow-head, which our
            // stroke-only renderer cannot draw cheaply) stays legible at 24dp
            // and does not collide with `sc-fl`/`sc-bl` which use arcs. Tips
            // held back to y=0.77/0.97 to keep a 3% margin from the cell
            // edge so stroke caps do not clip at any renderer.
            pathData =
                "M 0.3 0.15 L 0.7 0.6 " +
                    "M 0.7 0.15 L 0.3 0.6 " +
                    "M 0.35 0.77 L 0.18 0.87 L 0.35 0.97",
            jaLabel = "逆細編み",
            enLabel = "Reverse single crochet (reverse sc)",
            jisReference = JIS_REF,
            cycName = "reverse sc",
            aliases = listOf("crab stitch", "rsc"),
            jaDescription = "細編みを左から右へ編む（逆方向）。記号は×＋左向きの矢印。",
            enDescription = "sc worked left-to-right; rendered as sc cross with a leftward chevron.",
        )

    private val sc2tog =
        SymbolDefinition(
            id = id("sc2tog"),
            category = SymbolCategory.CROCHET,
            // Two sc strokes angled inward and meeting at a shared top apex,
            // with a short crossbar spanning the arms close to the apex (the
            // JIS sc-cross signature near y≈0.30). Phase 30.2-fix moved the bar
            // up from y=0.55 → y=0.30 to match JIS authority for the namespace.
            pathData =
                "M 0.25 0.9 L 0.5 0.25 " +
                    "M 0.75 0.9 L 0.5 0.25 " +
                    "M 0.38 0.32 L 0.62 0.32",
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
            // with a short crossbar spanning the converging arms close to the
            // apex (JIS form). Phase 30.2-fix moved the bar from y=0.55 → y=0.28.
            pathData =
                "M 0.2 0.9 L 0.5 0.2 " +
                    "M 0.5 0.9 L 0.5 0.2 " +
                    "M 0.8 0.9 L 0.5 0.2 " +
                    "M 0.35 0.28 L 0.65 0.28",
            jaLabel = "細編み3目一度",
            enLabel = "Single crochet 3 together (sc3tog)",
            jisReference = JIS_REF,
            cycName = "sc3tog",
        )

    private val dc2tog =
        SymbolDefinition(
            id = id("dc2tog"),
            category = SymbolCategory.CROCHET,
            // Two dc strokes angled inward joined by a long horizontal top-bar
            // that visibly extends past the outermost stroke endpoints. Phase
            // 30.2-fix widened the top-bar from `0.4→0.6` to `0.15→0.85` so the
            // open horizontal line is clearly distinguishable from the closed
            // oval used by `dc-cluster-3`/`-5` at 24dp. The two slashes mirror
            // each other around the vertical centreline (JIS dec convention,
            // distinct from the uniform-direction slashes used on the cluster
            // glyphs); each one crosses its arm near (0.31, 0.54) on the left
            // and (0.70, 0.54) on the right.
            pathData =
                "M 0.2 0.9 L 0.4 0.18 " +
                    "M 0.8 0.9 L 0.6 0.18 " +
                    "M 0.15 0.18 L 0.85 0.18 " +
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
            // Three dc strokes (outer pair angled, centre vertical) sharing a
            // long horizontal top-bar that extends past the outermost stroke
            // endpoints. Phase 30.2-fix widened the top-bar from `0.4→0.6` to
            // `0.10→0.90` to disambiguate from the cluster oval at 24dp.
            pathData =
                "M 0.15 0.9 L 0.4 0.18 " +
                    "M 0.5 0.9 L 0.5 0.18 " +
                    "M 0.85 0.9 L 0.6 0.18 " +
                    "M 0.1 0.18 L 0.9 0.18 " +
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

    private val hdcCluster3 =
        SymbolDefinition(
            id = id("hdc-cluster-3"),
            category = SymbolCategory.CROCHET,
            // Mirrors `dcCluster3` geometry (three vertical stems + a closed
            // oval cap forming the 玉 bundle) but omits the three slashes —
            // hdc carries no slash, so the cluster signature is the bundle
            // under an oval without height markers. Phase 30.3 addition
            // closing the coverage gap flagged in the Phase 30.2 Knitter
            // advisory §4. Phase 30.4 widened outer stem spacing from
            // 0.25/0.75 → 0.22/0.78: without slashes the three parallel stems
            // can read as one thick line on low-DPI Android at 24dp; the
            // extra 6% inter-stem gap disambiguates without breaking the cap
            // enclosure (oval spans 0.15→0.85 untouched).
            pathData =
                "M 0.22 0.9 L 0.22 0.3 " +
                    "M 0.5 0.9 L 0.5 0.3 " +
                    "M 0.78 0.9 L 0.78 0.3 " +
                    "M 0.15 0.2 C 0.15 0.08 0.35 0.03 0.5 0.03 " +
                    "C 0.65 0.03 0.85 0.08 0.85 0.2 " +
                    "C 0.85 0.32 0.65 0.32 0.5 0.32 " +
                    "C 0.35 0.32 0.15 0.32 0.15 0.2 Z",
            jaLabel = "3目の中長編み玉編み",
            enLabel = "3-hdc cluster",
            jisReference = JIS_REF,
            cycName = "hdc-cl3",
            // `bob-hdc` is the common EN informal name for the hdc-bobble
            // cluster. `hdc3tog-puff` was considered but dropped: the
            // "-puff" suffix would produce false-positive dictionary hits
            // for searchers looking for `jis.crochet.puff`.
            aliases = listOf("bob-hdc"),
            jaDescription = "3目の中長編みを頂点でまとめて1目にする玉編み。",
            enDescription = "Three hdc strokes bundled into one stitch; no slashes (hdc has no slash).",
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

    private val hdcCluster5 =
        SymbolDefinition(
            id = id("hdc-cluster-5"),
            category = SymbolCategory.CROCHET,
            // Phase 30.4 addition — mirrors `dcCluster5` geometry (five
            // vertical stems + wide closed oval cap, widthUnits=2) with all
            // five slashes stripped per the hdc signal (slash absence = hdc
            // height, same convention as `hdcCluster3` vs `dcCluster3`).
            // Closes the Phase 30.3 §4 gap for commercial-JP doily / lace
            // patterns that pair 5-stitch hdc bundles with `dc-cluster-5`.
            pathData =
                "M 0.1 0.9 L 0.1 0.3 " +
                    "M 0.3 0.9 L 0.3 0.3 " +
                    "M 0.5 0.9 L 0.5 0.3 " +
                    "M 0.7 0.9 L 0.7 0.3 " +
                    "M 0.9 0.9 L 0.9 0.3 " +
                    "M 0.05 0.2 C 0.05 0.08 0.25 0.03 0.5 0.03 " +
                    "C 0.75 0.03 0.95 0.08 0.95 0.2 " +
                    "C 0.95 0.32 0.75 0.32 0.5 0.32 " +
                    "C 0.25 0.32 0.05 0.32 0.05 0.2 Z",
            widthUnits = 2,
            jaLabel = "5目の中長編み玉編み",
            enLabel = "5-hdc cluster",
            jisReference = JIS_REF,
            cycName = "hdc-cl5",
            aliases = listOf("bob-hdc-5"),
            jaDescription = "5目の中長編みを頂点でまとめて1目にする玉編み。2目分の幅を占める。",
            enDescription = "Five hdc strokes bundled into one stitch; spans two columns.",
        )

    // endregion

    // region — crossed / decorative stitches (Phase 30.4) -------------------

    private val dcCrossed2 =
        SymbolDefinition(
            id = id("dc-crossed-2"),
            category = SymbolCategory.CROCHET,
            // Phase 30.4 addition — two dc stems crossing mid-cell to form
            // an X, each retaining its own top-bar + slash (dc=1 slash). JIS
            // L 0201 Table 2 shows the crossed family; Vogue / Bunka aran-
            // style crochet patterns render it this way.
            //
            // Coordinate guide (critical — per knitter advisory m2, do NOT
            // "correct" the top-bar x-ranges back to the pre-cross column):
            //   left  stem: (0.1, 0.9) → (0.7, 0.1)  [origin bottom-left,
            //                                         terminates top-right]
            //   right stem: (0.9, 0.9) → (0.3, 0.1)  [origin bottom-right,
            //                                         terminates top-left]
            // Stems cross at (0.5, 0.5). Each top-bar caps its stem's
            // *crossed* endpoint, not its origin column — left-stem bar at
            // x∈[0.6, 0.8], right-stem bar at x∈[0.2, 0.4], both at y=0.1.
            // Slashes mirror around x=0.5 at mid-stem (y≈0.5) per JIS dc
            // convention.
            pathData =
                "M 0.1 0.9 L 0.7 0.1 " +
                    "M 0.9 0.9 L 0.3 0.1 " +
                    "M 0.6 0.1 L 0.8 0.1 " +
                    "M 0.2 0.1 L 0.4 0.1 " +
                    "M 0.25 0.45 L 0.45 0.55 " +
                    "M 0.55 0.45 L 0.75 0.55",
            widthUnits = 2,
            jaLabel = "2目交差の長編み",
            enLabel = "Crossed 2-dc",
            jisReference = JIS_REF,
            cycName = "cross-dc",
            aliases = listOf("dc cross", "cross dc"),
            jaDescription = "2目の長編みを交差させる。記号は2本の長編みが中央でX字に交差。",
            enDescription = "Two dc stitches crossing mid-cell; each stem keeps its top-bar and slash.",
        )

    private val bullion =
        SymbolDefinition(
            id = id("bullion"),
            category = SymbolCategory.CROCHET,
            // Phase 30.4 addition — vertical stem with two alternating half-
            // loops, evoking the spring-coil silhouette of a bullion (wrapped)
            // stitch. JIS is silent on this glyph; JP publishers (毛糸だま
            // doily issues, Bunka lace books) draw a coil overlay. Two coils
            // beat three at 24dp legibility per knitter advisory m1 (three
            // coils would fall below the Phase 30.2 §3.5 density threshold).
            // Distinct from `tr` (T + 2 horizontal slashes) and `qtr` (T + 4
            // slashes) — the alternating-side half-loops read as a coil, not
            // as parallel slashes.
            pathData =
                "M 0.5 0.1 L 0.5 0.9 " +
                    "M 0.5 0.2 C 0.75 0.28 0.75 0.48 0.5 0.5 " +
                    "M 0.5 0.5 C 0.25 0.52 0.25 0.72 0.5 0.8",
            jaLabel = "バリオン編み",
            enLabel = "Bullion stitch",
            // JIS L 0201 Table 2 is silent on bullion; leave jisReference null
            // so it does not misrepresent authority.
            cycName = "bullion",
            aliases = listOf("bullion", "roll stitch"),
            jaDescription = "糸を何度も巻きつけて引き抜く背の高い編み目。記号は縦線＋交互の半円コイル。",
            enDescription = "Yarn wrapped multiple times before pulling through; rendered as stem with alternating coils.",
        )

    private val solomonKnot =
        SymbolDefinition(
            id = id("solomon-knot"),
            category = SymbolCategory.CROCHET,
            // Phase 30.4 addition — tall narrow open loop signaling an
            // elongated chain-knot (ラブノット). JIS is silent. Vogue 毛糸だま
            // shawl / stole issues and Bunka render as two vertical arc
            // curves (not a closed pill — that reads as a rotated `ch` at
            // 24dp per knitter advisory M1) joined by short horizontal cap
            // lines top and bottom.
            pathData =
                "M 0.45 0.08 C 0.35 0.3 0.35 0.7 0.45 0.92 " +
                    "M 0.55 0.08 C 0.65 0.3 0.65 0.7 0.55 0.92 " +
                    "M 0.4 0.08 L 0.6 0.08 " +
                    "M 0.4 0.92 L 0.6 0.92",
            jaLabel = "ラブノット",
            enLabel = "Solomon's knot",
            // JIS is silent on solomon-knot; leave jisReference null.
            cycName = "Solomon's knot",
            aliases = listOf("love knot", "true lover's knot"),
            jaDescription = "長い鎖の結び目。記号は縦に細長い開いたループ。",
            enDescription = "Elongated chain-knot stitch; rendered as a tall narrow open loop.",
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
            // Phase 30.2-fix widened the centre cross from `0.45–0.55` to
            // `0.40–0.60` so the cross is still legible at the 24dp gallery size.
            pathData =
                "M 0.5 0.1 " +
                    "C 0.72 0.1 0.9 0.28 0.9 0.5 " +
                    "C 0.9 0.72 0.72 0.9 0.5 0.9 " +
                    "C 0.28 0.9 0.1 0.72 0.1 0.5 " +
                    "C 0.1 0.28 0.28 0.1 0.5 0.1 Z " +
                    "M 0.4 0.5 L 0.6 0.5 " +
                    "M 0.5 0.4 L 0.5 0.6",
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
            // dc base shortened, plus a quadratic-curve C-wrap at the base
            // sweeping right and down to evoke the hook wrapping around the
            // front of the previous-row post. Phase 30.2-fix replaced the prior
            // L-shape (which read as an arrow rather than a wrap) with a single
            // quadratic curve per Knitter advisory §3.3 + §6 follow-up.
            pathData =
                "M 0.5 0.1 L 0.5 0.8 " +
                    "M 0.3 0.1 L 0.7 0.1 " +
                    "M 0.3 0.3 L 0.7 0.4 " +
                    "M 0.5 0.8 Q 0.85 0.8 0.85 0.95",
            jaLabel = "表引き上げ長編み",
            enLabel = "Front-post dc (fpdc)",
            jisReference = JIS_REF,
            cycName = "fpdc",
            jaDescription = "前段の目の柱を手前側から拾って編む。記号は長編み＋右向きに巻く鉤。",
            enDescription = "dc worked around the front of a post; rendered as dc with a right C-wrap.",
        )

    private val bpdc =
        SymbolDefinition(
            id = id("bpdc"),
            category = SymbolCategory.CROCHET,
            // Mirror of fpdc — the C-wrap sweeps left and down for the back of
            // the post. Phase 30.2-fix replaced the prior L-shape with a
            // quadratic curve.
            pathData =
                "M 0.5 0.1 L 0.5 0.8 " +
                    "M 0.3 0.1 L 0.7 0.1 " +
                    "M 0.3 0.3 L 0.7 0.4 " +
                    "M 0.5 0.8 Q 0.15 0.8 0.15 0.95",
            jaLabel = "裏引き上げ長編み",
            enLabel = "Back-post dc (bpdc)",
            jisReference = JIS_REF,
            cycName = "bpdc",
            jaDescription = "前段の目の柱を奥側から拾って編む。記号は長編み＋左向きに巻く鉤。",
            enDescription = "dc worked around the back of a post; rendered as dc with a left C-wrap.",
        )

    // endregion

    // region — loop-only markers --------------------------------------------

    private val scFl =
        SymbolDefinition(
            id = id("sc-fl"),
            category = SymbolCategory.CROCHET,
            // sc inclined cross compressed into the upper portion of the cell
            // so the front-loop arc has clearance below. Phase 30.2-fix kept
            // these in sync with the JIS `×` form chosen for `sc`; mismatched
            // bases (sc=× vs sc-fl=+) would read as different stitches.
            pathData =
                "M 0.3 0.2 L 0.7 0.7 " +
                    "M 0.7 0.2 L 0.3 0.7 " +
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
            // Mirror of sc-fl with an upward-opening arc indicating BLO. Phase
            // 30.2-fix synchronised the cross with the JIS `×` form on `sc`.
            pathData =
                "M 0.3 0.2 L 0.7 0.7 " +
                    "M 0.7 0.2 L 0.3 0.7 " +
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
            // Closed pentagon (point up) — JIS L 0201 Table 2 form for パプコーン
            // 編み. Phase 30.2-fix replaced the prior CYC radial-ray circle with
            // the JIS pentagon to align with the `jis.*` namespace authority
            // chosen in ADR-008. Vertices computed with the apex at (0.5, 0.15)
            // and the remaining four points spaced 72° around the centre.
            pathData =
                "M 0.5 0.15 " +
                    "L 0.79 0.36 " +
                    "L 0.68 0.7 " +
                    "L 0.32 0.7 " +
                    "L 0.21 0.36 Z",
            jaLabel = "パプコーン編み",
            enLabel = "Popcorn",
            jisReference = JIS_REF,
            cycName = "pc",
            jaDescription = "複数目を立ち上がりから1目に引き抜いて膨らませる。記号は閉じた五角形。",
            enDescription = "Multiple stitches pulled together; drawn as a closed pentagon (JIS form).",
        )

    private val puff =
        SymbolDefinition(
            id = id("puff"),
            category = SymbolCategory.CROCHET,
            // Tight bundle of three thin loops converging at a shared base
            // (0.5, 0.85) and capped by a short open horizontal top-bar at
            // y=0.20 that the three stem tips meet (loops "pulled to one
            // height" per JIS L 0201 Table 2). JP publishers (Vogue / Bunka
            // / Ondori) all follow suit. The converging base differentiates
            // this from `dc-cluster-3` (parallel stems), and the open top-bar
            // (no oval) differentiates it from clusters and popcorn. No
            // slashes = not a tall-stitch bundle. Added in Phase 30.3 per
            // Knitter §4 gap — no existing glyph combination can honestly
            // substitute for パフ編み.
            pathData =
                "M 0.5 0.85 L 0.35 0.2 " +
                    "M 0.5 0.85 L 0.5 0.2 " +
                    "M 0.5 0.85 L 0.65 0.2 " +
                    "M 0.25 0.2 L 0.75 0.2",
            jaLabel = "パフ編み",
            enLabel = "Puff stitch",
            jisReference = JIS_REF,
            cycName = "puff",
            jaDescription = "未完成の中長編み等を複数引き揃えた膨らみ。記号は縦線の束＋上部の横線。",
            enDescription = "Unfinished loops pulled together into a puff; vertical bundle with a single top bar.",
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
            // `picot` alias on the shortest family member per ADR-009 §4 so
            // dictionary search on the bare term still resolves.
            aliases = listOf("picot", "picot3", "3-ch picot"),
            jaDescription = "3目の鎖を輪にしたピコット。装飾に用いる。",
            enDescription = "Decorative 3-chain loop; rendered as a small loop over a stem.",
        )

    // Phase 30.4 — picot-N discrete family per ADR-009 §8 (geometry-varying,
    // unlabeled → discrete family, not parametric). Each member is a full
    // first-class catalog entry with its own pathData tuned for the loop
    // size. `picot-3` stays stable. `picot-6` goes widthUnits=2 per ADR-009
    // §8 clause (loop naturally overflows single cell).
    private val picot4 =
        SymbolDefinition(
            id = id("picot-4"),
            category = SymbolCategory.CROCHET,
            pathData =
                "M 0.5 0.6 L 0.5 0.9 " +
                    "M 0.25 0.6 C 0.25 0.2 0.75 0.2 0.75 0.6 Z",
            jaLabel = "4目のピコット",
            enLabel = "Picot (4-ch)",
            jisReference = JIS_REF,
            cycName = "picot-4",
            aliases = listOf("picot4", "4-ch picot"),
            jaDescription = "4目の鎖を輪にしたピコット。3目より一回り大きな装飾ループ。",
            enDescription = "Decorative 4-chain loop; one size larger than picot-3.",
        )

    private val picot5 =
        SymbolDefinition(
            id = id("picot-5"),
            category = SymbolCategory.CROCHET,
            pathData =
                "M 0.5 0.65 L 0.5 0.9 " +
                    "M 0.2 0.65 C 0.2 0.1 0.8 0.1 0.8 0.65 Z",
            jaLabel = "5目のピコット",
            enLabel = "Picot (5-ch)",
            jisReference = JIS_REF,
            cycName = "picot-5",
            aliases = listOf("picot5", "5-ch picot"),
            jaDescription = "5目の鎖を輪にしたピコット。ほぼセル全体を占めるループ。",
            enDescription = "Decorative 5-chain loop; near-full-cell loop.",
        )

    private val picot6 =
        SymbolDefinition(
            id = id("picot-6"),
            category = SymbolCategory.CROCHET,
            // widthUnits=2 per ADR-009 §8 — a 6-chain picot naturally reads
            // as an arch spanning two stitch columns on commercial edging
            // charts. Loop widened to x∈[0.05, 0.95] (near-full unit-square)
            // per knitter advisory m3 so the widthUnits=2 stretch renders a
            // true ~2-cell loop, visually distinct from picot-5 at 1 cell.
            pathData =
                "M 0.5 0.75 L 0.5 0.95 " +
                    "M 0.05 0.75 C 0.05 0.05 0.95 0.05 0.95 0.75 Z",
            widthUnits = 2,
            jaLabel = "6目のピコット",
            enLabel = "Picot (6-ch)",
            jisReference = JIS_REF,
            cycName = "picot-6",
            aliases = listOf("picot6", "6-ch picot"),
            jaDescription = "6目の鎖を輪にしたピコット。2目分の幅を占めるアーチ状ループ。",
            enDescription = "Decorative 6-chain loop; arch spanning two columns.",
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
            // decreases & edge
            reverseSc,
            sc2tog,
            sc3tog,
            dc2tog,
            dc3tog,
            // clusters
            dcCluster3,
            hdcCluster3,
            dcCluster5,
            hdcCluster5,
            // crossed / decorative (Phase 30.4)
            dcCrossed2,
            bullion,
            solomonKnot,
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
            puff,
            picot3,
            picot4,
            picot5,
            picot6,
            turningCh,
            chSpace,
        )
}
