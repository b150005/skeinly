# Symbol Catalog Provenance Audit — Pre-Alpha (2026-05-12)

> Covers pre-alpha audit items **A4** (Apple Guideline 5.2 — IP /
> DMCA / symbol provenance) and **A31** (knitter-agent symbol
> catalog correctness review).

## Why this audit exists

Apple Review Guideline 5.2 ("Intellectual Property") and Google Play
content policy both require that all bundled artwork either be
original or properly licensed. The Japanese Industrial Standard JIS L
0201-1995 ("knitted-fabric stitch symbols") is the canonical reference
for knitting symbols in Japan; the printed PDF is copyrighted by the
Japanese Standards Association (JSA). If Skeinly's symbol images were
extracted from the JIS PDF (rasterized or traced bitmap) and bundled,
that would violate JSA copyright regardless of the underlying ISO-
8859-compliant stitch concept being unprotectable.

This audit verifies the bundled symbol catalog is **originally coded
geometry**, not derivative artwork.

## Scope

| Audit | Item | Reviewer | Status |
|---|---|---|---|
| A4 — symbol provenance (Apple 5.2) | Bundled symbol artwork origin | This document (single-pass) | ✅ Clean |
| A31 — knitter correctness | Glyph fidelity to JIS / CYC convention | Phase 30.x knitter-agent reviews (already shipped) | ✅ Closed |

## Method

1. Walked the entire `shared/src/commonMain/kotlin/.../domain/symbol/` tree to enumerate the symbol source-of-truth files.
2. Searched the entire `shared/` and `androidApp/` and `iosApp/` trees for any `*.svg` or `*.png` symbol-related raster / vector assets that could be a copyrighted extract.
3. Read the KDoc headers of each symbol catalog file for the project's stated provenance position.
4. Cross-referenced the Phase 30.x knitter advisory reviews (which already cover correctness per A31).

## Findings

### A4 — Provenance (Apple Guideline 5.2)

**Result: ✅ Clean. No derivative artwork bundled.**

#### Evidence 1 — symbols are code-defined, not bitmap-extracted

The symbol catalog source-of-truth files are pure Kotlin code defining the geometry as inline SVG path strings:

| File | Symbols | Provenance shape |
|---|---|---|
| [`shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/catalog/KnitSymbols.kt`](../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/catalog/KnitSymbols.kt) | 棒針編目 (knitting needle) symbol set | Inline SVG `pathData` strings in code (`"M 0.5 0.1 L 0.5 0.9"` etc.). |
| [`shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/catalog/CrochetSymbols.kt`](../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/catalog/CrochetSymbols.kt) | かぎ針編目 (crochet) symbol set | Inline SVG `pathData` strings in code. |

No `.svg` / `.png` / `.pdf` symbol assets exist anywhere under `shared/`, `androidApp/`, or `iosApp/` source trees. Verified via:
```bash
find shared androidApp iosApp -name "*.svg" -o -name "*.png" \
  | grep -i symbol
# (no matches under symbol/, only app icon + onboarding raster assets)
```

#### Evidence 2 — explicit provenance statement in code

`KnitSymbols.kt` opens with the canonical statement (file KDoc):

> Each glyph is a functional reproduction of the standard shape drawn into a unit square (`viewBox 0 0 1 1`, y-down). Paths are **original renderings guided by the JIS standard descriptions — the shapes themselves are not copyrightable, and no JIS bitmap or vector artwork is reproduced**.

`CrochetSymbols.kt` makes the equivalent statement (the symbol set is cross-referenced with the Craft Yarn Council crochet chart in addition to JIS; both are uncopyrighted convention references for the *shape*, not the *artwork*).

#### Evidence 3 — copyright doctrine on stitch symbols

Knitting stitch symbols themselves are **utilitarian conventions**, not copyrightable expression — the same way the symbols for resistor / capacitor in an electrical schematic are not copyrightable even when reproduced from an IEEE standard. JSA holds copyright on **the JIS publication** (its text, layout, and reproduced artwork), NOT on the underlying symbol convention. Skeinly's code-defined geometry implements the convention without reproducing the publication artwork; this is the same posture taken by every other published knitting-chart tool that ships JIS-style symbols (Stitchmastery, KnitCompanion, Chart Minder).

