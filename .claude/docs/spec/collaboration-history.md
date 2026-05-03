# Spec — Collaboration / History / Branch / Diff

> **Purpose**: stable feature-organized view of the chart commit-history, branching, diff, and revision-restore surfaces as they exist in main today. Describes the *what*; ADR-013 carries the *why*.
>
> **Audience**: an agent extending history / branch / diff surfaces, or building atop the append-only revision spine.
>
> **Scope**: chart_revisions append-only spine, ChartHistoryScreen, ChartBranchPickerSheet, ChartDiffScreen, RestoreRevision flow, branch CRUD. Out of scope: pull-request workflow ([pull-request-flow.md](pull-request-flow.md)), chart editor itself ([chart-editor.md](chart-editor.md)).

## Current shape

### Data spine

**Postgres tables** (migration [015_chart_revisions.sql](../../../supabase/migrations/015_chart_revisions.sql)):

| Table | Role |
|---|---|
| `chart_revisions` | Append-only revision history. Composite PK `(pattern_id, revision_id)`. Standalone UNIQUE on `revision_id` (per ADR-013 §1, the canonical commit identifier referenced as a FK target by `chart_branches.tip_revision_id`, `pull_requests.source_tip_revision_id` / `common_ancestor_revision_id` / `merged_revision_id`). FK `parent_revision_id` references `revision_id` (single-parent linked list). `author_id` is FK to users (NOT users.id PK — `auth.users(id)` references the Supabase auth schema). `content_hash` deterministic over JSON of extents + layers. `document` JSONB stores the snapshot. No DELETE policy — only CASCADE on pattern deletion |
| `chart_branches` | Mutable branch tip pointers. PK `(pattern_id, name)`. `tip_revision_id` FK to `chart_revisions(revision_id)` |
| `chart_documents` | Single-row-per-pattern view of "current tip on the default branch". Synced server-side by triggers; client reads as-if a flat field on `Pattern` |

**Critical invariants (ADR-013 §1)**:

- `chart_revisions` is **append-only**. No UPDATE policy exists. The Phase 37 `RestoreRevisionUseCase` does not edit a past revision — it appends a new revision whose `document` mirrors the restored one but `parent_revision_id = current.tip_revision_id`.
- `author_id != owner_id` is **only legitimately produced by the merge_pull_request RPC** (see [pull-request-flow.md](pull-request-flow.md)). All non-RPC client writes set `author_id = owner_id`.

### File map

**Shared module — `commonMain`**

| Path | Role |
|---|---|
| [shared/.../domain/model/ChartRevision.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/model/ChartRevision.kt) | `ChartRevision`, `ChartBranch` data classes |
| [shared/.../domain/repository/ChartRevisionRepository.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/repository/ChartRevisionRepository.kt) | Append + getHistoryForPattern + getRevision + observeForPattern |
| [shared/.../domain/repository/ChartBranchRepository.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/repository/ChartBranchRepository.kt) | listForPattern + create + switchTo + delete |
| [shared/.../domain/repository/StructuredChartRepository.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/repository/StructuredChartRepository.kt) | Tip-pointer reader/writer (ports-and-adapters port) |
| [shared/.../domain/chart/ChartDiffAlgorithm.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/chart/ChartDiffAlgorithm.kt) | Cell-level + layer-level diff. `diff(base, target): ChartDiffReport` |
| [shared/.../data/local/LocalChartRevisionDataSource.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/local/LocalChartRevisionDataSource.kt) | SQLDelight cache |
| [shared/.../data/local/LocalChartBranchDataSource.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/local/LocalChartBranchDataSource.kt) | Same |
| [shared/.../data/remote/RemoteChartRevisionDataSource.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/remote/RemoteChartRevisionDataSource.kt) | Supabase append (uses `ignoreDuplicates = true` — re-enqueued append silently no-ops since revisions are immutable) |
| [shared/.../data/remote/RemoteChartBranchDataSource.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/remote/RemoteChartBranchDataSource.kt) | Branch CRUD remote |
| Use cases — `domain/usecase/`: | |
| `GetChartHistoryUseCase.kt` | Suspend invoke + observe Flow. Returns walked parent chain (newest → oldest) |
| `GetChartRevisionUseCase.kt` | Single-revision lookup by id |
| `GetChartDiffUseCase.kt` | Resolves base + target revisions, runs `ChartDiffAlgorithm.diff` |
| `RestoreRevisionUseCase.kt` | Appends a new revision whose document = restored snapshot, `parent_revision_id = current_tip_revision_id`. Updates `chart_branches.tip_revision_id` |
| `GetChartBranchesUseCase.kt` | listForPattern + observe Flow |
| `CreateBranchUseCase.kt` | New branch from current tip. Validates name (non-blank, ≤ 100 chars, unique within pattern) |
| `SwitchBranchUseCase.kt` | Updates the local + remote chart_documents tip pointer to the target branch's tip |
| ViewModels — `ui/chart/`: | |
| `ChartHistoryViewModel.kt` | Parametric on `patternId`. Observes `getChartHistory.observe()` |
| `ChartDiffViewModel.kt` | Parametric on `(patternId, baseRevisionId, targetRevisionId)`. Loads + diffs in `init` |
| `ChartBranchPickerViewModel.kt` | Parametric on `patternId`. List + create + switch + delete |
| Compose screens — `ui/chart/`: | |
| `ChartHistoryScreen.kt` | LazyColumn of revisions. Tap → diff screen |
| `ChartDiffScreen.kt` | Two-pane Canvas with `rememberTransformableState` (zoom + pan). Traffic-light coloring (red removed / green added / yellow modified) |
| `ChartBranchPickerSheet.kt` | Bottom sheet. Branch list + new-branch form + per-row switch action |

