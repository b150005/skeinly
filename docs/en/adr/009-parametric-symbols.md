# ADR-009: Parametric Symbol Contract

## Status

Accepted

## Context

The Phase 30.x symbol catalog ships two structurally different kinds of
"count-bearing" glyphs:

1. **Count baked into the id.** `jis.crochet.dc-cluster-3`,
   `jis.crochet.dc-cluster-5`, `jis.crochet.picot-3`. Geometry is drawn for
   exactly that count; a different count = a different symbol id.
2. **Count carried as a cell parameter.** `jis.crochet.ch-space` declares
   `parameterSlots = [ParameterSlot(key = "count", defaultValue = "n", ...)]`
   and the viewer overlays `cell.symbolParameters["count"]` on top of a
   geometry that does not depend on the count.

The Phase 30.4 backlog wants `picot-N` (longer picots: 4-, 5-, 6-chain
variants) and `hdc-cluster-5`. Without an explicit rule, each new glyph
re-opens the "id-encoded vs. parameter-slot" debate, and the editor MVP
has no story for placing either kind of cell — today it always writes
`symbolParameters = emptyMap()`, so every parametric cell renders its
`defaultValue` literal (e.g. the string `"n"`) instead of a real count.

The Phase 32 handoff flagged this as a blocker: splitting `picot-N` into
a standalone mini-ADR ahead of the Phase 30.4 glyph bundle prevents
ad-hoc lock-in when the first parametric glyph ships to users.

This ADR fixes the contract. It does **not** implement the editor UI for
entering parameter values (that is a Phase 32.3 follow-up) nor populate
new glyphs (Phase 30.4).

## Decision

### 1. Two-axis classification

A count-bearing glyph is classified by two independent axes:

- **Geometry axis** — does the drawing *change shape* when the count
  changes? (Yes = `geometry-varying`, No = `geometry-invariant`.)
- **Label axis** — does the chart show the count *as a rendered number*
  inside the cell? (Yes = `labeled`, No = `unlabeled`.)

Canonical catalog entries for each quadrant:

| | labeled | unlabeled |
|---|---|---|
| **geometry-varying** | `jis.crochet.picot-{N}` (each N is a separate id) | `jis.crochet.dc-cluster-{3,5}` |
| **geometry-invariant** | `jis.crochet.ch-space` (`count` slot) | *not parametric — ship as a plain glyph* |

### 2. Parametric = geometry-invariant + rendered number

A symbol is **parametric** (uses `parameterSlots`) iff its geometry is
invariant and the chart convention is to render the caller's value on
top. Every other count-bearing glyph is a **family of discrete
symbols** (one id per N).

This closes the interpretation debate on `ch-space` (parametric, correct)
and `dc-cluster-3` / `dc-cluster-5` (discrete family, correct — each has
its own carefully-balanced stroke positions and the chart never overlays
a digit).

### 3. Rule for new glyphs

When adding an Nth glyph, ask in this order:

1. **Does the drawing change with N?** If yes → discrete family. Ship
   one `SymbolDefinition` per supported N under the same stem
   (`{stem}-{N}`). Target the N values knitters actually use in
   commercial patterns; do not anticipate every integer.
2. **Is the number rendered inside the cell?** If yes → parametric. Add
   one `SymbolDefinition` with `parameterSlots`.
3. Otherwise → plain (non-parametric) glyph.

### 4. Discrete family identifier syntax

- Id shape: `{namespace}.{stem}-{N}` with a decimal integer suffix.
  Examples: `jis.crochet.dc-cluster-3`, `jis.crochet.picot-4`.
- The stem is stable across N (no pluralisation or tense change).
- Each family member is a full first-class catalog entry — its own
  labels, descriptions, aliases, jisReference, and pathData. Copy-
  paste is acceptable; the catalog prefers explicitness over DRY.
- Aliases may include the un-suffixed stem (e.g. `picot`) on the most
  common member (by convention: the shortest, so `picot-3` carries
  `picot` in its aliases) so dictionary search matches the bare term.

### 5. Parametric symbol contract

- `ParameterSlot.key` — stable, opaque string. Never reused across
  slot semantics. New parameter semantics (e.g. a colour id) get a
  new key, not a repurposed one.
- `ParameterSlot.defaultValue` — optional. When non-null and the cell
  omits the key, the viewer renders the default literally. Authors
  who want `ch-sp` to display "n" when empty rely on this.
- **Value type is `String`.** `ChartCell.symbolParameters` is
  `Map<String, String>` on the wire. Numeric validation (if any) is
  Phase 32.3 editor-UX scope; the domain model does not gate it.
  Rationale: the chart is ultimately a graphic; `"n"`, `"x"`, or `"?"`
  are legitimate placeholder characters knitters write in real
  patterns.
- A parametric symbol's geometry **must be independent** of the
  parameter value. Renderers composite the stringified value on top
  via the slot anchor; they do not re-parse the pathData. If geometry
  must vary, split into a discrete family (§4).
- Unknown slot keys in `ChartCell.symbolParameters` (keys not declared
  on the `SymbolDefinition`) are preserved on round-trip and ignored
  by the renderer. Forward-compat: a future catalog version adding a
  slot decodes old cells without data loss.

### 6. Content-hash invariants

- `computeContentHash` already includes `symbolParameters` via the full
  JSON serialization of each `ChartCell`. Two chart documents with
  identical drawings but different parameter values → different hashes.
  This is correct: the rendered chart differs, so sync should treat
  them as distinct.
- ADR-008 §Phase 32.1 excluded `craftType` / `readingConvention` from
  the hash because those are metadata *outside* the drawing. Parameter
  values are *inside* the drawing — the hash includes them.

