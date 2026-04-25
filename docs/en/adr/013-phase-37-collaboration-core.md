# ADR-013: Phase 37 Collaboration Core (Commit History, Branch, Diff View)

## Status

Proposed

## Context

ADR-007 committed the project to Git-like collaboration on charts as a v1.0
differentiator. Phases 29–36 built the structured-chart spine
(ADR-008/009/010/011/012) with `revision_id` / `parent_revision_id` /
`content_hash` columns reserved on `chart_documents` from day one. Phase 36
became the first writer of a non-null `parent_revision_id` (fork seeds
`parentRevisionId = source.revisionId`). Phase 37 is the first reader: it
turns the lineage scaffold into a queryable history, then uses that history
to power a diff view and a branch model.

**The core blocker for Phase 37 is a data-model decision that Phase 36 did
not have to make.** Today `chart_documents` has `pattern_id UUID NOT NULL
UNIQUE` — at most one row per pattern. `StructuredChartRepository.update`
overwrites the row in place; the `revision_id` rotates on every save but
the previous revision is destroyed. There is no historical record. To
ship a commit history, either chart_documents must become append-only, or
a sibling table must hold the history.

Phase 37 MVP scope per ADR-007:

- **Commit history** — read-only timeline of revisions for a chart.
- **Branch** — minimum viable schema; "main" default branch always exists.
  Branch creation/checkout is a single sub-slice (37.4) and may slip out
  of MVP without breaking the rest of Phase 37.
- **Diff view** — visualize what changed between two revisions of the same
  chart.

Explicitly **not** in Phase 37 MVP (per ADR-007 "Minimal Git semantics; no
CRDT in v1"):

- Pull-request workflow with comments + approval (Phase 38).
- Three-way merge / conflict resolution UI (Phase 38).
- CRDT-based concurrent editing (post-v1 if ever).
- Cherry-pick, rebase, squash (out of scope, indefinitely).
- Branch protection rules / required-reviewers (out of scope).
- Multi-author diff blame ("who changed this cell"; Phase 38+).
- Fork-of-fork chain walk in attribution UI (Phase 36 ADR-012 §8 deferred).

Constraints carried forward:

- **ADR-008 §7:** `content_hash` is drawing-identity only; two saves whose
  drawing payload is byte-identical share a hash. Phase 37 commit history
  must tolerate hash collisions across non-adjacent revisions (a user who
  redoes a drawing they previously had ends up with two revisions sharing
  a hash but with different `revision_id`).
- **ADR-008 §6:** `revision_id` is a UUID — uniquely identifies a single
  saved point. Phase 37 uses `revision_id` as the canonical commit
  identifier.
- **ADR-010 §4:** `ProjectSegment` rows are project-scoped, not chart-scoped.
  Per-segment progress is **not** versioned alongside chart commits — a
  user's progress survives chart edits, and a chart commit does not snapshot
  segments. Phase 37 history is drawing-only.
- **ADR-012 §1:** `Pattern.parentPatternId` records pattern-level fork
  ancestry. Phase 37 commit lineage (chart_revisions parent chain) is
  **chart-level** and orthogonal — a single forked Pattern has one
  `parentPatternId` and an open-ended chain of `parent_revision_id`.
- **Phase 32 invariants:** `EditHistory` is per-editor-session in-memory
  undo/redo. It is NOT serialized and must NOT be confused with commit
  history. A user's undo stack disappears on app restart; their commit
  history persists.
- **ADR-007 §5 destructive-pre-release stance:** until Phase 39 closed beta
  freezes the schema, migrations may break local data. Phase 37 is free to
  reshape `chart_documents` semantics.

## Agent team deliberation

Convened once for the full ADR. Five interacting topics: append-only data
model, tip pointer, branch shape, diff algorithm, and UX surface.

### Voices

- **architect:** Append-only history must live in a sibling table, not by
  repurposing `chart_documents`. Today `chart_documents` is the tip pointer
  — every read path in the codebase (`getByPatternId`, `observeByPatternId`,
  `existsByPatternId`, fork's `forkFor`, sync's `RemoteStructuredChartDataSource`,
  Realtime channel `chart-documents-<ownerId>`) assumes one row per pattern.
  Dropping that UNIQUE breaks every consumer simultaneously and forces
  every query to re-add a "tip" filter. Adding a sibling `chart_revisions`
  table keeps the existing surface unchanged; only the write path grows
  the append step. Migration risk is bounded to one new table + one new
  trigger. The cost is ~12 columns of duplication between the two tables,
  which is fine — `chart_documents` becomes a denormalized "current state"
  cache of the latest `chart_revisions` row.

- **product-manager:** History as a separate screen, not a tab in
  ChartViewer. Tab-in-Viewer conflates "edit current state" with "browse
  past states" — two distinct mental models. A dedicated `ChartHistoryScreen`
  matches how every Git client treats history (a separate view), and it
  makes the diff view's entry point obvious (tap a revision in the list →
  see what changed). The history screen is reachable from ChartViewer's
  overflow menu and from ProjectDetail's pattern-info section (next to
  the existing "View structured chart" link).

