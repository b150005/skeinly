# Phase 30.4 — Knitter Advisory: Opportunistic Glyph Bundle

**Date:** 2026-04-21
**Reviewer:** Knitter agent (see [`.claude/agents/knitter.md`](../../../.claude/agents/knitter.md))
**Authority:** [ADR-009 §8 — `picot-N` resolution](../adr/009-parametric-symbols.md) — picots ship as a discrete family, not parametric.
**Source under review:** [`shared/src/commonMain/kotlin/io/github/b150005/knitnote/domain/symbol/catalog/CrochetSymbols.kt`](../../../shared/src/commonMain/kotlin/io/github/b150005/knitnote/domain/symbol/catalog/CrochetSymbols.kt) — currently 28 glyphs; Phase 30.4 lands 7 additions.
**References:** JIS L 0201-1995 Table 2 (かぎ針編目), CYC crochet chart, Nihon Vogue / Bunka / Ondori JP publisher conventions.

This is a **pre-implementation** advisory. It drafts canonical geometry for the seven glyphs queued after Phase 30.3 per [`phase-30.3.md §4`](./phase-30.3.md#4-coverage-assessment), resolves the picot-N family contract against ADR-009 §8, and folds in the one deferred cosmetic tweak (`hdc-cluster-3` stem spacing).

Scope is intentionally tight: these are all **opportunistic** additions — no single glyph here blocks Phase 32 editor usability, but shipping them now unlocks aran-style crochet (crossed dc), open-work shawl patterns (Solomon's knot), doily issues (bullion), full hdc-cluster symmetry, and the long-picot family that appears on any project with edging beyond a 3-chain corner.

## 1. Verdict summary

| id | Verdict | Notes |
|---|---|---|
| `jis.crochet.hdc-cluster-5` | **ship** | Mirror of `dc-cluster-5` with slashes omitted (same hdc signal as `hdc-cluster-3`). `widthUnits=2`. |
| `jis.crochet.solomon-knot` | **ship** | Tall narrow elongated loop. Only symbol in the bundle without a JIS Table 2 entry — publisher convention drives it. |
| `jis.crochet.dc-crossed-2` | **ship** | Two dc stems crossing in an X with individual top-bars + slashes. `widthUnits=2`. |
| `jis.crochet.bullion` | **ship** | Two-coil spring silhouette (reduced from three per knitter m1 to preserve 24dp legibility). |
| `jis.crochet.picot-4` | **ship** | Loop slightly taller than `picot-3`. `widthUnits=1`. |
| `jis.crochet.picot-5` | **ship** | Near-full-cell loop. `widthUnits=1`. |
| `jis.crochet.picot-6` | **ship** | Arch loop. `widthUnits=2` — loop naturally overflows a single cell per ADR-009 §8 clause. |
| `jis.crochet.hdc-cluster-3` (tweak) | **polish** | Outer stems `0.25/0.75` → `0.22/0.78` per [`phase-30.3.md §5`](./phase-30.3.md#5-geometry-nits-and-post-implementation-tweaks) deferred nit. |

No blockers. Catalog lands at 35 glyphs after this phase (28 + 7).

## 2. Per-glyph canonical rendering

### 2.1 `jis.crochet.hdc-cluster-5` — 中長編み5目の玉編み / 5-hdc cluster

**References**: Vogue / Ondori doily patterns; JIS Table 2 shows the cluster family without enumerating the hdc variant. Mirrors the `hdc-cluster-3` signal (oval cap, no slashes = hdc height).

**Geometry** (unit-square, y-down; `widthUnits = 2`):

```
M 0.1 0.9 L 0.1 0.3
M 0.3 0.9 L 0.3 0.3
M 0.5 0.9 L 0.5 0.3
M 0.7 0.9 L 0.7 0.3
M 0.9 0.9 L 0.9 0.3
M 0.05 0.2 C 0.05 0.08 0.25 0.03 0.5 0.03
C 0.75 0.03 0.95 0.08 0.95 0.2
C 0.95 0.32 0.75 0.32 0.5 0.32
C 0.25 0.32 0.05 0.32 0.05 0.2 Z
```

**Rationale**: Identical stem + oval-cap coords as `dc-cluster-5`, with all five slashes stripped. That keeps the visual-contrast story consistent — slash absence = hdc, slash presence = dc (ADR-008 §fill-vs-stroke does not apply; this is stroke-only).

**Labels**:
- JA: 中長編み5目の玉編み
- EN: 5-hdc cluster
- aliases: `bob-hdc-5` (mirrors `hdc-cluster-3`'s `bob-hdc` alias precedent)
- `cycName`: `hdc-cl5`

### 2.2 `jis.crochet.solomon-knot` — ラブノット / Solomon's knot

**References**: JIS L 0201 Table 2 is **silent** on this stitch. Nihon Vogue (毛糸だま shawl / stole issues), Bunka, and Western publishers (Interweave) render ラブノット as a **tall narrow open loop** — two vertical arc curves joined at top and bottom caps. The elongation is the semantic signal; the outline is open, not a closed pill.

**Geometry** (unit-square, `widthUnits = 1`) — per knitter review M1 (pre-impl draft was a closed pill, which reads as a ch-oval rotated; the open-loop form matches Vogue/Bunka shawl books):

```
M 0.45 0.08 C 0.35 0.3 0.35 0.7 0.45 0.92
M 0.55 0.08 C 0.65 0.3 0.65 0.7 0.55 0.92
M 0.4 0.08 L 0.6 0.08
M 0.4 0.92 L 0.6 0.92
```

Two vertical arc curves bowing slightly outward (mid-height width ~0.3, caps width ~0.2), joined by short horizontal cap-lines top and bottom. Near-full cell height (y∈[0.08, 0.92]).

**Rationale**: Only glyph in the bundle without a JIS reference. The open-loop form beats both (a) the closed pill (reads as horizontal `ch` rotated 90°) and (b) the loop-with-base-dot (cluttered at 24dp). `jisReference = null` since JIS is silent; `cycName = "Solomon's knot"` per Interweave convention.

**Known variants (surface in advisory, do not ship)**:
- Long oval with a small filled dot at base — Bunka style in some 2020+ books.
- Long oval with an inline `×` anchor at mid-height — rarer, reads as a crossing bullion.

Either can be swapped later without id churn. Flagged as an open question below.

**Labels**:
- JA: ラブノット
- EN: Solomon's knot
- aliases: `love knot`, `true lover's knot`
- `jisReference`: null

### 2.3 `jis.crochet.dc-crossed-2` — 2目交差の長編み / Crossed 2-dc

**References**: JIS L 0201 Table 2 shows the crossed family; Vogue / Bunka both render as two full dc glyphs with their stems angled so they cross mid-height. Each stem retains its own top-bar + slash (same visual signature as a straight `dc` but rotated).

**Geometry** (unit-square, `widthUnits = 2`):

```
M 0.1 0.9 L 0.7 0.1
M 0.9 0.9 L 0.3 0.1
M 0.6 0.1 L 0.8 0.1
M 0.2 0.1 L 0.4 0.1
M 0.25 0.45 L 0.45 0.55
M 0.55 0.45 L 0.75 0.55
```

Two angled stems (bottom-outer to top-inner corners), each with a short top-bar at its top endpoint (spanning 0.2 of width on either side of the stem top). Slashes mirror each other around the x=0.5 midline, placed mid-stem at y≈0.5.

**Rationale**: Stems cross at (0.5, 0.5) — the standard JP crossed-dc rendering. `widthUnits=2` because two stitches are worked across two columns even though the graphic fits in that span. Top-bars at y=0.1 matches the `dc` glyph y=0.1 crossbar so the stitch height signal is consistent.

**Labels**:
- JA: 2目交差の長編み
- EN: Crossed 2-dc
- aliases: `dc cross`, `cross dc`
- `cycName`: `cross-dc`

### 2.4 `jis.crochet.bullion` — バリオン編み / Bullion stitch

**References**: JIS is silent. JP publishers (毛糸だま doily issues, Bunka lace books) render as a **vertical stem with a coiled-spring overlay** — the coils indicate the yarn wraps that define the stitch. Western publishers (Interweave, Vogue Knitting) sometimes use a tall narrow oval with a slash, which is too close to `tr` visually — avoid.

**Geometry** (unit-square, `widthUnits = 1`) — per knitter review m1 (pre-impl draft had three half-loops which collapse at 24dp into a blur; two coils preserve the "visible coil" signal without hitting the Phase 30.2 §3.5 density failure mode):

```
M 0.5 0.1 L 0.5 0.9
M 0.5 0.2 C 0.75 0.28 0.75 0.48 0.5 0.5
M 0.5 0.5 C 0.25 0.52 0.25 0.72 0.5 0.8
```

A vertical stem with two half-loops alternating sides — the JP-publisher "visible coil" signal. Loops are ~0.25 wide on each side of the centerline and ~0.3 tall each.

**Rationale**: Distinct from `tr` (T + 2 horizontal slashes) and `qtr` (T + 4 slashes) — the alternating-side half-loops read unambiguously as a coil rather than parallel slashes. Two coils beat three for 24dp legibility; the "how many wraps" detail is not the semantic signal (that's in the EN description text), the "spring-like" silhouette is.

**Labels**:
- JA: バリオン編み
- EN: Bullion stitch
- aliases: `bullion`, `roll stitch`
- `jisReference`: null (JIS silent)

### 2.5 `jis.crochet.picot-{4,5,6}` — discrete family

Per **ADR-009 §8**, these are **not parametric** — geometry varies with N (3-chain vs. 6-chain picots read as different sizes in commercial patterns), so each is a first-class catalog entry. `picot-3` stays at its current geometry; the new entries scale the loop size with N.

#### `jis.crochet.picot-4` — 4目のピコット / Picot (4-ch) — `widthUnits = 1`

```
M 0.5 0.6 L 0.5 0.9
M 0.25 0.6 C 0.25 0.2 0.75 0.2 0.75 0.6 Z
```

Slightly taller loop than `picot-3` (top at y=0.2 vs. 0.25), slightly wider (x∈[0.25, 0.75] vs. [0.3, 0.7]), shorter stem (y∈[0.6, 0.9]).

#### `jis.crochet.picot-5` — 5目のピコット / Picot (5-ch) — `widthUnits = 1`

```
M 0.5 0.65 L 0.5 0.9
M 0.2 0.65 C 0.2 0.1 0.8 0.1 0.8 0.65 Z
```

Near-full-cell loop (top at y=0.1, width x∈[0.2, 0.8]). Still single-cell — a 5-chain picot fits comfortably in one column on most JP commercial charts.

#### `jis.crochet.picot-6` — 6目のピコット / Picot (6-ch) — `widthUnits = 2`

Per knitter review m3, the pre-impl draft kept the loop at x∈[0.1, 0.9] which — after widthUnits=2 stretch — renders at 80% of the 2-cell span (~1.6 cells wide), visually close to `picot-5`. Widen loop to near-full unit-square width so the rendered span is truly ~2 cells and the visual step-up from `picot-5` is unambiguous:

```
M 0.5 0.75 L 0.5 0.95
M 0.05 0.75 C 0.05 0.05 0.95 0.05 0.95 0.75 Z
```

Arch-shaped full-width loop (x∈[0.05, 0.95] pre-stretch = ~1.8 cells post-stretch at widthUnits=2, per ADR-009 §8 clause "widthUnits = 2 if the loop naturally overflows a single cell"). Shorter stem (y∈[0.75, 0.95]) because the arch commands more vertical space.

**Labels** (all three):
- JA: `{N}目のピコット` (e.g. `4目のピコット`)
- EN: `Picot ({N}-ch)` (e.g. `Picot (4-ch)`)
- `cycName`: `picot-{N}`
- aliases: `picot{N}`, `{N}-ch picot`
- `jisReference`: JIS L 0201-1995 かぎ針編目 (the picot family is in Table 2; the specific N values are publisher convention)

**No parameter slots** on any member — ADR-009 §8 is explicit.

### 2.6 `jis.crochet.hdc-cluster-3` — stem-spacing tweak

Outer stems from `0.25 / 0.75` → `0.22 / 0.78`. Oval cap untouched (still spans 0.15 → 0.85; the 3% wider stem spread stays inside the cap). Reason: at 24dp on low-DPI Android the three parallel stems can read as one thick line because there are no slashes to break them up; `0.06` of additional inter-stem gap is enough to disambiguate without breaking the cap enclosure.

New pathData:

```
M 0.22 0.9 L 0.22 0.3
M 0.5 0.9 L 0.5 0.3
M 0.78 0.9 L 0.78 0.3
M 0.15 0.2 C 0.15 0.08 0.35 0.03 0.5 0.03
C 0.65 0.03 0.85 0.08 0.85 0.2
C 0.85 0.32 0.65 0.32 0.5 0.32
C 0.35 0.32 0.15 0.32 0.15 0.2 Z
```

## 3. Coverage after this bundle

With these seven glyphs, `jis.crochet.*` now covers:
- ~95% of commercial JP crochet pattern surface area (up from ~90% post-30.3)
- Aran-style crochet (crossed dc) — previously uncovered
- Open-work shawls (Solomon's knot) — previously uncovered
- Bobble lace / doilies (bullion + hdc-cluster-5) — previously partially covered
- Edging variants up through 6-chain picots — previously limited to `picot-3`

**Still missing**, for future opportunistic phases (no blocker):
- Foundation stitches (`fsc`, `fdc`) — US-dominant convention.
- Extended sc (`exsc`) — CYC-standard, JIS silent.
- Spike stitches (long-loop variants) — colorwork crochet.
- Turning-ch-N family — ADR-009 §9 flags this as a separate decision (parametric vs. discrete family). Not in Phase 30.4 scope; reopens when a concrete user pattern demands a non-dc turning chain.

## 4. Open questions (non-blocking, escalated to user)

- [ ] **`solomon-knot` secondary marker**: pure open loop (shipping, per knitter M1) vs. add a small base-dot anchor (Bunka 2020+ variant). Real pattern reports can drive a later variant if needed.
- [ ] **`dc-crossed-2` top-bar placement clarity**: implementer must add an explicit code comment (per knitter m2) naming each stem's endpoint — left stem ends at (0.7, 0.1), right stem ends at (0.3, 0.1) — so a future reader does not "correct" the bars back to the pre-cross column positions.

## 5. Knitter review outcomes (pre-implementation)

The pre-impl draft was reviewed by the knitter agent and amended before code was written. Summary of fixes folded into this advisory:

- **M1 (major) → fixed**: `solomon-knot` path changed from closed pill (read as rotated `ch`) to two vertical arc curves joined by short top/bottom caps — the Vogue / Bunka / Interweave open-loop form.
- **m1 (minor) → fixed**: `bullion` dropped from three half-loops to two. Three coils compressed into the y=0.2–0.85 band would fall below the Phase 30.2 §3.5 density threshold at 24dp.
- **m2 (minor) → ship with code comment**: `dc-crossed-2` top-bar coords unchanged (they are correct for crossed-stem endpoints), but the implementer must annotate the stem endpoints in the code comment so the geometry reads unambiguously.
- **m3 (minor) → fixed**: `picot-6` loop widened from x∈[0.1, 0.9] to x∈[0.05, 0.95] so the widthUnits=2 stretch renders a true ~2-cell loop, visually distinct from `picot-5`.

All `OK` verdicts unchanged: `hdc-cluster-5`, `hdc-cluster-3` tweak, `picot-4`, `picot-5`, bilingual labels, ADR-009 §8 compliance.

## 6. Team consensus

Pre-kickoff agreement with the agent team:

- **Knitter (domain)**: advocated all 7 glyphs this phase; flagged picot-6 widthUnits=2 explicitly against ADR-009 §8 to avoid a surprise at render time.
- **Architect**: confirmed the ADR-009 §8 discrete-family decision keeps the catalog mechanical (each picot-N is its own entry, no parameter-slot gymnastics).
- **Implementer**: bundled the 30.3 deferred `hdc-cluster-3` stem-spacing polish per CLAUDE.md Tech Debt Backlog convention (dedicated PR; explicitly called out in commit message).
- **PM**: no user-visible surface beyond the dictionary gallery this phase — editor palette will pick them up automatically once they're in the catalog.

Recorded per CLAUDE.md step 10.
