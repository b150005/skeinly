# Phase 30.2 — Knitter Advisory: `jis.crochet.*` Catalogue Visual Review

**Date:** 2026-04-18
**Reviewer:** Knitter agent (see [`.claude/agents/knitter.md`](../../../.claude/agents/knitter.md))
**Source under review:** [`shared/src/commonMain/kotlin/io/github/b150005/knitnote/domain/symbol/catalog/CrochetSymbols.kt`](../../../shared/src/commonMain/kotlin/io/github/b150005/knitnote/domain/symbol/catalog/CrochetSymbols.kt) — 25 glyphs, unit square `viewBox 0 0 1 1` (y-down).
**Viewer:** Phase 31 Compose `ChartViewerScreen` + SwiftUI `StructuredChartViewerScreen` + Phase 30.1 `SymbolGalleryScreen`.
**References:** JIS L 0201-1995 Table 2 (かぎ針編目), CYC crochet chart, Nihon Vogue / Bunka JP publisher conventions.

This is the companion review to Phase 30.1, scoped to the newly-added crochet catalog. Per ADR-008 §symbol-sources, JIS is the reference when JIS + CYC agree; CYC and Vogue/Bunka are tie-breakers when JIS is silent. The review flags authenticity, label fidelity, and 24dp render concerns — no code patches.

## 1. Summary

**Hold for Phase 30.2-fix before merge** — geometry is acceptable for most glyphs, but two craft-correctness issues will read wrong in commercial-pattern contexts:

- **`sl-st`** is stroked, not filled (every major JIS/CYC/JP publisher uses a solid dot). Renderer-side, but the symbol definition forces the issue.
- **`dc2tog` / `dc3tog`** carry a short top-bar that is visually indistinguishable from the cluster's closed oval at 24dp — the decrease vs. cluster disambiguation is the single most important visual contrast in the crochet symbol set.

Everything else is a minor geometry / cross-ref note or a deferrable label polish. Coverage at 25 glyphs hits the Phase 30.2 target (25–35) but is missing a small number of high-frequency glyphs that I'd push into a Phase 30.3.

## 2. Per-glyph findings

Severity key: **OK** no action, **minor** polish, **major** craft-correctness concern worth a fix, **BLOCK** must not ship as-is.

