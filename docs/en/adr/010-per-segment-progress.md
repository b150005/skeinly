# ADR-010: Per-Segment Progress Data Model

## Status

Proposed

## Context

ADR-007 §Decision 2 reframed the row counter as "a progress pointer *within*
a chart." Phases 29–32 delivered the structured chart (data model, viewer,
editor MVP with parametric input, craft/reading metadata). The current
row-counter (`Project.currentRow` + `ProgressEntity` journal entries) is
chart-blind: it advances a single integer without knowing which stitch on
which layer is being worked.

Phase 34 must close that gap. A user opening a structured chart should be
able to mark an individual stitch (or segment) as `todo` / `wip` / `done`
and have the viewer visualise that state across devices. This is the
user-value loop that justifies the Phase 31/32 rendering work — without
it, the structured chart is a static document and the row counter remains
the only progress mechanism.

The design question this ADR resolves: **where does segment progress
live?**

Five forces apply:

1. **Cardinality.** A `Pattern` has 0..1 `StructuredChart`. A `Project`
   has-a `Pattern`. Multiple users may each have their own `Project` on
   the same (public) `Pattern` → the same `StructuredChart`. Progress is
   per-user-per-project and must be independent across users.
2. **Content-hash stability (ADR-008 §7).** `StructuredChart.contentHash`
   protects drawing identity for Phase 37 diff / collaboration. Progress
   mutation must not invalidate it.
3. **RLS shape.** A `StructuredChart` may be public (pattern.visibility =
   public). Progress on that chart must be private to each user. One row
   cannot carry both visibilities coherently.
4. **Realtime granularity.** Knitting interaction is typically
   one-stitch-at-a-time. Per-row Realtime events fit the UX; whole-chart
   events are noise and would fight the Phase 37 multi-writer evolution.
5. **AI-imported chart size (ADR-008 §Context).** Hundreds of rows × tens
   of stitches per row → potentially thousands of segments per chart.
   Storage must scale to "rows proportional to progress made," not
   "rows proportional to chart size."

## Decision

### 1. New `project_segments` table (scope: per-project, not per-chart)

Segment progress is stored as rows in a new domain table, scoped by
`project_id`, not by `chart_id`. This matches the project-scoped
cardinality of progress and keeps it orthogonal to chart-document
publishing.

Local (SQLDelight):

```sql
CREATE TABLE ProjectSegmentEntity (
    id TEXT NOT NULL PRIMARY KEY,
    project_id TEXT NOT NULL,
    layer_id TEXT NOT NULL,
    cell_x INTEGER NOT NULL,
    cell_y INTEGER NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('wip', 'done')),
    updated_at TEXT NOT NULL,
    owner_id TEXT NOT NULL DEFAULT 'local-user',
    FOREIGN KEY (project_id) REFERENCES ProjectEntity(id) ON DELETE CASCADE,
    UNIQUE(project_id, layer_id, cell_x, cell_y)
);
CREATE INDEX idx_project_segments_project_id ON ProjectSegmentEntity(project_id);
CREATE INDEX idx_project_segments_owner_id ON ProjectSegmentEntity(owner_id);
```

Remote (Supabase `migrations/013_project_segments.sql`):

```sql
CREATE TABLE public.project_segments (
    id TEXT PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES public.projects(id) ON DELETE CASCADE,
    layer_id TEXT NOT NULL,
    cell_x INT NOT NULL,
    cell_y INT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('wip', 'done')),
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(project_id, layer_id, cell_x, cell_y)
);
```

Note on `id TEXT` vs the "UUID" shape used by other tables: §3 below
resolves this by choosing a deterministic synthetic id
(`seg:<projectId>:<layerId>:<x>:<y>`) over a server-assigned UUID.
The column type is therefore `TEXT` end-to-end (client and server) to
avoid round-trip format conversion. The draft of this ADR had `UUID`
here until §3 landed; kept as `TEXT` consistently in the shipped
Phase 34 migration.

RLS: owner-only CRUD. No public-read policy — progress is always private.
Even when the parent `Pattern` is public, each user's progress rows are
isolated to their own `owner_id`.

Added to `supabase_realtime` publication so the second device receives
per-stitch updates.

### 2. State enum: `todo` is implicit (absence = todo)

```kotlin
@Serializable
enum class SegmentState {
    @SerialName("wip") WIP,
    @SerialName("done") DONE,
}
```

A row exists only for segments the user has acted on. Segments with no
row are `todo` by default. This keeps table size **proportional to
progress made, not chart size** — addresses Force 5.

Consequence: "reset to todo" is implemented as row deletion, not a row
update to `state = 'todo'`. Simpler sync semantics, smaller wire payload,
no phantom-row lifecycle.

### 3. Segment identity: synthetic id + unique tuple

Primary key is a synthetic string `id` (UUID or deterministic from the
tuple). A composite `UNIQUE(project_id, layer_id, cell_x, cell_y)`
constraint guarantees at most one row per segment per project.