**iOS — `iosApp/iosApp/Screens/`**

| Path | Role |
|---|---|
| `ChartHistoryScreen.swift` | SwiftUI mirror |
| `ChartDiffScreen.swift` | SwiftUI mirror with MagnificationGesture + DragGesture for pan/zoom |
| `ChartBranchPickerSheet.swift` | SwiftUI sheet mirror |

**Tests — `commonTest`**

- `ChartRevisionRepositoryImplTest` — append idempotency + sync enqueue + Realtime echo
- `ChartDiffAlgorithmTest` — cell + layer diff matrix (rect + polar parity)
- `RestoreRevisionUseCaseTest` — verifies append-not-overwrite contract
- `GetChartHistoryUseCaseTest`, `GetChartDiffUseCaseTest`, `GetChartBranchesUseCaseTest`, `CreateBranchUseCase` + `SwitchBranchUseCase` tests
- `ChartHistoryViewModelTest`, `ChartDiffViewModelTest`, `ChartBranchPickerViewModelTest`

### Domain entry points

**View history** — `GetChartHistoryUseCase(patternId)`. Suspend invoke for cold-launch seed + `observe(patternId, scope)` Flow for live updates as Realtime appends arrive. Walks `parent_revision_id` chain newest→oldest. Returns `List<ChartRevision>` with author display name resolved via `UserRepository.getByIds`.

**View revision detail** — `GetChartRevisionUseCase(revisionId)`. Single-revision fetch. Used by the diff screen + restore flow.

**View diff** — `GetChartDiffUseCase(baseRevisionId, targetRevisionId)`. Loads both revisions, deserializes `document` JSON into `StructuredChart`, runs `ChartDiffAlgorithm.diff(base, target)`. Returns `ChartDiffReport(cellAdditions, cellRemovals, cellModifications, layerAdditions, layerRemovals, layerPropertyChanges)`.

**Restore a revision** — `RestoreRevisionUseCase(patternId, revisionId)`. Appends a new revision (mints new `revisionId = Uuid.random()`, sets `parent_revision_id = current_tip`). Returns the new revision id. Updates the default-branch tip pointer.

**Branch CRUD** — `GetChartBranchesUseCase(patternId)` (observe + invoke), `CreateBranchUseCase(patternId, name)`, `SwitchBranchUseCase(patternId, branchId)`. Branch deletion is exposed through repository directly (no use case yet).

### Realtime channels

`RealtimeSyncManager` runs 5 owner-scoped channels for the chart-history surface (Phase 37.1):

| Channel | Filter |
|---|---|
| `chart-revisions-<ownerId>` | `ChangeFilter("owner_id", ownerId)` — INSERTS only (revisions immutable). Handler routes to `LocalChartRevisionDataSource.upsert` (idempotent via INSERT-OR-REPLACE on `revision_id`) |
| `chart-branches-<ownerId>` | `ChangeFilter("owner_id", ownerId)`. Handler routes INSERT/UPDATE/DELETE |
| `chart-documents-<ownerId>` | `ChangeFilter("owner_id", ownerId)`. Handler updates the local `chart_documents` cache |
| `patterns-<ownerId>` | Pre-existing |
| `projects-<ownerId>` | Pre-existing |

(+ 2 more channels added by Phase 38.1 for pull requests; see [pull-request-flow.md](pull-request-flow.md).)

### Invariants (load-bearing — DO NOT BREAK)

1. **Append-only chart_revisions**: no UPDATE policy. RestoreRevision appends, never overwrites. Verified by `RestoreRevisionUseCaseTest`.

2. **Single-parent linked list**: `chart_revisions.parent_revision_id` is single-valued. Multi-parent merge-commit was rejected for v1 (see [pull-request-flow.md](pull-request-flow.md) — fast-forward strategy detection still produces a 1-parent commit).

3. **`revision_id` is the canonical commit identifier**: composite PK is `(pattern_id, revision_id)`, but `revision_id` carries a standalone UNIQUE so other tables FK against it directly without joining through `pattern_id`. Per ADR-013 §1 — referenced by `chart_branches.tip_revision_id`, `pull_requests.source_tip_revision_id` / `common_ancestor_revision_id` / `merged_revision_id`.

4. **`author_id` semantics**: client writes always set `author_id = owner_id`. The merge_pull_request RPC is the only legitimate divergence. Multi-author diff blame is a post-v1 affordance built atop populated `author_id`.