| id | Finding | Severity |
|---|---|---|
| `jis.crochet.ch` | Horizontal oval, aspect ratio and placement match JIS + CYC. | OK |
| `jis.crochet.sl-st` | JIS L 0201 Table 2 + CYC + every JP publisher (Vogue / Bunka / Ondori) render sl-st as a **filled black dot/oval**. Current `pathData` is an outlined (stroked) ellipse. Our renderer strokes paths only, so the glyph reads as a tiny donut rather than a solid point. This is the dominant cross-cutting concern for Phase 30.2 — see §3. | **major** |
| `jis.crochet.sc` | Centered `+`. JIS uses an **inclined cross (×)** and many JP publishers follow that; CYC publishes both. Either is defensible; current `+` matches CYC EN conventions. Non-blocking but flagged as a JIS/CYC tie-breaker in §5. | minor |
| `jis.crochet.hdc` | T-shape (stem + top bar, no slash). Matches JIS. | OK |
| `jis.crochet.dc` | T + single slash. Matches JIS. Slash runs slightly downhill (`0.3 0.3 → 0.7 0.4`), consistent with JP publisher style. | OK |
| `jis.crochet.tr` | T + 2 slashes. Matches JIS. Slash spacing (`0.25→0.35`, `0.45→0.55`) is readable at 24dp. | OK |
| `jis.crochet.dtr` | T + 3 slashes. Matches JIS (三つ巻長編み). Slashes at `0.25 / 0.40 / 0.55` with ~0.15 vertical spacing — tight but readable. | OK |
| `jis.crochet.qtr` | T + 4 slashes. Matches JIS (四つ巻長編み). **Label mismatch:** `enLabel = "Triple treble crochet (trtr)"` and `cycName = "trtr"` — but the id is `qtr` and the JA is `四つ巻長編み` (= US "quadruple treble" in CYC, or "quad tr"; UK "triple treble"). Pick one convention and make the id + labels agree. Geometry is fine. | minor |
| `jis.crochet.sc2tog` | Two angled strokes converging at apex `(0.5, 0.25)` with a **single crossbar spanning both arms at y=0.55**. JIS convention for sc-dec puts the crossbar at the **apex** (preserving the sc-cross signature near the top), not halfway down — the current rendering reads more like a CYC `\/` with an unrelated center-bar than a JIS 細編み2目一度. Not wrong enough to block, but non-standard. | minor |
| `jis.crochet.sc3tog` | Same comment as `sc2tog`. The three converging strokes with a single mid-height bar is a defensible simplification; JIS is stricter about the crossbar staying near the apex. | minor |
| `jis.crochet.dc2tog` | Angled dc strokes joined by a short top-bar at `y=0.18`. Two concerns: (a) the top-bar reads at 24dp as a tiny closed shape and is **too close visually to the cluster's closed-oval top** — `dc2tog` and `dc-cluster-3` can be confused; (b) JIS renders the top-bar as a **horizontal open line** — longer and clearly *not* enclosed. Widen the top-bar and/or differentiate more aggressively from the cluster bundle. | **major** |
| `jis.crochet.dc3tog` | Same top-bar confusion as `dc2tog`. Centre stroke is vertical with outer pair angled — geometrically correct. The disambiguation against `dc-cluster-3` (same number of strokes) is the blocking concern. | **major** |
| `jis.crochet.dc-cluster-3` | Three vertical dc strokes bundled under a closed oval. Matches the JIS 玉編み convention. Oval spans `(0.15–0.85) × (0.03–0.32)` — good legibility at 24dp. Slashes on each stroke confirm dc height. | OK |
| `jis.crochet.dc-cluster-5` | Five strokes + wider oval + `widthUnits=2`. Matches convention. Verify that `widthUnits=2` renders across two cells in both platforms (Phase 31 renderers). | OK |
| `jis.crochet.inc-sc` | V-spread of two sc glyphs from shared base. CYC uses two separated sc crosses linked at the base with a short bracket; JIS is silent on the composite glyph. Current V-spread is **clearly readable as an increase** and is the most common Western chart rendering. | OK |
| `jis.crochet.inc-dc` | Same as `inc-sc` — two dc glyphs diverging with each retaining its crossbar + slash. Legible. Two separate top-bars (`0.15→0.35` and `0.65→0.85`) is the correct signal. | OK |
| `jis.crochet.magic-ring` | Large open ring with a small `+` at centre. Matches the unstandardised JP publisher convention. **Geometry concern:** the centre cross spans only `0.45–0.55` on each axis — at 24dp that's ~2.4px across and will disappear. Widen to `0.40–0.60` or `0.38–0.62`. | minor |
| `jis.crochet.fpdc` | dc stem shortened to `0.1→0.8` plus a right-angled L at `(0.5, 0.8) → (0.75, 0.8) → (0.75, 0.95)`. The L-shape reads as an arrow, not a post-wrap. JIS + JP publisher convention for 引き上げ is a **curved C or J-hook that wraps around the post**, not a sharp L. Direction (right = front) is a publisher variant — CYC and Vogue both accept it. Consider replacing the L with a quadratic curve. | minor |
| `jis.crochet.bpdc` | Same L-shape concern as `fpdc`, mirrored left. | minor |
| `jis.crochet.sc-fl` | sc cross with downward-opening arc below indicating front-loop-only. Matches the JP publisher convention (where an **under-arc** = FLO). Legibility OK. | OK |
| `jis.crochet.sc-bl` | sc cross with upward-opening arc below indicating back-loop-only. Mirror of `sc-fl`. The arc at `0.75–0.92` compresses against the bottom of the cell — at 24dp the arc height is ~3px. Borderline but acceptable. | minor |
| `jis.crochet.popcorn` | Closed circle + 4 short radial bursts. This is a **CYC-style** popcorn rendering. JIS L 0201 Table 2 uses a **pentagon/oval with a closed top** or a **bundle of 5 dc with an oval then extra loop**; the radial-ray motif is not JIS-standard. Defensible, but flag for §5. The small radial stubs (`0.1→0.2`, each 0.1 long) will render as ~2.4px dashes at 24dp. | minor |
| `jis.crochet.picot-3` | Stem + single loop at top. JIS typically uses **three linked chain ovals arranged in a triangular cap** above a stitch. A single loop is an abstract simplification. Readable, but doesn't echo the "3-ch" semantic. | minor |
| `jis.crochet.turning-ch` | Three stacked chain-ovals. Matches JP publisher convention. **Semantic gap:** turning-ch *count* varies by stitch height (hdc=2, dc=3, tr=4, dtr=5, trtr=6). Hardcoding 3 ovals ties this glyph to a dc turning-ch. Consider converting to a **parameterised turning-ch** with a `count` slot, or shipping distinct `turning-ch-N` variants. See §5. | minor |
| `jis.crochet.ch-space` | Arc with `count` parameter slot labelled `"n"` — matches JIS / JP publisher "ch-N" bracket. Parameter placement `(0.5, 0.75)` puts the count below the arc's opening, which is correct. | OK |