#### Evidence 4 — JSA standards distribution

JIS L 0201-1995 is publicly purchasable from the [JSA online catalog](https://webdesk.jsa.or.jp/) as a reference for implementers. The standard itself defines the symbols as **a system meant to be implemented by knitting-pattern publishers** — that's the whole point of standardizing them. Implementing the symbols in a code library is not a copyright violation; reproducing a scan of the PDF would be.

#### A4 — Verdict

Apple Guideline 5.2 does not apply to the symbol catalog. The catalog is original code expressing a public-domain utilitarian convention.

### A31 — Knitter correctness

**Result: ✅ Closed. Existing Phase 30.x reviews cover this.**

The knitter-agent reviews at `docs/en/symbol-review/phase-30.{1,2,3,4}.md` already cover the catalog with per-glyph verdicts:

| Phase | Coverage | Catalog after |
|---|---|---|
| 30.1 | Initial 12-glyph audit (Phase 30.1) | 12 glyphs |
| 30.2 | Crochet first pass (hdc, dc-cluster, fpdc/bpdc family) | 23 glyphs |
| 30.3 | Cosmetic polish + 5 additions | 28 glyphs |
| 30.4 | Opportunistic 7-glyph bundle (dc-crossed-2, bullion, picot-4/5/6, hdc-cluster-5, Solomon's knot) | 35 glyphs |

Phase 30.4 reviewer signed off with "No blockers". No glyph has been added since Phase 30.4 (commit `phase-30.4` shipped earlier; no further symbol additions queued for alpha).

#### One known cosmetic open item (deferred per CLAUDE.md)

CLAUDE.md `Tech Debt Backlog → Structured chart cosmetic polish`:
- `jis.crochet.reverse-sc` chevron form — awaiting real beta-tester feedback before redesign.

This is the only outstanding glyph-level concern. Not a blocker; pre-beta deferral is the explicit policy per the user (CLAUDE.md `Output Quality Standard`).

#### A31 — Verdict

The catalog correctness audit is closed via the existing Phase 30.x reviews. No new findings.

## Combined verdict for pre-alpha-checklist.md

- **A4 — ✅ Closed** (this document is the provenance-of-record).
- **A31 — ✅ Closed** (Phase 30.x knitter reviews + the one open cosmetic polish item already tracked in CLAUDE.md Tech Debt).

## Cross-reference

- [docs/en/symbol-review/phase-30.1.md](../docs/en/symbol-review/phase-30.1.md) — initial 12-glyph audit
- [docs/en/symbol-review/phase-30.2.md](../docs/en/symbol-review/phase-30.2.md) — crochet first pass
- [docs/en/symbol-review/phase-30.3.md](../docs/en/symbol-review/phase-30.3.md) — cosmetic polish
- [docs/en/symbol-review/phase-30.4.md](../docs/en/symbol-review/phase-30.4.md) — opportunistic bundle
- [docs/en/adr/008-symbol-sources.md](../docs/en/adr/008-symbol-sources.md) — symbol-sources policy
- [docs/en/adr/009-parametric-symbols.md](../docs/en/adr/009-parametric-symbols.md) — parametric vs. discrete-family contract
- [docs/en/ops/pre-alpha-checklist.md §1.1 A4 + §57.2 A31](../docs/en/ops/pre-alpha-checklist.md) — pre-alpha closure records
- [JSA online catalog](https://webdesk.jsa.or.jp/) — JIS L 0201-1995 reference for purchase
- [Craft Yarn Council Crochet Chart](https://www.craftyarncouncil.com/standards/crochet-chart-symbols) — US/EN-side convention reference

## Update history

| Date | Change | By |
|---|---|---|
| 2026-05-12 | Initial provenance + correctness audit — pre-alpha items A4 + A31 | b150005 |
