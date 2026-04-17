# Phase 30.1 — Knitter Advisory: `jis.knit.*` Catalogue Visual Review

**Date:** 2026-04-18
**Reviewer:** Knitter agent (see [`.claude/agents/knitter.md`](../../../.claude/agents/knitter.md))
**Source under review:** [`shared/src/commonMain/kotlin/io/github/b150005/knitnote/domain/symbol/catalog/KnitSymbols.kt`](../../../shared/src/commonMain/kotlin/io/github/b150005/knitnote/domain/symbol/catalog/KnitSymbols.kt) — 35 glyphs, unit square `viewBox 0 0 1 1` (y-down).
**Viewer:** Phase 31 Compose `ChartViewerScreen` + SwiftUI `StructuredChartViewerScreen` + Phase 30.1 `SymbolGalleryScreen` (dictionary UI).

This document is the written review checkpoint scheduled in Phase 30.1 of the roadmap (per ADR-007). It captures craft-correctness findings against JIS L 0201-1995 + CYC + major JP publisher conventions so the human user and Knitter can agree on (a) geometry fixes for the existing catalog and (b) the next catalog category to ship.

## 1. Per-glyph assessment

Frequency key: **H** high (most patterns), **M** medium (common but optional), **L** low (occasional), **N** niche (specialty).
Cross-reference stability: **Y** = JIS + CYC + a major JP publisher (Vogue / Bunka) agree; **N** = known divergence; **U** = unknown / standard-silent.
Path assessment is judged against the JIS description only — not stylistic taste.