Totals: **2 major**, **13 minor**, **10 OK**, **0 BLOCK**, **0 OK-with-reservation**.

## 3. Cross-cutting concerns

### 3.1 `sl-st` fill vs. stroke (renderer-forced issue)

JIS L 0201 Table 2, CYC, Vogue Japan, and Bunka all render 引き抜き編み as a **solid filled dot/oval** (roughly 0.3–0.5 of cell width). Our renderer strokes paths; there is no current way to express "fill this glyph solid". This means any attempt to ship `sl-st` as a stroked ellipse — no matter how tight — will read as an outlined donut, which JP knitters will not recognize as sl-st.

Two resolution paths, both Phase 30.2-fix scope:

1. **Renderer-side**: add a `fill: Boolean` (or `SymbolStyle` enum) field to `SymbolDefinition` and have the Compose + SwiftUI renderers honor it. Small scope, correct long-term. Also unlocks future JIS glyphs that require fill (the filled-triangle sl-st variant, filled-diamond bobble variants, etc.).
2. **Path trick**: double-stroke the ellipse with a tighter inner path to approximate fill. Brittle, bad at larger zooms, and sets a bad precedent.

Strong recommendation: option 1. This is a cross-cutting catalog concern that will recur.

### 3.2 Decrease vs. cluster disambiguation

`dc2tog` / `dc3tog` (decreases) vs. `dc-cluster-3` / `dc-cluster-5` (clusters) are the most visually similar pairs in the set, and at 24dp with a 2px stroke the difference between:

- **dec**: short horizontal open line at top (`M 0.4 0.18 L 0.6 0.18`)
- **cluster**: closed oval at top (cubic-bezier loop)

is approximately **one pixel of positive/negative space**. JIS draws the dec top-bar as a **long open horizontal line** that clearly extends past the outermost strokes (so the top is visibly "open"), while the cluster oval is **rounded and unambiguous**. Recommend lengthening the dc2tog/dc3tog top-bar and optionally making it non-horizontal (slight inward arc) so the "open" signal survives at low zoom.

### 3.3 Post-stitch hook direction is a publisher variant

`fpdc` hooks right / `bpdc` hooks left is one of **two** accepted conventions (CYC and some US publishers). Nihon Vogue uses the **opposite** convention (front = hook toward the reader = curves *down* at the base; back = curves *up*). JIS is silent. Either is defensible, but pick one and document in `jaDescription` so JP users aren't surprised. Flagged in §5.

### 3.4 Hardcoded turning-ch count

`turning-ch` ships 3 stacked chain-ovals, which implicitly binds it to a dc row. In commercial JP crochet patterns, turning-ch ovals count match the first-stitch height literally (2 for hdc, 3 for dc, 4 for tr, 5 for dtr, 6 for trtr). Three options:

- Ship per-stitch turning-ch variants (`turning-ch-2`, `turning-ch-3`, ...): mechanical, minimal code.
- Parameterise `turning-ch` with a `count` slot and render ovals dynamically: requires renderer changes, bigger scope.
- Ship `turning-ch` as a stylised "turning-ch bundle" glyph without a literal count: cheapest, least informative.

Recommend the first for Phase 30.3 since it matches existing catalog conventions and needs no renderer work.

### 3.5 Stroke density at 24dp

`qtr` packs 4 slashes into the `y=0.22 – 0.70` band (0.12 spacing per slash). At 24dp that's ~2.9px between slash midlines, minus ~2px stroke width = ~1px negative space. Zooming out or on low-DPI it will blur to a solid block. `dtr` (3 slashes) is borderline; `tr` (2) is safe. Recommend stroke-width taper or minimum-cell-size guard in the renderer — not a symbol-definition fix.

## 4. Coverage gap assessment

25 glyphs hits the Phase 30.2 target of 25–35. Coverage is biased toward **basic stitches and structural decreases**, which is the right priority for v1. The following gaps would block a commercial-pattern publisher from typesetting a real Japanese crochet pattern without fallbacks:

| Missing glyph | Frequency | Why it matters | Recommended phase |
|---|---|---|---|
| **Reverse sc (逆細編み / crab stitch)** | High (edgings, almost every finished garment) | Used on ~every crocheted cardigan / blanket edge. Unique left-to-right direction. | 30.3 — top priority |
| **Puff stitch (パフステッチ)** | Medium-high (amigurumi, lace, baby wear) | Distinct from popcorn and cluster in JIS; separate glyph. | 30.3 |
| **hdc cluster (中長編みN目の玉編み / bob-hdc)** | Medium (lace, throws) | Same role as `dc-cluster-3` but with hdc strokes. Common in shell patterns. | 30.3 |
| Extended sc (exsc) / long sc | Medium (amigurumi) | CYC-standard; JIS silent. | 30.3 or defer |
| Foundation sc/dc (fsc/fdc) | Medium (growing) | US pattern convention; chain-less starts. | 30.4 or defer |
| Spike stitch (long loop variants) | Low-medium | Colour-work crochet. | defer |
| sl st for joining rounds (as a distinct glyph, or rely on `sl-st`) | High | If `sl-st` is reused, no extra work; if a round-join variant is needed, add. | 30.3 if distinct |
| Cluster-dec variants (cl3tog etc.) | Low | Infrequent outside lace. | defer |

**Top 3 for Phase 30.3:** reverse-sc, puff stitch, hdc-cluster. These unlock roughly 90% of JP commercial crochet-pattern typesetting beyond what 30.2 already ships.

## 5. Open questions for the human user

These require a user call — Knitter flags but does not resolve.

1. **`sl-st` fill — add `fill: Boolean` (or `SymbolStyle`) to `SymbolDefinition` and teach both renderers to honour it, or ship a path-trick approximation for v1?** Recommend option 1 (small, future-proof; the filled-triangle sl-st variant and several knit bobble glyphs will want this too).
   - **Team (Phase 30.2-fix):** Add `fill: Boolean = false` to `SymbolDefinition`. `SymbolDrawing.drawSymbolPath` (Compose) selects `Fill` vs. `Stroke`; the SwiftUI `StructuredChartViewerScreen` and `SymbolGalleryScreen` switch between `context.fill` and `context.stroke`. `jis.crochet.sl-st` is the only glyph flipped to `fill = true` in this PR; future filled triangles / bobbles can opt in with the same field.
2. **`qtr` naming — US "quadruple treble" (matches id `qtr`, matches JA `四つ巻長編み`) or UK "triple treble" (matches current `enLabel` + `cycName = "trtr"`)?** Current state is internally inconsistent. Recommend US convention since the id is already `qtr` and JIS + Nihon Vogue both document 四つ巻 = quad tr.
   - **Resolved in Phase 30.2 (inline):** Adopted US convention on code-reviewer recommendation — `enLabel` → `"Quadruple treble crochet (qtr)"`, `cycName` → `"qtr"`, and `aliases = listOf("trtr", "triple treble")` so UK notation still resolves to the same glyph. Geometry unchanged.
3. **`sc` crossbar — centered `+` (CYC EN + some JP publishers) or inclined `×` (JIS L 0201 + most JP publishers)?** Not a craft-correctness issue; a JA-audience authenticity call. If v1 primary audience is JP, `×` is slightly more authentic. Deferrable.
   - **Team (Phase 30.2-fix):** Switch to JIS `×`. Per ADR-008 §6 + §Phase 30.5 addendum, the `jis.*` namespace defers to JIS where JIS and CYC disagree, and JP publishers (Vogue / Bunka) follow the inclined cross. `pathData` updated to `M 0.3 0.3 L 0.7 0.7 M 0.7 0.3 L 0.3 0.7`.
4. **`dc2tog` / `dc3tog` top-bar vs. cluster oval — widen the dec top-bar and extend it past the outermost strokes (Knitter recommends) or rely on zoom levels to disambiguate?** Recommend the fix — this is the dominant legibility bug in the set.
   - **Team (Phase 30.2-fix):** Widen. `dc2tog` top-bar `0.4→0.6` → `0.15→0.85`; `dc3tog` top-bar `0.4→0.6` → `0.10→0.90`. The bar now extends past the outermost stroke endpoints and reads as an open horizontal line at 24dp, distinct from the cluster's closed oval.
5. **sc-dec crossbar height — keep the current mid-cell bar at `y=0.55` or move it to the apex (`y≈0.25`) per JIS?** Mid-cell reads as a CYC approximation; apex-bar is more authentic. Minor either way.
   - **Team (Phase 30.2-fix):** Move toward apex per JIS. `sc2tog` crossbar `y=0.55` → `y=0.32` (just below the `y=0.25` apex); `sc3tog` crossbar `y=0.55` → `y=0.28`. The bar now sits inside the converging arms and preserves the JIS sc-cross signature near the top.
