# ADR-008: Structured Chart Data Model

## Status

Accepted

## Context

ADR-007 pivoted Knit Note from a row counter to a structured chart authoring
product. Phase 29 must land the first version of that data model — a
representation of a knitting chart as a structured document rather than a
photo — on which all of Phase 30 (Symbol Library), Phase 31 (Chart Viewer),
Phase 32 (Chart Editor MVP), and eventually Phase 37+ (Git-like
collaboration) will be built.

The model must:

1. Support both rectangular (flat knit/purl grids) and circular (round /
   polar) charts, because many patterns work in the round.
2. Support multiple stitch-symbol standards in the same document without
   committing to one standard (JIS L 0201 is dominant in JA patterns;
   Craft Yarn Council symbols dominate EN patterns; no ISO standard exists;
   users will want to define custom symbols).
3. Coexist with the legacy `Pattern.chartImageUrls` photo-based chart field.
   The roadmap includes AI-assisted photo → structured chart import, which
   means an imported chart may be very large (hundreds of rows × dozens of
   stitches per row).
4. Not paint us into a corner for Phase 37 collaboration (commit history,
   branches, diffs).
5. Not require production data migration — this is a pre-release throwaway
   window per ADR-007 §Decision 5.

## Decision

### 1. Storage: JSON document, not normalized tables

A chart is stored as a single JSON document:

- Local: one row in `StructuredChartEntity` with a `document` TEXT column
  holding the serialized `StructuredChart` payload.
- Remote: one row in `chart_documents` with a Postgres `jsonb` column.

Alternatives rejected:

- **Normalized `chart` / `layer` / `cell` tables.** Cell-level UPDATE via
  Supabase Realtime does not carry chart-level context to subscribers,
  making coherent viewer updates hard. Partial-row write granularity is
  also wasted when the editor mental model is "save the whole chart."
  Tripling the mapper and sync surface pays no dividend for Phase 29/31/32.
- **SQLite FTS / GIN on cells.** No Phase 29–32 UseCase queries *inside*
  a chart. Discovery in Phase 36 may want tag/symbol search, at which
  point a GIN index on `document` can be added without schema reshape.

### 2. Whole-document upsert, not per-cell diff

Every edit produces a new complete document. Writes are:

- `local.upsert(chart)` — single row update
- `syncManager.syncOrEnqueue(STRUCTURED_CHART, id, UPDATE, json)` — single
  payload

Rationale:

- Existing `PendingSync` coalescing already dedupes rapid consecutive edits
  by `(entityType, entityId)`, so UX responsiveness is fine.
- Realtime `postgres_changes` emits the full new row; the viewer just
  replaces its in-memory chart atomically.
- Matches how the existing `PatternRepositoryImpl` and
  `ProjectRepositoryImpl` already work — no new sync primitives needed.

### 3. Schema versioning + forward-compat escape hatches

The document carries a `schema_version: Int` (Phase 29 fixes version 1) and
the `chart_documents` row carries a separate `storage_variant: TEXT`
column (Phase 29 only ships `'inline'`). Either field can be advanced
without a destructive migration when:

- The in-document layout changes (`schema_version` bump + mapper `when`).
- A single row can no longer hold an AI-imported giant chart
  (`storage_variant = 'chunked'` branches to a separate `chart_chunks`
  table; `chart_documents.document` becomes a manifest). **Not
  implemented in Phase 29.** The column exists as the future branch point.

### 4. Domain model shape

```kotlin
@Serializable
data class StructuredChart(
    val id: String,
    @SerialName("pattern_id") val patternId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("coordinate_system") val coordinateSystem: CoordinateSystem,
    val extents: ChartExtents,
    val layers: List<ChartLayer>,
    @SerialName("revision_id") val revisionId: String,
    @SerialName("parent_revision_id") val parentRevisionId: String?,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
)

@Serializable enum class CoordinateSystem {
    @SerialName("rect_grid") RECT_GRID,
    @SerialName("polar_round") POLAR_ROUND,
}

@Serializable
sealed interface ChartExtents {
    @Serializable @SerialName("rect")
    data class Rect(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) : ChartExtents
    @Serializable @SerialName("polar")
    data class Polar(val rings: Int, val stitchesPerRing: List<Int>) : ChartExtents
}

@Serializable
data class ChartLayer(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val cells: List<ChartCell>,
)

@Serializable
data class ChartCell(
    @SerialName("symbol_id") val symbolId: String,
    val x: Int,
    val y: Int,
    val width: Int = 1,
    val height: Int = 1,
    val rotation: Int = 0,
    @SerialName("color_id") val colorId: String? = null,
)
```

