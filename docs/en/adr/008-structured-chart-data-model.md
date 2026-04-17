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

## References

- ADR-001: Supabase as backend
- ADR-003: Offline-first sync strategy
- ADR-004: Supabase schema v1
- ADR-007: Pivot to chart authoring
- `docs/en/chart-coordinates.md` (Phase 29)
- JIS L 0201:1995 編目記号
- Craft Yarn Council chart symbol reference