### 7. Editor UX delta (Phase 32.3 scope)

The Phase 32 MVP editor writes `symbolParameters = emptyMap()` for every
cell. For parametric cells this means the viewer falls back to
`defaultValue` and renders the placeholder literally. The user can work
around it only by hand-editing the document today.

Phase 32.3 will add:

- **Placement flow** — when the palette selection has non-empty
  `parameterSlots`, the tap-to-place event does *not* commit the cell
  immediately. Instead it opens a small inline input (Compose
  `BasicTextField` / SwiftUI `TextField`) keyed by
  `ParameterSlot.enLabel` / `ParameterSlot.jaLabel`.
- **Confirm / cancel** — commit writes
  `symbolParameters = { slot.key -> enteredValue }`; cancel discards.
- **Re-edit** — tapping an existing parametric cell reopens the
  inline input pre-populated with its current value.
- **Discrete families** are not affected — tapping a palette entry
  for `dc-cluster-5` just places the cell with that id. No prompt.
- **No schema change** — `ChartCell.symbolParameters` already exists
  and round-trips today.

Phase 32.3 lives outside this ADR. What this ADR fixes is the shape the
editor must conform to, so that the picot-N bundle and any future
parametric glyph ship without editor surprises.

### 8. `picot-N` resolution (the triggering case)

Per §3, `picot-N` geometry clearly varies with N — a 3-chain picot is a
small loop, a 5-chain picot is a taller loop or arc, a 6-chain picot
may span two cells. This is a discrete family:

- Phase 30.4 adds `jis.crochet.picot-{4,5,6}` as full catalog entries
  next to the existing `picot-3`.
- Each entry has its own `pathData` tuned for that loop size; the
  existing `picot-3` id stays stable.
- `picot-3` gains the alias `picot` so dictionary search on the bare
  term still resolves.
- `picot-5` and `picot-6` may set `widthUnits = 2` if the loop
  naturally overflows a single cell (decision per-glyph by the
  knitter advisory).
- No `parameterSlots` on any member.

`picot-N` is explicitly **not** parametric because the chart convention
is not to render the digit — the loop size itself is the signal.

### 9. Counter-case: `turning-ch`

The existing `jis.crochet.turning-ch` glyph draws three stacked chain-
ovals and is used for turning chains of any count (1..6 typical). The
Phase 30.2 knitter advisory §Q8 deferred `turning-ch-2 .. turning-ch-6`
to Phase 30.3 but they never shipped.

Under this ADR, `turning-ch` is a **parametric glyph** candidate (the
chain count *is* a rendered number in common JA chart conventions).
Phase 30.4 may either:

- Leave it as the existing three-oval stylisation (geometry-invariant
  visual signal that means "turning chain"), then add a `count` slot
  so the author can overlay the number. **Recommended.**
- Split into a discrete family `turning-ch-{2..6}` where the oval
  stack height varies with N. More visually faithful to some
  publishers but quintuples the catalog entries and fights the
  existing rendering.

The `knitter` agent has the call when Phase 30.4 queues this glyph.
Either path is ADR-legal.

## Consequences

### Positive

- Parametric vs. discrete-family decision becomes mechanical (§3) —
  no repeated re-litigation per glyph.
- `picot-N` ships as a discrete family; the existing `picot-3` id stays
  stable and the two-axis rule vindicates that choice.
- `ch-space`'s parametric form remains correct.
- `ChartCell.symbolParameters` is ratified as the supported authoring
  surface for parameter values, unblocking Phase 32.3 editor UX.
- `turning-ch` has a documented resolution path without forcing a
  decision today.

### Negative

- Discrete families duplicate catalog entries (N copies of a
  substantially similar glyph). Acceptable given the small expected N
  (3..6 for picot, 3/5 for dc-cluster) and the value of per-N
  geometry tuning.
- Parametric symbols still render `defaultValue` literally in the
  Phase 32 MVP. Users authoring `ch-space` today see the placeholder
  "n" until Phase 32.3 lands. Documented, not fixed.

### Neutral

- `widthUnits` / `heightUnits` continue to be per-entry values, so a
  discrete family member that naturally spans multiple cells (e.g. a
  hypothetical `picot-6`) declares its span independently of the
  count.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Single parametric `picot` with a `count` slot | Geometry must vary with N — a 6-chain loop is not a scaled 3-chain loop; the chart convention is to read size, not a digit. Violates §2. |
| Force every count-bearing glyph to parametric | Destroys per-N geometry tuning for `dc-cluster-3/5`; chart convention for those families doesn't render a digit. |
| Force every count-bearing glyph to discrete family | `ch-space` would need `ch-space-2`, `ch-space-3`, … for every possible span. The chart convention *is* to render the digit; exhaustive enumeration loses the point. |
| Integer-typed `symbolParameters: Map<String, Int>` | Real patterns use non-numeric placeholders like `n`, `x`, `?`. Stringly-typed is closer to the rendered artifact. |
| Gate parametric id regex (e.g. require trailing `-N`) | Conflates two orthogonal axes (geometry and labelling). The two-axis table (§1) is the correct factorisation. |

## References

- ADR-008 §6 (symbol-id format + `parameterSlots`)
- ADR-008 Phase 30.2-fix addendum (`SymbolDefinition.fill` precedent
  for additive non-breaking catalog fields)
- ADR-008 Phase 30.3 addendum (deferred Phase 30.4 bundle including
  `picot-N` and `hdc-cluster-5`)
- `docs/en/symbol-review/phase-30.2.md §Q8` (turning-ch deferral)
- `docs/en/symbol-review/phase-30.3.md §7` (team consensus on
  30.x-first vs. 32-first)