### 5. Coordinate convention

In `RECT_GRID`, `y` increases upward. Knitting charts conventionally
read from bottom to top; a cell at `y = 0` is on row 1 (bottom). This is
documented in `docs/en/chart-coordinates.md` (Phase 29). SwiftUI and
Compose Canvas renderers in Phase 31 apply a `scaleY(-1)` transform at
the viewport layer.

### 6. Symbol ID format (catalog deferred to Phase 30)

Symbol IDs are namespaced dot-separated lowercase strings matching
`^[a-z]+(\.[a-z0-9_]+)+$`:

- `jis.*` — JIS L 0201 編目記号 (knit + crochet + afghan)
- `std.*` — international (Craft Yarn Council) standard
- `user.{uuid}.*` — user-defined custom symbols
- `ext.*` — reserved for future extensions

Rationale over `enum class`:

- Enums force recompile for every new symbol; Phase 35 user-defined
  symbols would require a second field anyway.
- JSON-boundary safety: unknown IDs render as a placeholder glyph
  instead of failing deserialization.

Phase 29 fixes only the *format*. The actual `SymbolCatalog` (ID →
SVG path + bilingual label + JIS↔CYC mapping) is Phase 30.

### 7. Revision metadata in Phase 29 (not just Phase 37)

`revisionId`, `parentRevisionId`, and `contentHash` ship in Phase 29
even though no UI consumes them yet:

- Adding them at Phase 37 would require a backfill after Phase 39
  closed beta has shipped to real users, contradicting ADR-007 §5.
- `contentHash` enables cheap idempotent sync retries and Realtime
  dedup in Phase 29 itself.
- Phase 37 adds a separate `chart_revisions` table holding the DAG;
  `chart_documents` always holds the current tip. No destructive
  migration.

### 8. Pattern relationship

`StructuredChart.patternId` is non-nullable: every structured chart
belongs to a pattern. Each pattern has 0..1 structured charts
(enforced by `UNIQUE(pattern_id)` on `chart_documents`).

`Pattern.chartImageUrls` is preserved. A pattern may have both a
photo chart and a structured chart. Phase 31 viewer precedence UX is
a Phase 31 decision.

### 9. RLS + Realtime

`chart_documents` RLS mirrors the `patterns` RLS model:

- Owner may CRUD their own chart.
- Public-readable when the parent `patterns.visibility = 'public'`.

