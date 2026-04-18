# Phase 30.3 — Knitter Advisory: `jis.crochet.*` Top-3 Coverage Additions

**Date:** 2026-04-18
**Reviewer:** Knitter agent (see [`.claude/agents/knitter.md`](../../../.claude/agents/knitter.md))
**Source under review:** [`shared/src/commonMain/kotlin/io/github/b150005/knitnote/domain/symbol/catalog/CrochetSymbols.kt`](../../../shared/src/commonMain/kotlin/io/github/b150005/knitnote/domain/symbol/catalog/CrochetSymbols.kt) — now 28 glyphs.
**References:** JIS L 0201-1995 Table 2 (かぎ針編目), CYC crochet chart, Nihon Vogue / Bunka JP publisher conventions.

This is the companion review to Phase 30.2-fix, scoped to three glyphs added in Phase 30.3 to close the commercial-JP coverage gap flagged in [`phase-30.2.md §4`](./phase-30.2.md#4-coverage). Adding these three brings `jis.crochet.*` to ~90% of commonly-seen JP commercial crochet pattern surface area, which was the target the Knitter set for "Phase 32 editor can ship on a catalog real JP knitters recognise".

The three glyphs were drafted by the Knitter agent, implemented verbatim per the pre-implementation advisory, then reviewed post-implementation. Two cosmetic nits (reverse-sc chevron form, puff top-bar width) were applied in-PR; one (hdc-cluster-3 stem spacing) is deferred to Phase 30.4 per the Knitter's "ship now, tweak in 30.4" guidance.

## 1. Verdict

| Glyph | Verdict |
|---|---|
| `jis.crochet.reverse-sc` | **ship-with-followup** — arrow-head vs. filled-head flagged for user-side preference review; the chevron form is defensible and renders cleanly |
| `jis.crochet.puff` | **ship** — matches JIS L 0201 + Vogue canonical form |
| `jis.crochet.hdc-cluster-3` | **ship** — mirrors `dc-cluster-3` with slashes omitted per JIS signal for hdc-height |

No blockers. Catalog is safe to land at 28 glyphs.

## 2. Geometry check (24dp legibility vs. existing glyphs)

- **`reverse-sc`**: compressed `×` at y∈[0.15, 0.6] plus a symmetric left-pointing V at y∈[0.77, 0.97] centred around y=0.87. After the Phase 30.3 nit (apex at x=0.18, tips at (0.35, 0.77)/(0.35, 0.97)) the V reads as an arrow-head pointer rather than a checkmark. The ~3% margin from the cell edge keeps stroke caps from clipping on any renderer. No collision with `sc` (centred ×, no marker), `sc-fl`/`sc-bl` (× + arc opening down/up).
- **`puff`**: three converging stems meeting a single open top-bar at y=0.20 across `x∈[0.25, 0.75]`. The stem tips land on the bar (0.35 / 0.5 / 0.65 at y=0.20), so the cap visibly closes the bundle at 24dp rather than floating above. Unambiguously distinct from `dc-cluster-3` (parallel stems + closed oval + slashes) and from `popcorn` (filled pentagon).
- **`hdc-cluster-3`**: parallel stems + closed oval, no slashes — JIS signal for hdc-height. Distinct from `dc-cluster-3` purely by slash absence; at 24dp the ~10 px slash difference is tight but legible. See §5.

## 3. JIS / JP-publisher authority

- **`reverse-sc`** (逆細編み): JIS L 0201 is silent on a dedicated glyph. Nihon Vogue (毛糸だま / Let's Knit) and Bunka both overlay `×` with a directional marker; the chevron form is defensible. Some recent Vogue patterns prefer an actual filled arrowhead — flagged as follow-up (user-side preference), not a block.
- **`puff`** (パフ編み): matches JIS L 0201 Table 2 and 毛糸だま canonical form (vertical bundle + single horizontal cap, no oval). Fully defensible against any JP reference book.
- **`hdc-cluster-3`** (中長編み3目の玉編み): matches Vogue / Ondori convention; JIS shows the cluster family but does not always enumerate the hdc variant explicitly. Defensible — a knitter reads "oval cap, no slashes = hdc bundle" correctly.

## 4. Coverage assessment

With these three added, `jis.crochet.*` now covers ~90% of commercial JP crochet pattern surface area. **Still missing**, in priority order for Phase 30.4 or later:

1. **`hdc-cluster-5`** (中長5目玉編み) — mirror of `dc-cluster-5`, `widthUnits=2`.
2. **Solomon's knot / ラブノット** — frequent in open-work shawls.
3. **Crossed dc (交差長編み)** — two dc with an X crossing; appears in aran-style crochet.
4. **Bullion stitch** — a spiral mark; niche but appears in 毛糸だま doily issues.
5. **Longer picots** (`picot-4`, `picot-5`) — parametric `picot-N` would be cleaner than individual glyphs.

None of these block Phase 32 (Chart Editor MVP). They're opportunistic top-ups informed by editor-palette telemetry.

## 5. Geometry nits and post-implementation tweaks

Applied in Phase 30.3 PR:

- **`reverse-sc` chevron** — moved from `M 0.35 0.75 L 0.15 0.88 L 0.35 0.88` (asymmetric V-with-flat-base that read like a checkmark) to `M 0.35 0.77 L 0.18 0.87 L 0.35 0.97` (symmetric V around y=0.87, arrow-head pointer, with a 3% margin from the cell edge so stroke caps do not clip).
- **`puff` top-bar and stems** — widened bar from `0.28→0.72` to `0.25→0.75`, and lifted all three stem tips from y=0.22/0.25/0.25 to y=0.20 so they land on the bar. Matches the JIS "loops pulled to one height" description and removes the visible stem-to-bar gap that would have read as three disconnected marks at 24dp on low-DPI Android.
- **`hdc-cluster-3` alias** — dropped `hdc3tog-puff` alias to avoid producing false-positive search hits on the word "puff" when the user is looking for `jis.crochet.puff`. `bob-hdc` retained.

Deferred to Phase 30.4 (bundled with `hdc-cluster-5`):

- **`hdc-cluster-3` stem spacing** — because there are no slashes, the three parallel stems can read as one thick line on low-DPI Android at 24dp. Consider pulling outer stems from `0.25 / 0.75` to `0.22 / 0.78` to increase inter-stem gap by ~6%. Purely cosmetic; will be re-evaluated together with the hdc-cluster-5 geometry.

## 6. Open questions escalated to user (none blocking)

- [ ] **`reverse-sc` directional marker form**: chevron (current, Knitter-preferred) vs. a filled arrow-head (Vogue-recent) vs. a JIS-style dot-plus-line. Our stroke-only renderer cannot draw a filled head cheaply, so the chevron is the practical choice until Phase 32 surfaces real user feedback. No blocking question for merge.

## 7. Team consensus

The Phase 30.3 direction (add reverse-sc / puff / hdc-cluster-3, defer hdc-cluster-5 / Solomon's knot / crossed-dc / bullion / long-picots) was agreed inside the agent team before kick-off:

- **Knitter (domain)**: advocated 30.3-first, citing puff as a zero-substitute glyph and reverse-sc as the standard amigurumi-edge finisher.
- **Architect / PM**: argued for jumping to Phase 32 (editor), treating catalog gaps as `?` placeholders.
- **Synthesis**: 3–5h cost to ship 30.3 first vs. day-one "this app doesn't know my craft" churn → 30.3 first, then Phase 32.

Recorded per CLAUDE.md step 10.