6. **Post-stitch hook direction — keep CYC convention (front = right, back = left) or switch to Nihon Vogue (front = hook curves down, back = hook curves up)?** Not a craft bug; pick one and document.
   - **Team (Phase 30.2-fix):** Keep CYC direction (front = right, back = left) since JIS is silent on direction and both conventions are accepted. Separately, replace the right-angle L-shape with a quadratic-curve C-wrap so the glyph reads as a hook wrapping the post rather than an arrow. `fpdc` base becomes `M 0.5 0.8 Q 0.85 0.8 0.85 0.95`; `bpdc` mirrors with `Q 0.15 0.8 0.15 0.95`.
7. **Popcorn rendering — keep CYC radial-ray circle or switch to JIS pentagon / bundle-with-cap?** If JP audience is primary, JIS form is more authentic. Minor.
   - **Team (Phase 30.2-fix):** Switch to JIS pentagon (point up). Same ADR-008 namespace-authority rationale as Q3. Vertices at `(0.5, 0.15)`, `(0.79, 0.36)`, `(0.68, 0.7)`, `(0.32, 0.7)`, `(0.21, 0.36)` — a closed pentagon stroked, not filled. The pentagon sits inside the cell and is unambiguous against the cluster oval (which spans the cell width and lives at the top of its glyph).
8. **`turning-ch` count — ship per-stitch variants (`turning-ch-2`, `-3`, `-4`, `-5`, `-6`) in Phase 30.3, or parameterise with a `count` slot now?** Per-stitch variants match existing catalog conventions (e.g., `dc-cluster-3` vs. `dc-cluster-5`) and need no renderer changes; parameterisation is cleaner long-term but is bigger scope.
   - **Team (Phase 30.2-fix):** Defer to Phase 30.3 with per-stitch variants. Matches the `dc-cluster-3 / -5` precedent, needs no renderer touch, and avoids cementing a parameter-slot contract before the editor (Phase 32) has shaped its UX. The current `turning-ch` glyph (3 ovals = dc default) stays in place as a stop-gap.
9. **`inc-sc` / `inc-dc` geometry — keep V-spread (current), or use JIS "two separate glyphs joined by a base bracket" form?** V-spread is Western; bracket is JIS. Minor authenticity call.
   - **Team (Phase 30.2-fix):** Keep V-spread. JIS is silent on the composite glyph, the V-spread is unambiguously legible as an increase, and matches the dominant Western chart rendering. Bracket form can be re-evaluated if/when JP-publisher feedback signals confusion.
10. **Phase 30.3 scope — confirm reverse-sc, puff, hdc-cluster as the top-3 additions? Or prioritise foundation stitches / extended sc first?** Recommend the §4 top-3.
    - **Team (Phase 30.2-fix):** Confirmed: reverse-sc, puff, hdc-cluster as Phase 30.3 top-3. Foundation stitches and extended sc move to Phase 30.4 / defer per §4 frequency table.

## 6. Follow-up work

- **Phase 30.2-fix** (new, geometry + renderer): Required before merge if we want v1-authentic crochet rendering.
  1. Add `fill: Boolean` (default `false`) to `SymbolDefinition`. Teach `ChartViewerScreen` (Compose `drawPath` with `Fill` style) and `StructuredChartViewerScreen` (SwiftUI `Path.fill`) to honour it. Set `fill = true` on `jis.crochet.sl-st`.
  2. Widen `dc2tog` / `dc3tog` top-bar (extend past outermost strokes, consider slight non-horizontal arc) to disambiguate from cluster oval.
  3. Reconcile `qtr` label/cycName with the id (`qtr` + US "quadruple treble" recommended).
  4. Widen `magic-ring` centre cross from `0.45–0.55` to `0.40–0.60`.
  5. (Optional, low risk) Redraw `fpdc` / `bpdc` base L-shape as a quadratic curve C-wrap for more authentic post-wrap signal.

- **Phase 30.3** (new): Crochet catalog follow-up — add reverse-sc, puff stitch, hdc-cluster (top-3), then foundation stitches / extended sc if time. Also the turning-ch-N family (pick per §5 Q8). Expected ~6–10 new glyphs.

- **Phase 32 scope addition**: Editor UI should let authors pick the correct turning-ch variant or supply the `count` parameter at chart-placement time.

- **ADR-008 addendum**: Record the Phase 30.2 fill-vs-stroke decision (expected: add `fill` to `SymbolDefinition`) so the pattern is documented for all future catalogs (jis.embroidery, jis.tatting, user.*).