Added to `supabase_realtime` publication so the viewer can react to
peer edits (once Phase 37 multi-writer scenarios exist; pre-37 it
just lets a user's second device stay in sync).

### 10. Phase 29 scope boundary

Phase 29 delivers:

- Domain model + serialization + golden test
- SQLDelight migration + `PendingSync` enum extension
- Supabase migration 012 (`chart_documents`, RLS, Realtime, index)
- Local/Remote DataSource + mapper
- `StructuredChartRepository` (coordinator pattern, mirrors
  `PatternRepositoryImpl`)
- `SyncExecutor` extended to handle `STRUCTURED_CHART`
- 5 minimum UseCases: Get / Observe / Create / Update / Delete
- Koin wiring
- Headless "chart exists" indicator on `ProjectDetail` (Android + iOS)
  so that Phase 31 has an entry point to render

Phase 29 does NOT deliver:

- Canvas rendering (Phase 31)
- Editor UI / palette / tap-to-place (Phase 32)
- `SymbolCatalog` population (Phase 30)
- Per-cell progress overlay (Phase 34)
- Revision DAG table (Phase 37)
- Tag / symbol-level search indexes (Phase 36)

## Consequences

### Positive

- One table, one payload, one sync path — identical mental model to
  every other domain entity already in the codebase.
- `schema_version` + `storage_variant` give two independent forward-
  migration axes without destructive migrations later.
- Revision metadata present from day one avoids a painful backfill
  after the Phase 39 closed beta freeze.
- AI-imported very large charts (hundreds of rows) stay within the
  same table up to the practical Postgres `jsonb` row size. When they
  outgrow it, `storage_variant = 'chunked'` is the planned escape
  without schema reshape.

### Negative

- Whole-document upsert means a 1-cell edit rewrites the whole row.
  Acceptable for target chart sizes (est. ≤ 2000 cells / ~150 KB);
  revisited if telemetry shows pain.
- Sealed `ChartExtents` means two cases to maintain forever. Polar
  charts are a v1 requirement per the product vision, so flattening
  to a single rect type is not an option.
- Symbol IDs as opaque strings lose compile-time exhaustiveness. The
  Phase 30 `SymbolCatalog` is expected to provide a runtime registry
  plus lint-level checks.

### Neutral

- The existing `Pattern.chartImageUrls` photo-based field stays put.
  A pattern with both photo and structured chart is a valid state;
  the Phase 31 viewer will pick one per explicit UX decision.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Three normalized tables (chart/layer/cell) | Realtime doesn't carry chart context on cell UPDATE; editor mental model is whole-doc anyway |
| Postgres row-per-cell + per-cell Realtime | 1000s of rows per chart kills query and sync performance |
| Shared SVG per chart instead of structured cells | Loses symbol-level semantics needed for per-segment progress (Phase 34) and git-style diff (Phase 37) |
| Put structured chart into `patterns.chart_data jsonb` instead of a new table | Violates single-responsibility: patterns already carry unrelated metadata; also RLS semantics for public fork diverge |
| Start with enum `SymbolId` and migrate to string in Phase 35 | Forces JSON-boundary re-serialization across pre-release → closed beta — exactly the migration cost ADR-007 §5 warns against |

## Phase 30 addendum — Symbol catalog and parametric cells

Added on completion of Phase 30 to record decisions that were forward-looking
in the original ADR but have now been ratified in code.

### Symbol ID casing

- Multi-word segments use **kebab-case** (hyphens). Example:
  `jis.knit.k2tog-r`, `jis.knit.cable-2x2-l`.
- The JIS standard number is **not** embedded in the id. It lives in
  `SymbolDefinition.jisReference` so the id stays stable across JIS
  renumberings.
- `SYMBOL_ID_REGEX` widened from `^[a-z]+(\.[a-z0-9_]+)+$` to
  `^[a-z]+(\.[a-z0-9][a-z0-9_-]*)+$` to accept hyphens. Every reject case
  from the Phase 29 validator test still rejects.

### Parametric symbols

Some stitches carry a caller-supplied numeric label (cast-on n stitches,
bind-off n stitches, w&t row tag). Two additions:

- `SymbolDefinition.parameterSlots: List<ParameterSlot>` — slot key,
  unit-square anchor position, bilingual label, optional default.
- `ChartCell.symbolParameters: Map<String, String>` (JSON key
  `symbol_parameters`) — per-cell values. Default is an empty map so legacy
  cells decode unchanged.

Schema version stays at `1`. The new cell field is additive and
default-empty, so round-tripping through `chart_documents.document` jsonb
needs no migration and no `schema_version` bump. A future parameter-slot
*semantic* change (type system, validation, etc.) would justify bumping
to `2`.

### SVG path parser subset

The bundled commonMain parser supports
`M m L l H h V v C c S s Q q T t Z z`. Elliptical arcs (`A a`) are
deliberately excluded — no JIS L 0201 stitch needs one, and circles are
approximated with four cubic Béziers (already the de-facto vector-editor
export). Unsupported letters throw with an actionable message; commands
appearing without operands throw instead of silently dropping (guards
against truncated exports losing user strokes).

### Unit-square coordinate convention for symbols

All symbol paths are drawn in `viewBox 0 0 1 1` with **y-down** (matching
SVG). Renderers map the unit square onto the per-cell rect at draw time.
This is independent of `docs/en/chart-coordinates.md`'s y-up chart
coordinates: the chart layout is y-up (knitter convention), the symbol
interior is y-down (SVG convention). The boundary is the renderer
transform.

### Platform rendering plan

`PathCommand` is a sealed-interface IR in commonMain. Android will render
it with Compose `Path.moveTo / lineTo / cubicTo / quadTo / close`; iOS
SwiftUI does the same via SwiftUI `Path` APIs. No `expect/actual` is used
for the parser — parsing is pure logic. `expect/actual` remains reserved
for the Canvas drawing entry point, which lands in Phase 31.

## Phase 30.5 addendum — Symbol sources policy

Added after user feedback that in practice knitters do **not** treat JIS
L 0201 as a prescriptive standard. JIS is one of several reference
corpora, not the product's canonical authority.

### Sources recognised by the product

Ranked from "we ship it" to "user brings it":

1. **JIS L 0201:1995** — baseline reference for the default
   catalog. Ships under `jis.*` IDs. Rendering matches the JIS glyphs
   as published, not the myriad publisher variants.
2. **Major Japanese publisher houses** — 日本ヴォーグ社 and 文化出版局
   define de-facto variants that diverge from JIS (stroke weight,
   cross-over orientation, auxiliary ticks). These are *not* shipped
   under `jis.*`. When the default catalog ships an alternate glyph
   for the same stitch, the ID will be `std.<house>.<stitch>` (for
   example `std.vogue.k2tog-r`). None are in the Phase 30 default
   catalog.
3. **Craft Yarn Council** — reserved under `std.cyc.*`. Not shipped
   in Phase 30; adding CYC equivalents is a Phase 30.x task.
4. **User-defined symbols** — live under `user.<uuid>.*`. Authoring
   UI arrives in a later phase; the ID namespace and
   `CompositeSymbolCatalog` extension point are designed in now.
5. **Composite / derived symbols** — built from primitive strokes
   (vertical = knit, horizontal = purl, diagonal decrease direction,
   etc.). The Phase 30 catalog does **not** attempt to enumerate every
   composite; catalog completeness is explicitly a non-goal. Users
   will compose or import the long tail.

### Policy consequences

- **`jis.*` IDs stay stable** even when a major publisher's glyph
  differs, so a JA pattern that cites JIS keeps working. A publisher
  variant ships under a different ID, preserving round-trip fidelity
  with the source material.
- **Default catalog is not exhaustive** and never claims to be. The
  Phase 30 棒針 set ships the JIS-defined stitches; crochet / afghan /
  machine sets follow a similar scope. Stitches outside any recognised
  standard are out of scope for the default ship.
- **Conflicts defer to the user.** The `knitter` agent surfaces
  disagreement between sources; `product-manager` decides whether a
  variant earns a catalog slot; disputed symbols that neither party
  can resolve fall to the human user.
- **Catalog extension is non-breaking.** `SymbolCatalog` is an
  interface. A future `CompositeSymbolCatalog(default, user,
  imported)` can fan-in multiple catalogs without touching
  `DefaultSymbolCatalog` or the `jis.*` IDs.
- **Phase 30 visual verification is deferred to Phase 31.** Shipping
  more symbol sets before the viewer exists would compound any design
  bug across categories. The order is: Viewer → knitter review of the
  Phase 30 JIS set → decide which category (crochet / afghan / machine)
  expands next.

### Bilingual label handling

Every catalog entry carries JA and EN labels. When sources disagree on
the EN name (e.g., "k2tog" vs. "SSK" vs. "K2tog tbl" for mirror-image
decreases), the EN label records the CYC-preferred term and the
`aliases` field holds community variants. JA labels follow the JIS
canonical name unless a Nihon Vogue / Bunka convention is clearly
dominant in contemporary Japanese patterns.

## Addendum — Phase 30.1 review outcome (2026-04-18)

The Knitter-led visual review scheduled by this ADR ran in Phase 30.1.
Full findings are in [`docs/en/symbol-review/phase-30.1.md`](../symbol-review/phase-30.1.md).
Decisions codified here:

- **Next catalog category = `jis.crochet.*` (Phase 30.2).** Crochet
  scored 18/20 vs. afghan 8 and machine 11 on a four-factor rubric
  (commercial frequency, JIS/CYC coverage gap, user-segment unlock,
  implementation cost). JIS L 0201 Table 2 already specifies crochet
  symbols and the JP commercial pattern volume is the single largest
  audience segment the app can unlock in one step.
- **Geometry follow-up = Phase 30.1-fix (geometry-only PR).** ~16 of
  the 35 `jis.knit.*` glyphs carry at least one craft-correctness
  concern. The four most impactful are: (a) purl bar too wide
  (0.1→0.9; should be ~0.3→0.7 centered), (b) cable `over`/`under`
  not expressed (both diagonals are unbroken strokes, so right-over
  and left-over are visually identical), (c) SSK/k2tog/p2tog/k3tog
  direction glyphs drawn as symmetric inverted-V instead of JIS
  `stem + single slash`, and (d) `jis.knit.kfb` has a JA label
  (`ねじり増し目`) that actually names twisted-M1, not k-front-and-back.
  These are scheduled as a focused `KnitSymbols.kt` PR after the user
  answers the open questions in §5 of the review doc.
- **`DefaultSymbolCatalog` is intentionally non-exhaustive.** The
  Phase 30 catalog is a first pass; known publisher-specific variants
  (Vogue JP / Bunka / CYC-only glyphs) remain reserved under
  `std.<house>.*` / `std.cyc.*` / `user.*` per the original namespace
  policy and will land as overlays on top of the JIS core, not as
  edits to `jis.knit.*` entries.

This addendum does not change the data model or the symbol-id scheme
established in the body of this ADR.

## Addendum — Phase 30.1-fix: `std.cyc.kfb` and geometry corrections (2026-04-18)

The geometry-only follow-up promised by the Phase 30.1 review landed in
Phase 30.1-fix. Two things changed and are worth recording here because
they illustrate the namespace policy in practice:

- **First `std.cyc.*` entry shipped.** `CycSymbols.kt` now sits next to
  `KnitSymbols.kt` and is merged into `DefaultSymbolCatalog` by
  `KnitSymbols.all + CycSymbols.all`. The only entry today is
  `std.cyc.kfb`, added because JIS L 0201-1995 does not standardise a
  `kfb` glyph and the previous `jis.knit.kfb` entry therefore mis-
  advertised its provenance. CYC-only or publisher-specific glyphs will
  continue to land here rather than edit `jis.*` entries, per the
  Phase 30.5 symbol-sources policy.
- **Searchable aliases on `SymbolDefinition`.** `SymbolDefinition` gained
  an `aliases: List<String>` field. The Phase 30.1-fix PR populates
  exactly one entry: `jis.knit.twist-r` now carries `ねじり増し目` as an
  alias. This lets the dictionary search UI (Phase 30.2+) resolve the
  common JA term for a twisted-M1 increase to the correct JIS glyph
  without duplicating catalog entries. Aliases are not part of the
  rendered label pair and do not alter the symbol-id scheme.

Geometry changes (purl bar narrowing and base-anchoring; cable under-
stroke broken at the cell centre; decrease glyphs redrawn as
`stem + slash(es)` with the stem tail preserved below the crossing) are
described fully in the Phase 30.1 review doc §5 responses and remain
path-data edits inside `KnitSymbols.kt` — no data-model impact.

## References

- ADR-001: Supabase as backend
- ADR-003: Offline-first sync strategy
- ADR-004: Supabase schema v1
- ADR-007: Pivot to chart authoring
- `docs/en/chart-coordinates.md` (Phase 29)
- `docs/en/symbol-review/phase-30.1.md` (Phase 30.1)
- JIS L 0201:1995 編目記号 (reference corpus, not prescriptive)
- Craft Yarn Council chart symbol reference (reserved under `std.cyc.*`)
- 日本ヴォーグ社 / 文化出版局 house conventions (reserved under `std.<house>.*`)