Synthetic id is required because `PendingSync.entity_id` is `TEXT` and
existing `SyncExecutor` parameterises on a single id. Deterministic id
is chosen — `"seg:<projectId>:<layerId>:<x>:<y>"` — because:

- Deterministic ids collapse double-tap idempotency into the existing
  `PendingSync` `(entity_type, entity_id)` coalescing without needing a
  DB round-trip to resolve "is this a new row?"
- Same id on local + remote simplifies the upsert-or-insert choice in
  `SyncExecutor`.

### 4. Coordinate convention matches `ChartCell`

`cell_x` / `cell_y` use the same convention as `ChartCell.x` / `y` —
y-up, bottom row = y=0 (per ADR-008 §5 and `docs/en/chart-coordinates.md`).

For `CoordinateSystem.POLAR_ROUND`, the `ChartCell.x/y` fields currently
encode `(ring, position)` or equivalent. `ProjectSegmentEntity.cell_x/y`
carry the same values without reinterpretation. Polar segment UX is
deferred to Phase 35 (advanced editor); Phase 34 ships rect-grid support.

### 5. Sync wire protocol: same path as every other entity

- `PendingSync.EntityType` gains `PROJECT_SEGMENT`.
- Toggle segment state → `local.upsert(segment)` → `syncManager.syncOrEnqueue(PROJECT_SEGMENT, id, UPSERT, json)`.
- Reset segment to todo → `local.deleteById(segment.id)` → `syncManager.syncOrEnqueue(PROJECT_SEGMENT, id, DELETE, "")`.
- `PendingSync` existing coalescing on `(entity_type, entity_id)` means
  rapid double-taps on the same segment collapse before network.
- `RemoteSyncOperations` gains a `ProjectSegment` branch in the existing
  upsert / delete switch (no new infra).

Offline-first: same pattern as `Progress`, `Pattern`, etc.

### 6. Realtime subscription scope

- Subscribed when a `Project` with a linked `StructuredChart` is opened.
- Filter: `owner_id = auth.uid()` (RLS-aligned).
- No chart-document-level filter needed at the client — RLS already
  scopes server-side.
- Existing `RealtimeChannelProvider` wiring; new channel alongside the
  3 current ones (projects / progress / patterns).

### 7. `contentHash` is unchanged

Per ADR-008 §7 and the Phase 32.1 addendum, `contentHash` protects
drawing identity only. Progress lives in a separate table and does not
touch the chart document; the invariant carries forward trivially.

### 8. Domain layer shape

```kotlin
@Serializable
data class ProjectSegment(
    val id: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("layer_id") val layerId: String,
    @SerialName("cell_x") val cellX: Int,
    @SerialName("cell_y") val cellY: Int,
    val state: SegmentState,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("updated_at") val updatedAt: Instant,
)

interface ProjectSegmentRepository {
    fun observeByProjectId(projectId: String): Flow<List<ProjectSegment>>
    suspend fun getById(id: String): ProjectSegment?
    suspend fun getByProjectId(projectId: String): List<ProjectSegment>
    suspend fun upsert(segment: ProjectSegment): ProjectSegment
    suspend fun resetSegment(id: String)
    suspend fun resetProject(projectId: String)
}
```

Repository-layer return-type note (Phase 34 amendment): the shipped
interface uses plain-throw suspend functions rather than `Result<T>`,
matching the existing codebase convention (`StructuredChartRepository`,
`ProgressRepository`, `PatternRepository` all do the same). `Result<T>`
wrapping lives at the use-case layer via the existing
`UseCaseResult<T>` + `Throwable.toUseCaseError()` scaffold — this keeps
the repository interface composable and avoids double-wrapping errors.

`resetSegment` takes a flat deterministic `id: String` rather than the
decomposed `(projectId, layerId, cellX, cellY)` tuple. The caller
(viewmodel or use case) already resolves the id via
`ProjectSegment.buildId(...)`, so the tuple-decomposed API would
require either an extra `buildId` call on every site or a duplicate
signature. This is a minor simplification from the original §8 draft
above.

UseCases (Phase 34 minimum):

1. `ObserveProjectSegments(projectId)` — Flow for viewer overlay.
2. `ToggleSegmentState(projectId, layerId, x, y)` — user tap: todo → wip → done → todo (cyclic).
3. `MarkSegmentDone(projectId, layerId, x, y)` — explicit "done" for long-press / sweep.
4. `ResetProjectProgress(projectId)` — clear all segments for a project.

The "current working position" pointer used by the existing row-counter
UI is derived at the viewmodel layer (first `wip` segment, else first
`todo` segment in reading-order per `StructuredChart.readingConvention`),
not stored. This avoids a "pointer-vs-states" consistency bug class.

### 9. Legacy row-counter coexistence

`Project.currentRow` + `ProgressEntity` notes journal **stay**. They are
orthogonal:

- `currentRow` / journal: row-level narrative ("started row 12,
  photographed the cable crossing"). Remains useful without a chart.
- `ProjectSegment`: stitch-level state for projects with a linked
  `StructuredChart`.

A project with no linked chart behaves exactly as it does today.

### 10. Phase 34 scope boundary

Phase 34 delivers:

- Domain model + serialization.
- SQLDelight migration + `PendingSync` enum extension.
- Supabase migration 013 (`project_segments`, RLS, Realtime, indexes).
- Local/Remote DataSource + mapper + coordinator repository.
- `SyncExecutor` extended for `PROJECT_SEGMENT`.
- 4 UseCases above + viewmodel state shape.
- Chart viewer per-segment overlay (Compose + SwiftUI): tap toggles, long-press sets done.
- Progress-reset action on the project detail screen.

Phase 34 does NOT deliver:

- Polar-chart segment UX (Phase 35).
- "Mark entire row done" batch ops (Phase 35).
- Progress export / share (Phase 36).
- Multi-writer segment conflict resolution (Phase 37+).
- Undo/redo of progress toggles (out of scope — progress is "what I did,"
  not a document edit history).
- Chart author's "suggested stitch order" overlay (Phase 35+).

## Consequences

### Positive

- Chart document stays drawing-only; `contentHash` invariant holds;
  Phase 37 diff semantics stay clean.
- Progress is per-user-per-project, matching the real cardinality
  without cross-user data leakage when patterns are shared.
- Absence-equals-todo keeps storage proportional to progress made, not
  chart size — AI-imported giant charts incur zero baseline cost.
- Sync path reuses every primitive already proven in Phase 3b+ / 14 /
  15 (PendingSync, SyncExecutor, RealtimeChannelProvider). Zero new
  infra.
- Deterministic segment id folds double-tap idempotency into existing
  coalescing without DB round-trips.

### Negative

- New table, new sync branch, new Realtime channel = ~400 LOC of
  mechanical wiring per the Phase 29 template.
- Polar segment UX deferred means Phase 34 ships incomplete for round
  charts (amigurumi / doilies). Acceptable because Phase 35 is the polar
  editor's home anyway.
- Deterministic id format (`seg:<projectId>:<layerId>:<x>:<y>`) leaks
  structure. Acceptable because the id is never user-visible and the
  structure enables offline coalescing; documented inline.
- "Reset to todo = DELETE" means a Realtime subscriber can see a row
  disappear without seeing it first appear (a todo→done→reset sequence
  on a peer device emits INSERT+UPDATE+DELETE). Handled client-side by
  making the overlay's local state a `Map<SegmentKey, SegmentState>`
  where absence = todo — same truth model as the storage layer.

### Neutral

- Existing `Progress` / `ProgressEntity` journal semantics unchanged.
- `Project.currentRow` row-counter pointer unchanged.
- Chart-level "% complete" becomes computable as
  `done_count / total_cells_in_visible_layers` but is not surfaced in
  Phase 34 UX (follow-up polish).

## Alternatives Considered

| Alternative | Pros | Cons | Why Not Chosen |
|---|---|---|---|
| Inline segment state on `ChartCell` (extend `StructuredChart` document) | Single sync path; atomic viewer refresh | Cardinality wrong — chart is pattern-scoped, progress is project-scoped; breaks `contentHash` invariant; breaks RLS (public chart + private progress cannot coexist on one row); AI-imported giant charts bloat further | Structural mismatch; kills Phase 37 diff |
| Parallel `progressLayers: List<ChartLayer>` array in chart document | Keeps cells separate from state | Same cardinality and RLS problems as above; still invalidates `contentHash`; still couples progress to chart publish cycle | Same |
| Separate table keyed by `chart_id` instead of `project_id` | Cleaner relationship to the chart being annotated | Misses the user-project-scoped cardinality; Phase 36 fork would then also fork progress (wrong) | Project is the right scope for user progress |
| Composite primary key `(project_id, layer_id, cell_x, cell_y)` (no synthetic id) | Normalised; no redundant id | `PendingSync.entity_id` is a single TEXT column; composite key forces schema change or an encode/decode layer that duplicates the deterministic-id idea anyway | Deterministic synthetic id is the same information, compatible with existing sync |
| Three-state enum with `todo` explicit | Symmetric storage | Storage scales with chart size, not progress made; AI-imported giant charts carry thousands of "todo" rows per project from day one | Absence-equals-todo is the storage-efficient form |
| Event-log table (append-only state transitions) | Phase 37-ready history for free | Querying "what is segment X's state right now?" becomes an aggregate; defers complexity that Phase 34 doesn't need; inconsistent with the current-state shape of every other sync entity | Phase 37 will layer history on top when commits arrive; Phase 34 stores current state |

## References

- ADR-007: Pivot to structured chart authoring (frames the progress loop as core)
- ADR-008: Structured chart data model (contentHash invariant, RLS shape, coordinate convention)
- ADR-003: Offline-first sync strategy (PendingSync coalescing, syncOrEnqueue)
- ADR-004: Supabase schema v1 (RLS policy template)
- `docs/en/chart-coordinates.md` (y-up chart coordinate convention)
- Phase 1.5 `ProgressRepository` (journal semantics — preserved, not replaced)