- **knitter:** Commits should carry an optional message. Knitters pause
  at meaningful milestones — "finished the cuff", "reworked the cable
  panel", "added picots". Without a message, history reads as a list of
  timestamps which is not useful. Make the field optional (auto-save
  shouldn't pop a dialog) but surface it prominently in the editor's save
  flow. For MVP, message capture is on save only; editing a past commit's
  message is out of scope.

- **implementer:** The append-step has a failure mode that affects sync
  correctness. Today `update(chart)` is one local UPDATE + one remote
  upsert. With history, every save becomes: append local revision row +
  update local tip row + append remote revision row + update remote tip
  row. Without atomicity, a partial failure leaves the tip pointing at a
  revision that doesn't exist, or vice-versa. Two ways out:

  1. Server-side RPC `append_chart_revision(pattern_id, revision)` that
     does both INSERTs in one transaction.
  2. Append-then-update ordering on both sides + tolerate the brief window
     where the tip is stale (next sync round corrects it).

  (1) is correct but adds Supabase-side coupling that no other write path
  has today. (2) matches the existing PendingSync best-effort idiom.
  Recommend (2) for MVP; revisit if telemetry shows tip-divergence as a
  user-visible issue.

- **ui-ux-designer:** Diff view is visual side-by-side, not a textual
  changelog. Charts are inherently spatial; "5 cells modified" does not
  let a knitter understand the change. Use the existing `ChartCanvas`
  rendering at half-width on each side (or a stacked layout on narrow
  screens) with the changed cells highlighted in both panes —
  added/modified/removed get distinct colors that align with the per-segment
  overlay palette so users don't need to learn a new visual language. A
  small change-summary chip ("3 added · 2 modified · 1 removed") sits at
  the top for context but is not the primary affordance.

### Decision points resolved by the team

1. **Data model** → new `chart_revisions` append-only table; `chart_documents`
   stays as tip pointer with UNIQUE on `pattern_id` preserved (architect, strong).
2. **Tip pointer** → `chart_documents.revision_id` is the tip. Reads work
   unchanged (architect; implementer agrees).
3. **Branch table** → new `chart_branches(pattern_id, branch_name, tip_revision_id)`
   with `(pattern_id, branch_name)` UNIQUE. "main" auto-created on first
   revision. Reserved in 37.1; UI in 37.4 (architect; PM yields on MVP scope).
4. **Commit message** → optional `commit_message TEXT` column on
   `chart_revisions`, captured at save time. Editing past messages out of
   scope (knitter; PM agrees).
5. **Diff algorithm** → cell-level diff over `(layer.id, x, y)` triples;
   layer-property diffs (visibility, name, lock) tracked separately
   (ui-ux-designer; architect agrees).
6. **UX surface** → dedicated `ChartHistoryScreen`, not a tab in ChartViewer.
   Reachable from ChartViewer overflow + ProjectDetail (PM, strong).
7. **Atomicity** → append-then-update with PendingSync coalescing on
   failure; no Supabase RPC for MVP (implementer; architect agrees).

## Decision

### 1. Data model: append-only `chart_revisions` + tip-pointer `chart_documents`

Migration 015 creates `chart_revisions`:

```sql
CREATE TABLE IF NOT EXISTS public.chart_revisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pattern_id UUID NOT NULL REFERENCES public.patterns(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    schema_version INTEGER NOT NULL DEFAULT 1,
    storage_variant TEXT NOT NULL DEFAULT 'inline'
        CHECK (storage_variant IN ('inline', 'chunked')),
    coordinate_system TEXT NOT NULL
        CHECK (coordinate_system IN ('rect_grid', 'polar_round')),
    document JSONB NOT NULL,
    revision_id UUID NOT NULL,
    parent_revision_id UUID,
    content_hash TEXT NOT NULL,
    commit_message TEXT,
    author_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- revision_id is globally unique per ADR-008 §6 (the commit identifier).
    -- The standalone UNIQUE makes it a valid FK target for
    -- chart_branches.tip_revision_id (§7); the composite UNIQUE additionally
    -- guards against the same revision_id being attributed to different
    -- patterns (defense in depth — should be impossible by construction
    -- since revision_id is minted per save, but cheap to enforce).
    UNIQUE (revision_id),
    UNIQUE (pattern_id, revision_id)
);

CREATE INDEX IF NOT EXISTS idx_chart_revisions_pattern_id_created_at
    ON public.chart_revisions(pattern_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chart_revisions_revision_id
    ON public.chart_revisions(revision_id);
CREATE INDEX IF NOT EXISTS idx_chart_revisions_parent_revision_id
    ON public.chart_revisions(parent_revision_id)
    WHERE parent_revision_id IS NOT NULL;

ALTER TABLE public.chart_revisions ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can read own chart revisions"
    ON public.chart_revisions FOR SELECT
    USING (owner_id = auth.uid());

CREATE POLICY "Users can insert own chart revisions"
    ON public.chart_revisions FOR INSERT
    WITH CHECK (owner_id = auth.uid() AND author_id = auth.uid());
-- Note: `author_id` is nullable (ON DELETE SET NULL) so that revision rows
-- outlive author account deletion. The INSERT policy still requires
-- `author_id = auth.uid()` at insert time — null author_id only appears on
-- existing rows after the referenced profile is deleted, never on new rows.

CREATE POLICY "Public chart revisions readable"
    ON public.chart_revisions FOR SELECT
    USING (
        pattern_id IN (
            SELECT id FROM public.patterns WHERE visibility = 'public'
        )
    );

ALTER PUBLICATION supabase_realtime ADD TABLE public.chart_revisions;
```

Notes:

- **No UPDATE / DELETE policies.** Revisions are immutable once written.
  Editing or deleting a past commit is structurally forbidden by the
  absence of UPDATE/DELETE policies, mirroring Git's "history is
  append-only".
- **No `updated_at`.** A revision is created and never modified; the
  `created_at` is the commit timestamp.
- **`author_id` separate from `owner_id`.** Today they are always equal
  (single-author chart). Phase 38 PR/merge introduces revisions whose
  `author_id` differs from `owner_id` (a contributor's commit on an
  owner's chart). The column is provisioned now to avoid a Phase 38
  migration. **Nullable** with `ON DELETE SET NULL` so that revision
  rows outlive author account deletion (history outlives accounts).
  `author_id NOT NULL + ON DELETE SET NULL` would be a Postgres
  contradiction — the `SET NULL` action would fail to satisfy the
  `NOT NULL` constraint and account deletion would error rather than
  preserving the row. Domain model and SQLDelight schema mirror the
  nullable form.
- **`commit_message TEXT`** nullable. Auto-saves write null; explicit
  save-with-message writes the user-supplied string. UI surface is a
  single-line field at save time.

`chart_documents` is unchanged — it remains the tip pointer. The
write path becomes:

1. INSERT row into `chart_revisions` with the new `revision_id`.
2. UPDATE `chart_documents` row for the pattern, setting `document`,
   `revision_id`, `parent_revision_id`, `content_hash`, `updated_at` to
   the new revision.

Both INSERT and UPDATE flow through the existing PendingSync queue. Append
ordering is enforced locally by the use case, but if the network drops
between (1) and (2), the next sync round flushes both — temporary
divergence between local tip and local revisions is bounded to a single
session's pending queue.

### 2. SQLDelight mirror

`shared/src/commonMain/sqldelight/io/github/b150005/knitnote/db/ChartRevision.sq`:

```sql
CREATE TABLE ChartRevisionEntity (
    id TEXT NOT NULL PRIMARY KEY,
    pattern_id TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    storage_variant TEXT NOT NULL DEFAULT 'inline',
    coordinate_system TEXT NOT NULL,
    document TEXT NOT NULL,
    revision_id TEXT NOT NULL,
    parent_revision_id TEXT,
    content_hash TEXT NOT NULL,
    commit_message TEXT,
    author_id TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (pattern_id, revision_id)
);

CREATE INDEX IF NOT EXISTS idx_chart_revisions_pattern_id_created_at
    ON ChartRevisionEntity(pattern_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chart_revisions_revision_id
    ON ChartRevisionEntity(revision_id);
```

Queries: `getByRevisionId`, `getHistoryForPattern` (ORDER BY
`created_at DESC` LIMIT/OFFSET for pagination), `insert`, `deleteByPatternId`.
No `update` query — revisions are immutable.

### 3. Domain model

```kotlin
@Serializable
data class ChartRevision(
    val id: String,
    @SerialName("pattern_id") val patternId: String,
    @SerialName("owner_id") val ownerId: String,
    // Nullable: ON DELETE SET NULL on the Postgres FK to profiles, so a revision
    // row outlives the author account being deleted. INSERT-time RLS still
    // forces author_id = auth.uid(); null author_id only appears on
    // historically-written rows after the author account is removed.
    @SerialName("author_id") val authorId: String?,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("storage_variant") val storageVariant: StorageVariant,
    @SerialName("coordinate_system") val coordinateSystem: CoordinateSystem,
    val extents: ChartExtents,
    val layers: List<ChartLayer>,
    @SerialName("revision_id") val revisionId: String,
    @SerialName("parent_revision_id") val parentRevisionId: String?,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("commit_message") val commitMessage: String?,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("craft_type") val craftType: CraftType = CraftType.KNIT,
    @SerialName("reading_convention") val readingConvention: ReadingConvention = ReadingConvention.KNIT_FLAT,
)
```

`ChartRevision.toStructuredChart(): StructuredChart` extension reconstructs
a tip-shaped record for diff/preview rendering — every renderer in the
codebase consumes `StructuredChart`, so by routing revision render through
this conversion the existing `ChartCanvas` works unchanged.

`ChartRevision.extents` and `ChartRevision.layers` are deserialized from
the `document` JSON column using the same `StructuredChartMapper` path as
`StructuredChart` — they are NOT separate columns in the local DB or the
remote table. The `document JSONB` blob remains the canonical drawing
payload exactly as in `chart_documents`; the Kotlin model surfaces
`extents` and `layers` as first-class fields purely for ergonomic access
at the diff and rendering call sites.

### 4. Repository + UseCase

New repository:

```kotlin
interface ChartRevisionRepository {
    suspend fun getRevision(revisionId: String): ChartRevision?

    suspend fun getHistoryForPattern(
        patternId: String,
        limit: Int = 50,
        offset: Int = 0,
    ): List<ChartRevision>

    fun observeHistoryForPattern(patternId: String): Flow<List<ChartRevision>>

    suspend fun append(revision: ChartRevision): ChartRevision
}
```

`StructuredChartRepository.update` is extended to call
`chartRevisionRepository.append(...)` BEFORE the existing tip update.
Order is load-bearing: a remote sync of the tip update would fail RLS or
foreign-key checks if the revision row is not yet present. (No FK is
declared between `chart_documents.revision_id` and
`chart_revisions.revision_id` — the columns mirror but cross-table FKs in
this codebase are reserved for hard structural relationships; a soft mirror
is documented in the schema comment.)

New use cases:

```kotlin
class GetChartHistoryUseCase(
    private val repository: ChartRevisionRepository,
)

class GetChartRevisionUseCase(
    private val repository: ChartRevisionRepository,
)

class GetChartDiffUseCase(
    private val repository: ChartRevisionRepository,
) {
    suspend operator fun invoke(
        baseRevisionId: String,
        targetRevisionId: String,
    ): UseCaseResult<ChartDiff>
}
```

`ChartDiff` is the diff result envelope — see §5.

### 5. Diff algorithm

Cell-level diff with separate layer-property diff. Pseudocode:

```
fun diff(base: StructuredChart, target: StructuredChart): ChartDiff {
    val cellChanges = mutableListOf<CellChange>()
    val layerChanges = mutableListOf<LayerChange>()

    val baseLayersById = base.layers.associateBy { it.id }
    val targetLayersById = target.layers.associateBy { it.id }

    // Layer-level diff
    for (layerId in baseLayersById.keys + targetLayersById.keys) {
        val b = baseLayersById[layerId]
        val t = targetLayersById[layerId]
        when {
            b == null && t != null -> layerChanges += LayerAdded(t)
            t == null && b != null -> layerChanges += LayerRemoved(b)
            b != null && t != null && (b.name != t.name || b.visible != t.visible || b.locked != t.locked) ->
                layerChanges += LayerPropertyChanged(layerId, b, t)
        }
    }

    // Cell-level diff: for each layer present in both, key cells by (x, y).
    for (layerId in baseLayersById.keys.intersect(targetLayersById.keys)) {
        val baseCells = baseLayersById[layerId]!!.cells.associateBy { it.x to it.y }
        val targetCells = targetLayersById[layerId]!!.cells.associateBy { it.x to it.y }
        for (xy in baseCells.keys + targetCells.keys) {
            val bc = baseCells[xy]
            val tc = targetCells[xy]
            when {
                bc == null && tc != null -> cellChanges += CellAdded(layerId, tc)
                tc == null && bc != null -> cellChanges += CellRemoved(layerId, bc)
                bc != null && tc != null && bc != tc ->
                    cellChanges += CellModified(layerId, bc, tc)
            }
        }
        // Cells from layers added/removed entirely are captured in layerChanges, not cellChanges.
    }

    return ChartDiff(base, target, cellChanges, layerChanges)
}
```

Tradeoffs:

- **Position-keyed, not symbol-keyed.** Two cells at different coordinates
  with the same symbol_id are independent; a cell whose symbol changed
  in place is one `CellModified` (not paired add+remove). Knitters reading
  a diff care about "what's in this position now vs before" more than
  "which symbols moved".
- **Layer-renamed-and-edited** shows up as `LayerPropertyChanged` plus
  cell-level diffs against the same layer id. Handled cleanly because
  layer id is stable across renames.
- **Layer added/removed.** Cells in those layers are NOT enumerated in
  `cellChanges` — the layer-level change implies them. This avoids a
  blow-up when a 100-cell layer is added (one `LayerAdded` vs 100
  `CellAdded`). UX renders the entire layer as added/removed visually.
- **Polar charts diff identically.** The `(x, y)` key is `(stitch, ring)`
  on polar; same algorithm.
- **Parametric symbols.** `ChartCell` data class equality includes
  `symbolParameters`, so a parameter edit is a `CellModified`. Diff UI
  surfaces "parameter changed" as a sub-category of modification.
- **Wide cells (`cell.width > 1`).** The diff algorithm keys on the cell's
  top-left anchor `(x, y)`. The `(x, y)` key is the canonical address of
  any cell regardless of width. The diff renderer (37.3) must apply
  highlight rects across `[x, x + cell.width)` columns when rendering a
  `CellAdded` / `CellModified` / `CellRemoved` whose `cell.width > 1` —
  otherwise wide cells appear partially highlighted. Today the catalog
  contains `widthUnits=2` glyphs (`hdc-cluster-5`, `dc-cluster-5`,
  `picot-6`, `dc-crossed-2`); the renderer contract is enforced in
  `ChartDiffScreen` not in the algorithm itself.

Performance: O(N + M) where N, M are total cell counts in base and target.
For a 100×100 chart with 10K cells each, diff runs in well under a
millisecond on a modern device. No memoization needed for MVP.

### 6. UX: ChartHistoryScreen + ChartDiffScreen

Two new screens, both shared Compose + SwiftUI mirror.

**`ChartHistoryScreen(patternId)`:**

- Top bar with back navigation + pattern title.
- Vertical `LazyColumn` / `List` of revisions, newest first.
- Each row shows: commit message (or "Auto-save" if null), relative
  timestamp, author display name (when distinct from owner — Phase 37
  always shows owner = author, but the field is rendered for Phase 38
  forward-compat).
- Tap a revision → opens `ChartDiffScreen` showing diff between the
  tapped revision and its parent (if any) — or a "Initial commit" view
  if no parent.
- Long-press a revision → context menu with "Restore as new commit"
  affordance (pasted as a new revision on top of current tip; the past
  revision is not destructively reverted-to). **Restore is reserved for
  37.4** — listed here so the UX shape is consistent end-to-end, but the
  sub-slice that ships history list (37.2) leaves long-press unwired.
- testTags / accessibilityIdentifiers: `chartHistoryScreen` (root),
  `revisionRow_<revisionId>` (per-row), `commitMessageLabel_<revisionId>`,
  `revisionTimestampLabel_<revisionId>`.
- i18n keys (additive): `title_chart_history`, `state_no_chart_history`,
  `state_no_chart_history_body`, `label_auto_save`,
  `label_initial_commit`, `action_restore_revision` (reserved for 37.4),
  `dialog_restore_revision_title`, `dialog_restore_revision_body`.

Reachable from:

- `ChartViewerScreen` overflow menu → "View history".
- `ProjectDetailScreen` pattern-info section → existing
  "View structured chart" link gains a sibling "History" link when
  `pattern.parentPatternId == null || hasMultipleRevisions`.

**`ChartDiffScreen(baseRevisionId, targetRevisionId)`:**

- Top bar with back + summary chip ("3 added · 2 modified · 1 removed").
- Side-by-side `ChartCanvas` rendering at half-width on tablets / stacked
  on phones (responsive break at compact width class).
- Cell highlighting palette (aligns with per-segment overlay):
  - Added: `Color.Green` semi-transparent fill.
  - Removed: `Color.Red` semi-transparent fill.
  - Modified: `Color.Yellow` semi-transparent fill.
- Layer-property changes surfaced as a small banner above the canvas
  ("Layer 'Cable' renamed", "Layer 'Background' hidden").
- Pan/zoom synchronized across the two panes (same gesture registers on
  both; this is the harder UX engineering).
- testTags / accessibilityIdentifiers: `chartDiffScreen` (root),
  `diffSummaryChip`, `baseChartCanvas`, `targetChartCanvas`,
  `layerChangesBanner`.
- i18n keys (additive): `title_chart_diff`, `label_diff_summary`
  (parametric `%1$d added · %2$d modified · %3$d removed`),
  `label_layer_renamed` (parametric `%1$s → %2$s`),
  `label_layer_hidden`, `label_layer_shown`, `label_layer_locked`,
  `label_layer_unlocked`, `label_layer_added`, `label_layer_removed`,
  `state_no_changes`, `label_initial_commit`.

### 7. Branch model (reserved in 37.1, UI in 37.4)

Migration 015 also creates `chart_branches`:

```sql
CREATE TABLE IF NOT EXISTS public.chart_branches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pattern_id UUID NOT NULL REFERENCES public.patterns(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    branch_name TEXT NOT NULL,
    -- Hard FK to chart_revisions.revision_id (the commit id, globally unique
    -- per ADR-008 §6 and §1's standalone UNIQUE) with ON DELETE RESTRICT.
    -- Revisions are immutable by RLS (no DELETE policy), so RESTRICT is
    -- structurally safe and enforces the invariant that a branch tip cannot
    -- dangle. This is stricter than the soft mirror between
    -- `chart_documents.revision_id` and `chart_revisions.revision_id` (§4)
    -- because chart_branches is the entry point for SwitchBranchUseCase;
    -- a dangling tip would surface as a null chart with no recovery path
    -- when a forker tries to check out a deleted branch tip.
    tip_revision_id UUID NOT NULL REFERENCES public.chart_revisions(revision_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (pattern_id, branch_name)
);

CREATE INDEX IF NOT EXISTS idx_chart_branches_pattern_id
    ON public.chart_branches(pattern_id);

ALTER TABLE public.chart_branches ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can CRUD own chart branches"
    ON public.chart_branches FOR ALL
    USING (owner_id = auth.uid())
    WITH CHECK (owner_id = auth.uid());

CREATE POLICY "Public chart branches readable"
    ON public.chart_branches FOR SELECT
    USING (
        pattern_id IN (
            SELECT id FROM public.patterns WHERE visibility = 'public'
        )
    );

CREATE TRIGGER set_updated_at
    BEFORE UPDATE ON public.chart_branches
    FOR EACH ROW EXECUTE FUNCTION public.update_updated_at();
```

A "main" branch is auto-created by `StructuredChartRepository.create`'s
new wrapper `ensureDefaultBranch(patternId, ownerId, initialRevisionId)`
on the first save. Every subsequent save advances `chart_branches.tip_revision_id`
for the branch the user is currently on (Phase 37 MVP: always "main").

`StructuredChartRepository.forkFor` (Phase 36.2) is also updated in 37.1
to call `ensureDefaultBranch(newPatternId, newOwnerId, clonedRevisionId)`
after writing the cloned revision, so forked charts start with a 'main'
branch pointing at the cloned revision. Without this, forked charts would
have a populated `chart_revisions` history but an empty `chart_branches`
table, and the Phase 37.4 branch picker would surface no branches —
breaking switch / restore for forks.

Phase 37.4 surfaces:

- Branch picker in `ChartViewerScreen` overflow → "Switch branch".
- "New branch" affordance from the picker → creates a new `chart_branches`
  row with `tip_revision_id = current revision`.
- Checkout: switching branches updates `chart_documents.revision_id` to
  the target branch's tip + reloads the chart from
  `chart_revisions.document`. The tip pointer table is shared across
  branches; only one branch's tip is materialized in `chart_documents`
  at a time. This is the simplest model that makes "switch branch"
  observable to existing renderers without rewriting `chart_documents`
  semantics.

The single-tip simplification is a deliberate scope cut. Real Git tracks
HEAD and per-branch tips separately so you can have a branch checked out
without re-fetching its tip blob. We collapse that distinction because
the chart documents are small (jsonb document, typically <100KB) and
re-materializing on switch is sub-millisecond.

### 8. Sync wiring

`SyncEntityType.CHART_REVISION` added. `SyncExecutor` branch maps
INSERT to `remote.append(revision)`; UPDATE and DELETE not used (revisions
immutable). `SyncEntityType.CHART_BRANCH` added similarly with
INSERT/UPDATE/DELETE supporting branch creation, tip movement, and branch
deletion (Phase 37.4).

`RealtimeSyncManager` gains a 5th channel `chart-revisions-<ownerId>`
alongside projects / progress / patterns / project-segments. Owner-scoped
filter mirrors the per-segment-progress pattern (ADR-010 §6). When a peer
device appends a revision, the local cache catches up via the existing
"refetch on Realtime event" path. Specifically: on each
`chart-revisions-<ownerId>` INSERT event, `RealtimeSyncManager` decodes
the payload into a `ChartRevision` and writes it to
`LocalChartRevisionDataSource.insert(...)`. The SQLDelight insert triggers
the `observeHistoryForPattern` Flow, which `ChartHistoryViewModel` is
subscribed to — the history list updates live without a manual refetch.
Tip-pointer changes still flow through the existing
`chart-documents-<ownerId>` channel, so ChartViewer continues to receive
tip updates as it does today.

### 9. Phase 37 sub-slice plan

Bounded, mergeable independently. Each sub-slice ships its own commit, CI
verification, test delta, and code review.

| Slice | Scope | Test delta target | Migrations | i18n keys |
|---|---|---|---|---|
| **37.0** | This ADR (no code) | 0 | 0 | 0 |
| **37.1** | `chart_revisions` + `chart_branches` schema, domain `ChartRevision`, `ChartRevisionRepository` (local + remote + impl), `StructuredChartRepository.update` extended to append revisions, default-"main" branch auto-create on first save. Sync wiring for `CHART_REVISION` + `CHART_BRANCH`. | +25–35 commonTest | 015 | 0 |
| **37.2** | `GetChartHistoryUseCase`, `ChartHistoryViewModel`, `ChartHistoryScreen` (Compose + SwiftUI), entry points from ChartViewer overflow + ProjectDetail. **No diff view yet** — tapping a revision opens a placeholder. | +6–10 commonTest | 0 | 6–8 |
| **37.3** | `GetChartDiffUseCase`, `ChartDiffScreen` (Compose + SwiftUI), wire history-row tap to diff. Cell-level + layer-property diff algorithm. | +12–18 commonTest | 0 | 8–12 |
| **37.4** | Branch picker UI in ChartViewer overflow, "New branch" + "Switch branch" + restore-revision flows. `CreateBranchUseCase`, `SwitchBranchUseCase`, `RestoreRevisionUseCase`. | +10–15 commonTest | 0 | 6–8 |

Each sub-slice updates `CLAUDE.md`'s "Completed" section in the same
commit that ships it. Slice 37.4 is the only one that may slip out of MVP
without breaking earlier slices — 37.1/37.2/37.3 form a coherent read-only
history experience even if branch never ships.

### 10. Realtime + Discovery interaction

A public pattern's `chart_revisions` are publicly readable (RLS in §1).
Discovery does not surface history yet; the read-side is provisioned for
a future "view source's history" affordance from the attribution UI
(Phase 36.5 surfaced "Forked from" links; Phase 37 could deepen it to
"View source's history" via `getHistoryForPattern(sourcePatternId)` —
out of scope for this ADR, but the RLS already permits the read).

**Realtime is owner-scoped, not public-fan-out.** The
`chart-revisions-<ownerId>` channel filter matches only revisions where
`owner_id = <subscriber's auth.uid()>`, mirroring the
`project-segments-<ownerId>` pattern from ADR-010 §6. A forker does NOT
receive Realtime events when the source author appends a new revision
to the source pattern; the forker would have to explicitly poll
`getHistoryForPattern(sourcePatternId)` to observe upstream changes.
This is the safer MVP choice — it bounds Realtime fan-out to
self-authored work and avoids the "every public-pattern revision
broadcasts to N forkers" multiplier that public-fan-out would entail.
Phase 38 PR/merge introduces a "watch upstream" subscription mechanism
when cross-fork awareness becomes a feature requirement; that is the
right shape, not implicit Realtime fan-out via Phase 37 channel design.

### 11. Explicitly NOT in Phase 37 MVP

- Pull-request workflow with comments + approval (Phase 38).
- Three-way merge with conflict resolution UI (Phase 38).
- "Pull from upstream" affordance on a forked chart (Phase 38).
- Cherry-pick across branches (out of scope, indefinitely).
- Rebase / squash / amend (out of scope, indefinitely).
- Editing past commit messages.
- Branch protection rules / required-reviewers (out of scope).
- Multi-author diff blame (Phase 38+).
- Full fork-chain ancestry walk in attribution UI (ADR-012 §8 deferred).
- Server-side RPC for atomic revision-append + tip-update (revisit only
  if telemetry shows tip-divergence as a user-visible issue).
- CRDT-based concurrent editing (post-v1 if ever; ADR-007).
- Cached PNG thumbnails of revisions for the history list — reuse the
  Phase 36.4 live-render `ChartThumbnail` at smaller scale.
- `commit_message` validation / max length / formatting. MVP accepts any
  TEXT; UI enforces a soft 200-char limit on the input field.

## Consequences

### Positive

- The 35.x editor investment becomes a queryable timeline. Knitters can
  see "what did the cuff look like before I reworked it" — meaningful
  affordance that no other knitting app surfaces today.
- Phase 38 PR/merge starts with a populated chart_revisions graph from
  day one (mirrors Phase 36's "fork populated `parent_revision_id` so
  Phase 37 inherits ancestry" pattern). PR/merge builds against the same
  diff algorithm in §5 — no rewrite at the algorithm layer.
- Append-only revision storage matches Git's immutability invariant —
  enables forward-compat for branch protection, audit logs, and
  reproducible builds (Phase 38+).
- ADR-008 §7 `content_hash` finally has a non-trivial use case beyond
  drift detection: identifying duplicate revisions across the timeline
  (a "Restore" that lands you back on a previously-seen drawing) without
  re-storing the document.
- Diff view at the cell level matches the editor's mental model (every
  edit is a cell-level operation per Phase 32). Users see diffs in the
  same units they think in.

### Negative

- Storage cost grows linearly with edit count. A power user editing 200
  times produces 200 revision rows × ~50KB document each = ~10MB per
  pattern. Bounded but not free. Mitigation: Phase 37+ can add a
  retention policy (e.g. keep daily snapshots beyond N days) — explicitly
  not in MVP.
- Append-then-update is non-atomic in **two failure directions**:
  1. **Forward failure** (revision INSERT succeeds, tip UPDATE fails):
     Local state has the new revision in `chart_revisions` but
     `chart_documents.revision_id` still points at the previous revision.
     Next sync round retries the tip UPDATE (PendingSync coalesces).
     User-visible symptom: history list shows the new revision but
     ChartViewer still renders the old tip until reconnect.
  2. **Reverse failure** (remote revision INSERT fails, e.g. RLS token
     expiry mid-sync, but remote tip UPDATE succeeds): Remote
     `chart_documents.revision_id` points at a revision_id that does
     not exist in remote `chart_revisions`. A second device fetching
     `chart_documents` sees a tip it cannot resolve via `getRevision()`
     — the history list is empty even though the tip points somewhere.
     Recovery: PendingSync retains the failed revision INSERT and
     re-enqueues it on the next sync cycle; once it lands, the second
     device's history flow updates. The tip `revision_id` is the
     canonical source of truth and history is eventually consistent.
     There is no FK between `chart_documents.revision_id` and
     `chart_revisions.revision_id` (soft mirror per §4) so the tip
     UPDATE is not blocked at the database layer if the revision INSERT
     hasn't landed — this is by design but creates the temporary
     inconsistency window described above.

  Both failure modes are bounded to a single sync cycle. Atomic via a
  Supabase RPC is the alternative; deferred per the implementer's vote
  unless telemetry shows user-visible divergence as common.
- Diff algorithm is position-keyed, so a "moved" cell (same symbol at a
  new position) shows up as one add + one remove, not as a "moved"
  category. Knitters who reorganize a pattern's layout will see noisier
  diffs than they might expect. Phase 37+ can add move-detection as a
  post-process; MVP stays simple.
- Realtime channel count grows from 4 to 5 per authenticated user.
  Negligible per-channel cost on Supabase, but the cumulative connection
  budget on free tier is not infinite — if Phase 39 closed beta surfaces
  a cap, we coalesce channels then.
- ChartDiffScreen requires synchronized pan/zoom across two panes — non-
  trivial UX engineering in both Compose `transformableState` and SwiftUI
  `MagnificationGesture` + `DragGesture`. Risk surface for 37.3.

### Neutral

- `chart_documents` table unchanged (still UNIQUE on pattern_id). Every
  existing read path continues to work without modification.
- `Pattern` data model unchanged. Phase 36 `parentPatternId` and Phase 37
  `parentRevisionId` are orthogonal — pattern-level vs chart-level
  ancestry. No coupling between the two.
- `ProjectSegment` unchanged. Per-segment progress is project-scoped per
  ADR-010; chart commits do not snapshot segments. A user's progress
  survives chart edits.
- `EditHistory` (in-memory undo stack) unchanged. Per-session, not
  persisted. Distinct from commit history; ADR explicitly disambiguates
  to prevent confusion.
- Existing fork path (Phase 36.3) sets `parentRevisionId = source.revisionId`.
  After 37.1 lands, fork's chart-clone step also writes a row to
  `chart_revisions` with `parentRevisionId = source.revisionId` — so
  forks immediately have a 1-revision history. Fork's pattern attribution
  (`parentPatternId`) and chart's commit lineage (`parentRevisionId`)
  coexist on the cloned row. `forkFor` also calls `ensureDefaultBranch`
  per §7 so the forked pattern has a usable 'main' branch from the first
  load, not just a populated history.

## Considered alternatives

| Alternative | Pros | Cons | Why not chosen |
|---|---|---|---|
| Repurpose `chart_documents` as append-only (drop UNIQUE) | One table, less duplication; existing Realtime channel reused | Every existing read path needs explicit "tip" filter; Realtime emits on every revision insert; backfill of `is_tip` flag is a forward-compat trap | Sibling table localizes the migration risk; no read-path consumers change |
| Server-side RPC for atomic revision-append + tip-update | Strict consistency between revisions and tip | Couples KMP repos to Postgres-side semantics; no other write path uses RPCs; adds a new failure mode (RPC unreachable) | Best-effort idiom matches the rest of the codebase; revisit if telemetry shows divergence |
| Diff at layer level only ("layer X changed") | Coarser diff = simpler render | Knitters need to know which cells changed to evaluate the diff; layer-level is too coarse to be actionable | Cell-level matches the edit model |
| Diff at JSON-blob level ("document hash changed") | Trivial implementation | No semantic information; user can't see what changed | Useless as a UX |
| History as a tab inside ChartViewer | One screen, tighter integration | Conflates "edit current state" with "browse past states"; tab-switching loses scroll position; harder to deep-link to | Separate screen matches every Git client's affordance |
| Commit message required (not optional) | Forces meaningful history | Auto-saves can't have meaningful messages; pop-up at every save annoys users | Optional with auto-save default fits the actual workflow |
| Branch picker in 37.1 (not deferred to 37.4) | Whole feature lands at once | Scope blowup; 37.1 already touches schema + repo + sync — adding UI doubles the slice | Slice boundary keeps each PR reviewable |
| `chart_branches` deferred to 37.4 (not provisioned in 37.1) | 37.1 stays even smaller | Migration 015 is the natural place for `chart_branches`; provisioning later means migration 016 with similar shape — wasteful | Schema pieces ship together; UI ships separately |
| Diff colors driven by per-segment overlay palette | Consistent visual language across segment overlay + diff | Per-segment overlay uses primary/onSurface (semantic), diff uses traffic-light (categorical) — overloading the same palette confuses users | Distinct palette; diff is its own visual primitive |
| Synchronized pan/zoom on diff view skipped (independent panes) | Simpler implementation | Users can't compare specific cells across panes; defeats the diff use case | Synchronized pan/zoom is load-bearing for diff usability |
| No `author_id` column (single-author Phase 37) | Simpler MVP schema | Phase 38 PR/merge needs it; adding later requires a migration that backfills | Provision now to avoid Phase 38 migration |
| Restore-revision destructive (rewrites tip backwards) | Matches Git's `git reset --hard` | Loses revisions between restore target and current tip; no recovery; user expectation violated | Restore appends as a new revision (additive, non-destructive) |

## References

- ADR-007: Pivot to chart authoring (Phase 37 framed as collaboration core)
- ADR-008: Structured chart data model (`revision_id` / `parent_revision_id` /
  `content_hash` lineage scaffold; §7 content_hash is drawing-identity only)
- ADR-010: Per-segment progress (segments are project-scoped; not versioned)
- ADR-011: Phase 35 advanced editor (closed; Phase 37 reads its outputs)
- ADR-012: Phase 36 Discovery + fork (`parentRevisionId` first writer; Phase 37
  is first reader)
- Phase 32 Completed notes: editor MVP invariants (`EditHistory` is per-session,
  not persisted)
- `supabase/migrations/012_structured_chart.sql`: existing chart_documents schema
  and RLS that Phase 37 chart_revisions mirrors