| id | jaLabel | enLabel | Freq | X-ref | Path | Notes |
|---|---|---|---|---|---|---|
| `jis.knit.k` | 表目 | Knit | H | Y | reasonable | Correct vertical stroke. |
| `jis.knit.p` | 裏目 | Purl | H | Y | **suspect** | Purl mark should be a **short centered dash** (≈0.3–0.7), not edge-to-edge. Current 0.1→0.9 reads as a bind-off bar. |
| `jis.knit.yo` | 掛け目 | Yarn over | H | Y | reasonable | Circle r≈0.35 matches JIS/CYC. |
| `jis.knit.sl-k` | すべり目（表） | Slip knitwise | M | U | reasonable | JIS prescribes `V` for slip; this is a local composition. See §5. |
| `jis.knit.sl-p` | すべり目（裏） | Slip purlwise | M | U | reasonable | Same composability caveat as `sl-k`. |
| `jis.knit.float-front` | 浮き目（糸を手前） | Slip with yarn in front | L | N | suspect | JIS uses `V` with arrow/hook for yarn position. |
| `jis.knit.twist-r` | ねじり目（右） | Right twisted knit (k-tbl) | M | Y | reasonable | Small diagonal at top. OK. |
| `jis.knit.twist-l` | ねじり目（左） | Left twisted knit | M | Y | reasonable | Mirror of `twist-r`. OK. |
| `jis.knit.twist-p-r` | 裏ねじり目（右） | Right twisted purl | L | U | suspect | Inherits edge-to-edge purl bar. |
| `jis.knit.k2tog-r` | 右上2目一度 | Right-leaning k2tog (SSK) | H | **N** | **suspect** | Directional glyph should be stem + single left-slanting slash; current renders symmetric inverted-V = center-decrease shape. |
| `jis.knit.k2tog-l` | 左上2目一度 | Left-leaning k2tog | H | **N** | **suspect** | Mirror problem of `k2tog-r`; visually indistinguishable from it. |
| `jis.knit.p2tog-r` | 右上2目一度（裏） | Right-leaning p2tog | M | U | suspect | Same geometric ambiguity + edge-to-edge purl bar. |
| `jis.knit.p2tog-l` | 左上2目一度（裏） | Left-leaning p2tog | M | U | suspect | Same as above. |
| `jis.knit.k3tog-c` | 中上3目一度 | Centred double decrease (CDD) | H | Y | reasonable | Three strokes converging centrally is standard CDD. |
| `jis.knit.k3tog-r` | 右上3目一度 | Right-leaning sssk | M | N | suspect | Directional tick is too short; base still symmetric. |
| `jis.knit.k3tog-l` | 左上3目一度 | Left-leaning k3tog | M | N | suspect | Mirror of `k3tog-r`. |
| `jis.knit.m1-r` | 右増目 | M1R | H | Y | reasonable | Vertical + small right foot. OK. |
| `jis.knit.m1-l` | 左増目 | M1L | H | Y | reasonable | Mirror. OK. |
| `jis.knit.kfb` | ねじり増し目 | Knit front and back | H | **N** | suspect | **Label/shape mismatch.** `ねじり増し目` is twisted-M1 in Vogue JP, not `kfb`. |
| `jis.knit.cast-on` | 編出し増目 | Cast-on increase | H | Y | reasonable | Stem + base bar + count slot. OK. |
| `jis.knit.bind-off` | 伏せ目 | Bind-off | H | Y | reasonable | Double horizontal bar + count slot. OK. |
| `jis.knit.cable-1x1-r` | 1目交差 右上 | 1/1 RC | H | Y | **suspect** | Pure X — over-stroke and under-stroke are both unbroken, so right-over vs left-over is visually identical. |
| `jis.knit.cable-1x1-l` | 1目交差 左上 | 1/1 LC | H | Y | suspect | Same problem as `cable-1x1-r`. |
| `jis.knit.cable-2x2-r` | 2目交差 右上 | 2/2 RC | H | Y | suspect | Same top-over-bottom ambiguity. |
| `jis.knit.cable-2x2-l` | 2目交差 左上 | 2/2 LC | H | Y | suspect | Same. |
| `jis.knit.cable-3x3-r` | 3目交差 右上 | 3/3 RC | M | Y | suspect | Same. |
| `jis.knit.cable-3x3-l` | 3目交差 左上 | 3/3 LC | M | Y | suspect | Same. |
| `jis.knit.cable-1x1-r-p` | 1目交差 右上（下が裏） | 1/1 RPC | M | Y | suspect | Same X-ambiguity + purl tick. |
| `jis.knit.cable-1x1-l-p` | 1目交差 左上（下が裏） | 1/1 LPC | M | Y | suspect | Same. |
| `jis.knit.bobble` | 玉編み | Bobble | M | Y | reasonable | Lens shape is standard. |
| `jis.knit.w-and-t` | 引き返し編み | Wrap and turn | M | N | reasonable | Publisher-variable glyph; acceptable. |
| `jis.knit.k-below` | 引き上げ編み（表） | Knit 1 below | L | U | reasonable | OK. |
| `jis.knit.p-below` | 引き上げ編み（裏） | Purl 1 below | L | U | suspect | Inherits purl bar issue. |
| `jis.knit.psso` | かぶせ目 | psso | L | N | reasonable | Dotted-bar is a local convention; JIS prescribes overlay arc. |
| `jis.knit.no-stitch` | なし（目なし） | No stitch | H | Y | reasonable | Diagonal cross is a valid Vogue / Interweave convention. |

Summary: **~19 glyphs reasonable as-is, ~16 glyphs with at least one craft-correctness concern.**

## 2. Geometry concerns — follow-up bug candidates

Only clear craft-wrong issues, not stylistic preferences:

1. **Purl bar width (`p`, `twist-p-r`, `p2tog-r`, `p2tog-l`, `p-below`).** Narrow from 0.1→0.9 to ≈0.3→0.7 to match JIS + Vogue + Bunka convention. Current width reads as a bind-off bar at row density.
2. **Cable over/under direction is not expressed (`cable-1x1-r/l`, `cable-2x2-r/l`, `cable-3x3-r/l`, `cable-1x1-r/l-p`).** All six cables draw both diagonals as unbroken strokes (pure X). JIS renders the over stroke as continuous and the under stroke as broken or offset where it passes behind. Without that, right-over vs left-over is visually identical — the most impactful fix in the catalog.
3. **SSK / k2tog direction glyphs (`k2tog-r/l`, `p2tog-r/l`, `k3tog-r/l`).** The base path currently draws an inverted V (two diagonals meeting at (0.5, 0.2)) for both right-leaning and left-leaning decreases. JIS uses a single diagonal above a stem: `\|` for right-leaning (SSK), `/|` for left-leaning (k2tog). Redraw as stem + single slash with the correct lean.
4. **`kfb` label / shape mismatch.** JA label `ねじり増し目` is twisted-M1 in JIS / Vogue, not `kfb`. Either rename the symbol or redraw the glyph — needs user decision (§5).
5. **`k3tog-r` / `k3tog-l` tick too faint.** Directional tick at (0.2, 0.7)→(0.5, 0.35) is geometrically correct but invisible <24px. If keeping symmetric-base-plus-tick, lengthen or thicken the tick.