5. **Realtime append idempotency**: `RemoteChartRevisionDataSource.append` uses `ignoreDuplicates = true`. The local SQLDelight `upsert` uses INSERT-OR-REPLACE on `revision_id`. A re-enqueued PendingSync append silently no-ops on both sides.

6. **`ChartDiffAlgorithm` purity**: the algorithm is pure (no I/O). It is reused by:
   - `GetChartDiffUseCase` (current screen)
   - `ConflictDetector` for 3-way merge ([pull-request-flow.md](pull-request-flow.md))
   - Any future "preview merge" affordance

7. **Polar diff coordinate parity**: rect diff uses `(x, y)` cell keys; polar uses `(stitch, ring)` (also stored as `(x, y)` semantically). The diff algorithm doesn't know the difference — it operates on `(layerId, x, y)` keys uniformly.

8. **E2E load-bearing testTags**:
   - `ChartHistoryScreen`: `chartHistoryScreen` (root), `revisionRow_<revisionId>`
   - `ChartDiffScreen`: `chartDiffScreen` (root), pinch-zoom canvas region (no testTag — gestural)
   - `ChartBranchPickerSheet`: `chartBranchPickerSheet`, `branchRow_<branchId>`, `createBranchButton`, `switchBranchButton_<branchId>`

## Extension points

### Multi-author diff blame ("who last touched this cell")

Post-v1. Requires:
- Populated `author_id` on every `chart_revisions` row (already happens for non-merge writes; merge writes carry author from the PR).
- Per-cell author lookup: walk parent_revision_id chain backwards from the tip until the cell's `(layerId, x, y)` first appears with a different value.
- UI: hover-to-reveal author name on a cell in `ChartViewerScreen` (or in `ChartDiffScreen` per pane).

### Adding a new diff visualization mode

`ChartDiffScreen` currently uses traffic-light coloring (red/green/yellow). To add e.g. side-by-side cell-level overlays:
- `ChartDiffViewModel` already exposes `state.diffReport: ChartDiffReport`.
- Add a `DiffRenderMode` enum + state field; toggle via toolbar.
- Render path lives in private composables in [ChartDiffScreen.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/chart/ChartDiffScreen.kt).

### Adding "compare with main" affordance

The diff screen takes explicit `baseRevisionId` + `targetRevisionId`. To add a "compare current with main" shortcut:
- Resolve `chart_branches.tip_revision_id WHERE name = 'main'` for the pattern.
- Route through `navigate(ChartDiff(baseRevisionId = mainTip, targetRevisionId = currentTip))`.

### Pre-merge preview

The `ConflictDetector` algorithm wraps `ChartDiffAlgorithm.diff`. A future "preview merge" surface (showing what the merge would produce before clicking Apply) reuses the same algorithm — feed `(ancestor, theirs, mine)` and render the resolved document via the existing chart canvas.

## Deferred / known limitations

| Item | Pointer |
|---|---|
| **Multi-author diff blame** — post-v1 | ADR-013 §10 / ADR-014 §10 |
| **Cherry-pick / rebase / squash-with-fixup / amend** — post-v1 | ADR-014 §10 |
| **CRDT concurrent editing** — post-v1 | ADR-013 §10 |
| **Branch deletion in UI** — repo method exists; no use case wired yet | Add when needed |
| **`ChartDiffScreen` zoom does not have a "fit to screen" reset** — pan/zoom is cumulative; user must double-tap or pinch-out manually | Polish; reopen if beta feedback surfaces it |
| **Branch protection / required approvals** — relevant only at merge time, not at branch level | See [pull-request-flow.md](pull-request-flow.md) |

## ADR + archive references

**ADRs**:

| ADR | File | Scope |
|---|---|---|
| ADR-007 | [docs/en/adr/007-pivot-to-chart-authoring.md](../../../docs/en/adr/007-pivot-to-chart-authoring.md) | Pivot from v1 store submission to structured chart authoring; opens the Phase 29–40 workstream |
| ADR-013 | [docs/en/adr/013-phase-37-collaboration-core.md](../../../docs/en/adr/013-phase-37-collaboration-core.md) | Append-only `chart_revisions`, parent chain, content hash, branches, restore semantics, Realtime channel layout |

**Phase archive entries** in [docs/en/phase/completed-archive.md](../../../docs/en/phase/completed-archive.md):

- Phase 37.0 — ADR-013 cut
- Phase 37.1 — Data spine: `chart_revisions` + `chart_branches` schema, repository, sync, Realtime
- Phase 37.2 — `ChartHistoryScreen` + `GetChartHistoryUseCase`
- Phase 37.3 — `ChartDiffScreen` + `ChartDiffAlgorithm` + `GetChartDiffUseCase`
- Phase 37.4 — Branch CRUD + `RestoreRevisionUseCase`

**Maintenance rule**: when a Phase commit changes the history / branch / diff surface, update this spec in the same commit (per CLAUDE.md `## Development Workflow` step 7).