All other glyphs are reasonable renderings of the JIS description.

## 3. Parameterization audit

Three glyphs declare `parameterSlots`:

| id | slot key | (x, y) | defaultValue | Assessment |
|---|---|---|---|---|
| `jis.knit.cast-on` | `count` | (0.5, 0.25) | `"n"` | Correct — JIS prints the count above the stem-and-base. |
| `jis.knit.bind-off` | `count` | (0.5, 0.65) | `"n"` | Correct — below the double bar. |
| `jis.knit.w-and-t` | `rowLabel` | (0.5, 0.40) | `null` | Correct — pattern-specific; no default is right. |

No parameter slot bugs found. **Gap:** cables could accept an explicit stitch-count parameter (commercial JP patterns often show "2/2" on top of `cable-2x2-*`); not blocking for v1, flag for Phase 32 editor scope.

## 4. Next-catalog-category recommendation

Scored 1 (weakest) to 5 (strongest):

| Factor | CROCHET | AFGHAN | MACHINE |
|---|---|---|---|
| Frequency in commercial patterns | 5 | 2 | 2 |
| Coverage gap vs. JIS L 0201 + CYC | 5 | 3 | 3 |
| User-segment unlock | 5 | 1 | 2 |
| Implementation cost (lower = better) | 3 | 2 | 4 |
| **Total** | **18** | **8** | **11** |

**Recommendation: CROCHET.** JIS L 0201 Table 2 already specifies crochet symbols, CYC has a full crochet set, JP commercial pattern volume is the largest audience we can unlock in one step, and implementation is moderate (~25–35 core glyphs, mostly single-path, few parameters). Afghan is a later add-on built on shared knit + crochet primitives; machine knitting is a smaller installed base with domain-specific notation.

Codified into the roadmap as **Phase 30.2**.

## 5. Open questions for the human user

These require a user call — Knitter flags but does not resolve:

1. **Purl bar width** — narrow to centered dash (Knitter recommends) vs keep edge-to-edge?
2. **Cable over/under** — adopt JIS-style broken under-stroke (Knitter recommends) or simpler tinted-fill approach for v1?
3. **Decrease directional glyph** — switch all right/left-leaning decreases to JIS stem + single slash (Knitter recommends)?
4. **`kfb` vs `ねじり増し目`** — which is the label error: the ID (`kfb`) or the JA label (`ねじり増し目`)? Two possible fixes, need user call.
5. **CYC English aliases** — keep descriptive `Right-leaning k2tog (SSK)` or abbreviate to `SSK`?
6. **Slip-stitch convention** — keep current composable approximation or switch to JIS / Vogue / CYC `V`?
7. **psso rendering** — keep dotted bar or adopt Vogue overhead arc?
8. **Next category confirmation** — proceed with CROCHET as Phase 30.2? (Pre-approved in this session.)

## 6. Follow-up work

- **Phase 30.1-fix** (new): resolve items 1–4 and 5 in §2 as a geometry-only PR on `KnitSymbols.kt`. No schema change. Scoped after the user answers §5 questions 1–4.
- **Phase 30.2** (new): Crochet catalog — `jis.crochet.*` namespace, ~25–35 glyphs covering ch / sc / hdc / dc / tr / sl-st / cluster variants. Follows the same path-data + `SymbolDefinition` pattern as Phase 30.
- **Phase 32 scope addition**: evaluate whether cables should carry a `stitches-over` parameter slot in the editor MVP.
