# Spec — Pull Request Flow

> **Purpose**: stable feature-organized view of the chart-pattern pull-request workflow as it exists in main today. Describes the *what*; ADR-014 carries the *why*.
>
> **Audience**: an agent extending PR list / detail / merge / conflict resolution surfaces, or wiring a new entry point into the PR loop.
>
> **Scope**: PR open / list / detail / comment / close / merge / conflict resolution. Out of scope: chart history (separate spec → [collaboration-history.md](collaboration-history.md)), chart editor itself ([chart-editor.md](chart-editor.md)).

## Current shape

### Data spine

**Postgres tables** (migration [016_pull_requests.sql](../../../supabase/migrations/016_pull_requests.sql)):

| Table | Role |
|---|---|
| `pull_requests` | One row per PR. FKs to `patterns(id) ON DELETE CASCADE` (source + target), `chart_branches(id) ON DELETE CASCADE` (source + target), `chart_revisions(revision_id) ON DELETE RESTRICT` (`source_tip_revision_id`, `common_ancestor_revision_id`), `ON DELETE SET NULL` for `merged_revision_id`. `status TEXT NOT NULL CHECK (status IN ('open','merged','closed'))`. Partial unique on `(source_branch_id, target_branch_id) WHERE status = 'open'` |
| `pull_request_comments` | Append-only comments. `length(body) <= 5000` CHECK. RLS gates on PR-participant set |

**Critical RLS clauses**:
- INSERT on `pull_requests` requires `author_id = auth.uid()` AND **fork-routing invariant**: `source_pattern_id IN (SELECT id FROM patterns WHERE parent_pattern_id = target_pattern_id)`. Without this clause, any user could open a PR from any pattern they own against any target UUID.
- UPDATE permits `status` flips to `'open'` or `'closed'` only. The `'merged'` transition is reachable only through the SECURITY DEFINER `merge_pull_request` RPC.
- No DELETE policy. PRs are kept as audit trail; only CASCADE on pattern deletion clears them.

**`merge_pull_request` SECURITY DEFINER RPC** (in 016_pull_requests.sql):
- Validates: PR exists, `status = 'open'`, caller is target owner (`patterns.owner_id = v_caller`), strategy in `('squash', 'fast_forward')`, source tip unchanged (`chart_branches.tip_revision_id = source_tip_revision_id`).
- Captures `v_target_tip_pre_merge` BEFORE the INSERT (so the chart_documents UPDATE never reads NULL via subquery).
- INSERTs the merged revision into `chart_revisions` with `author_id = v_pr.author_id` and `owner_id = v_caller` — this is the only place in the codebase that produces `chart_revisions.author_id != owner_id` rows.
- UPDATEs `chart_branches.tip_revision_id` (target), `chart_documents` tip pointer, `pull_requests` row (status=merged + merged_revision_id + merged_at).
- `FOR UPDATE` on the PR row at function start serializes concurrent merges. Partial unique index prevents INSERT-side duplicate OPENs. **Both mechanisms are complementary, not redundant.**

### File map

**Shared module — `commonMain`**

| Path | Role |
|---|---|
| [shared/.../domain/model/PullRequest.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/model/PullRequest.kt) | `PullRequest` data class, `PullRequestStatus` enum (OPEN/MERGED/CLOSED), `PullRequestFilter` enum (INCOMING/OUTGOING) |
| [shared/.../domain/model/PullRequestComment.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/model/PullRequestComment.kt) | Comment data class (immutable) |
| [shared/.../domain/repository/PullRequestRepository.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/repository/PullRequestRepository.kt) | Read + write surface (11 methods) plus `subscribeToCommentsChannel(prId)` / `closeCommentsChannel()` |
| [shared/.../domain/repository/PullRequestMergeOperations.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/repository/PullRequestMergeOperations.kt) | Thin port for the merge RPC (ports-and-adapters layering) |
| [shared/.../domain/chart/ConflictDetector.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/chart/ConflictDetector.kt) | Pure 3-way wrapper over `ChartDiffAlgorithm`. `detect(ancestor, theirs, mine): ConflictReport` |
| [shared/.../data/local/LocalPullRequestDataSource.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/local/LocalPullRequestDataSource.kt) | SQLDelight-backed local cache |
| [shared/.../data/remote/RemotePullRequestDataSource.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/remote/RemotePullRequestDataSource.kt) | Supabase `RemotePullRequestSyncOperations` + `RemotePullRequestCommentSyncOperations` + `PullRequestMergeOperations` (single class, multiple interfaces) |
| Use cases — `domain/usecase/`: | |
| `OpenPullRequestUseCase.kt` | Walks parent chain to compute `commonAncestorRevisionId` client-side. `MAX_WALK_DEPTH = 1000`, `MAX_TARGET_HISTORY_LIMIT = 1000` |
| `ClosePullRequestUseCase.kt` | UI-layer caller-is-author-or-target-owner check; defense-in-depth status check |
| `GetPullRequestUseCase.kt` | Suspend invoke + `observe(prId, ownerId, scope)` Flow filtering whichever owner-scoped flow matches |
| `GetIncomingPullRequestsUseCase.kt` / `GetOutgoingPullRequestsUseCase.kt` | Symmetric. Observe Flow + one-shot `invoke()` for cold-launch seed |
| `GetPullRequestCommentsUseCase.kt` | Local Flow over per-PR Realtime channel cache |
| `PostPullRequestCommentUseCase.kt` | Body length ≤ 5000 char + non-blank validation + fresh id mint |
| `MergePullRequestUseCase.kt` | RPC invocation. Bypasses standard local-then-sync (RPC is the only writer for `author_id != owner_id` rows) |
| ViewModels — `ui/pullrequest/`: | |
| `PullRequestListViewModel.kt` | Parametric on `defaultFilter`. `flatMapLatest` over `_filter: MutableStateFlow<PullRequestFilter>` for cold-launch seed + observe lifetime |
| `PullRequestDetailViewModel.kt` | Parametric on `prId`. `init` seeds + subscribes to per-PR Realtime channel BEFORE seeding comments. `onCleared` uses detached `CoroutineScope(Dispatchers.Default + NonCancellable).launch { withTimeout(5_000) { closeCommentsChannel() } }` |
| `ConflictResolutionViewModel.kt` | Parametric on `prId`. Loads ancestor / theirs / mine in `init`, runs `ConflictDetector.detect`, exposes per-conflict picker state |
| Compose screens — `ui/pullrequest/`: | |
| `PullRequestListScreen.kt` | Filter chip row + grouped list (OPEN/MERGED/CLOSED display order) |
| `PullRequestDetailScreen.kt` | Title + status chip + author + description + diff preview link + comments + compose box + action bar |
| `ChartConflictResolutionScreen.kt` | Layer + cell conflict rows with 3-button picker (Take theirs / Keep mine / Skip) |

**iOS — `iosApp/iosApp/Screens/`**

| Path | Role |
|---|---|
| `PullRequestListScreen.swift` | SwiftUI mirror of list |
| `PullRequestDetailScreen.swift` | SwiftUI mirror of detail |
| `ChartConflictResolutionScreen.swift` | SwiftUI mirror with `AnyButtonStyle` private wrapper for selected/unselected picker buttons |

**Tests — `commonTest`**

- `PullRequestRepositoryImplTest` — 15 tests (open/close/post + sync enqueue + scoping + Realtime echo round-trip)
- `OpenPullRequestUseCaseTest` — 9 tests (parent-chain walk, fork-point shortcut, empty-target-history, validation paths)
- `MergePullRequestUseCaseTest` — 13 tests (RPC error mapping, idempotent revision-id, the HIGH-1 + HIGH-2 regression anchors)
- `ConflictDetectorTest` — 14 tests (full partition matrix, polar parity, multi-layer mixed change)
- `PullRequestDetailViewModelTest` — 14 tests (load + subscribe-before-seed + canMerge/canClose gates + nav events)
- `PullRequestListViewModelTest` — 12 tests (filter switch + flatMapLatest seed + race-fix anchors)
- `ConflictResolutionViewModelTest` — 8 tests (loadInitial → detect → picker state → ApplyAndMerge dispatch)
- `RealtimeSyncManagerPullRequestTest` — 4 tests (7-channel subscribe count, outgoing `author_id` filter, incoming-no-filter, unsubscribe)

### Domain entry points

**Open PR** — `OpenPullRequestUseCase(sourcePatternId, sourceBranchId, sourceTipRevisionId, targetPatternId, targetBranchId, title, description?)`. Walks `target.history` into a `Set<revisionId>` then walks source's `parent_revision_id` chain from `sourceTipRevisionId`. The start revision counts (fork-with-no-source-side-edits resolves immediately). Fails with `Validation` on no ancestor / empty target history / blank title / title > 200 / blank or > 2000 description.

Entry surfaces:
- `ChartViewerScreen` overflow → "Open pull request" `DropdownMenuItem` (gated on `state.canOpenPullRequest`).
- `ProjectDetailScreen` → `PatternInfoSection` → "Pull Requests" link (also opens the list, but list itself does not open new PRs).

**List** — `GetIncomingPullRequestsUseCase(ownerId)` / `GetOutgoingPullRequestsUseCase(ownerId)`. Both expose `observe()` Flow + one-shot `invoke()` for the seed. Repository `observeIncomingForOwner` / `observeOutgoingForOwner` are **local-only** flows — callers must invoke `getIncomingForOwner` first to backfill cache, then collect for live updates. Realtime channels keep the cache warm thereafter.

**Detail** — `GetPullRequestUseCase(prId)` + `observe(prId, ownerId, scope)`. The observe Flow filters whichever owner-scoped Flow the caller indicates via `PullRequestObserveScope` enum (INCOMING / OUTGOING) — the ViewModel picks based on `currentUserId == targetOwnerId` (incoming) vs `== authorId` (outgoing).

**Post comment** — `PostPullRequestCommentUseCase(prId, body)`. Validates body length + non-blank, mints id + `createdAt = Clock.System.now()` locally. Optimistic cache write in ViewModel so the user sees their post even in local-only mode where the Realtime echo never arrives.

**Close** — `ClosePullRequestUseCase(prId)`. Defense-in-depth checks status is OPEN + caller is signed in. The "is caller author or target owner" check lives in the **UI layer** (`PullRequestDetailViewModel.canClose`) — server-side RLS UPDATE policy is the actual security boundary.

**Merge** — `MergePullRequestUseCase(prId, resolvedChart, strategy = 'squash')`. RPC invocation. Maps RPC `RAISE EXCEPTION` messages onto distinct `UseCaseError` subtypes:
- "Source tip drifted" / "PR not open" / "Target branch has no tip" → `Validation`
- "Caller is not target owner" → `Authentication`
- "PR not found" → `NotFound`
- Other → `Unknown`

### Realtime channels

5 → 7 owner-scoped channels (per [collaboration-history.md](collaboration-history.md) baseline + 2 added by Phase 38.1):

| Channel | Filter |
|---|---|
| `pull-requests-incoming-<ownerId>` | NO client filter — RLS scopes broadcast to PRs where user is participant. Single-eq `ChangeFilter` cannot express "target_pattern_id IN owned_patterns" |
| `pull-requests-outgoing-<ownerId>` | `ChangeFilter("author_id", ownerId)` — clean server-side scoping |

Plus a **dynamic per-PR comments channel** `pull-request-comments-<prId>` opened/closed via PullRequestRepository `subscribeToCommentsChannel(prId)` / `closeCommentsChannel()`. NOT managed by `RealtimeSyncManager`; lifecycle is bound to `PullRequestDetailViewModel.init` / `onCleared`.

### Invariants (load-bearing — DO NOT BREAK)

1. **Fork-routing invariant** — RLS INSERT clause `source_pattern_id IN (SELECT id FROM patterns WHERE parent_pattern_id = target_pattern_id)` is the security boundary. Removing it allows arbitrary cross-pattern PR creation.

2. **Merge atomicity** — the RPC is the **only** path that produces `chart_revisions.author_id != owner_id`. It must run inside the function transaction (FOR UPDATE on the PR row + INSERT chart_revisions + UPDATE chart_branches + UPDATE chart_documents + UPDATE pull_requests as a single atomic unit). Bypassing the RPC by writing chart_revisions directly with a different author would violate the audit invariant.

3. **`canOpenPullRequest` exact-match gate** — the gate requires `branch.tipRevisionId == chart.revisionId` exact match. The `resolveCurrentBranch` fallback to `"main"` is for displayed-branch rendering only; the gate prevents submission of immediately-unmergeable PRs (the merge RPC would reject "Source tip drifted").

4. **`applyResolutions` correctness fixes** (HIGH-1 + HIGH-2 from ADR-014 review):
   - Auto-clean merge path applies `autoFromTheirs` ON `mine` (not raw `theirs`), preserving target-side `autoFromMine` edits.
   - SKIP / KEEP_MINE on a cell whose containing layer was auto-removed by theirs **drops the cell** (skips the `cellsByLayerXy.getOrPut`). Otherwise the orphan cell silently disappears in `mergedLayers` build.

5. **Idempotent revision id** — `MergePullRequestUseCase` mints fresh `revisionId = Uuid.random()` per call so PendingSync retry semantics stay clean.

6. **iOS `default: return status.name` fallback** — Swift `PullRequestStatus.statusKey` extension uses `default: return status.name` per Phase 33.1.13 precedent. A future enum addition without matching Swift switch update surfaces visibly at runtime instead of silently mislabeled.

7. **`onCleared` Realtime cleanup** — must run via detached `CoroutineScope(Dispatchers.Default + NonCancellable).launch { withTimeout(5_000) { ... } }`. `viewModelScope` is already cancelled when `onCleared` runs so a plain `viewModelScope.launch` would be dropped. NonCancellable + 5s timeout bounds the lifetime against hung websocket unsubscribe.

8. **E2E load-bearing testTags / accessibilityIdentifiers**:
   - List: `pullRequestListScreen`, `incomingFilterChip`, `outgoingFilterChip`, `prTitleLabel_<id>`, `prStatusChip_<id>`
   - Detail: `pullRequestDetailScreen`, `prTitleLabel`, `prDescriptionLabel`, `prDiffPreview`, `commentInputField`, `postCommentButton`, `mergeButton`, `closeButton`, `commentRow_<commentId>`
   - Open form: `openPullRequestMenuItem`, `openPullRequestDialog`, `openPrTitleInput`, `openPrDescriptionInput`, `openPrErrorLabel`, `confirmOpenPullRequestButton`
   - Conflict resolution: `chartConflictResolutionScreen`, `conflictRow_<layerId>_<x>_<y>`, `layerConflictRow_<layerId>`, `takeTheirsButton_*`, `keepMineButton_*`, `skipButton_*`, `applyAndMergeButton`
   - Entry points: `pullRequestsButton` (ProjectList overflow + iOS Menu), `openPullRequestsLink` (ProjectDetail PatternInfoSection)

## Extension points

### Adding a new merge strategy

ADR-014 §4: v1 supports `'squash'` (default) + `'fast_forward'` (detection-only — produces a 1-parent commit). Multi-parent merge-commit was rejected because `chart_revisions.parent_revision_id` is single-valued.

To add a strategy:
1. Update the RPC's `IF v_strategy NOT IN ('squash', 'fast_forward', '<new>')` check.
2. Update `MergePullRequestUseCase`'s strategy parameter validation.
3. Add to the conflict-resolution screen's strategy picker (currently absent — squash is implicit).

### Adding required-approval gates / draft PR / threaded comments

Post-v1 (deferred per ADR-014). The current data model collapses the approval shape to merge / close (target owner is implicit + sole approver). Adding required approval would require:
- New table `pull_request_approvals` with FKs to `pull_requests` + `users`.
- New RLS policy allowing collaborators (where defined?) to insert approval rows.
- Pre-merge gate in the RPC: `SELECT COUNT(*) FROM pull_request_approvals WHERE pull_request_id = v_pr.id AND state = 'approved'` ≥ N.
- This implies a `team` or `collaborator` concept, which v1 does not have.

### Adding inline diff render to PullRequestDetail

The "View proposed changes" button currently routes to the existing Phase 37.3 `ChartDiffScreen`. To embed inline:
- Extract `DualCanvasPanel` from `ChartDiffScreen` into a thumbnail-sized component.
- Add to `PullRequestDetailScreen` between description card and comments section.
- Same approach blocked for ChartConflictResolutionScreen's three-pane preview (deferred per Phase 38.4 scope cut).

### Adding entry points to PR list

Existing entries: `ProjectListScreen` toolbar overflow → "Pull requests" (defaults to INCOMING) + `ProjectDetailScreen` `PatternInfoSection` "Pull Requests" link (default filter computed: `pattern.parentPatternId != null ? OUTGOING : INCOMING`).

To add a new entry:
- Wire `onSuggestionsClick` callback through NavGraph to `navigate(PullRequestList(defaultFilter = INCOMING))`.
- Mirror in iOS via `path.append(Route.pullRequestList(defaultFilter:))`.
- testTag `pullRequestsButton` (or similar) for the new control.

## Deferred / known limitations

| Item | Pointer |
|---|---|
| **Inline diff canvas in PR detail** — links to ChartDiffScreen instead. Extracting `DualCanvasPanel` is a meaningful refactor of the gestural Compose Canvas | Phase 38.3 / 38.4 scope cut |
| **Three-pane canvas preview in ChartConflictResolutionScreen** — same `DualCanvasPanel` extraction. Row-based picker alone closes the merge loop | ADR-014 §6 / Phase 38.4 scope cut |
| **Required-approval gates / draft PR / threaded comments / @mentions / markdown / merge-conflict re-resolution after source drift** | Post-v1 per ADR-014 §10 |
| **PR templates / saved descriptions / comment reactions** | Post-v1 |
| **Realtime channel count: 7 owner-scoped + 1 dynamic per-PR** — bounded for now. If Phase 39 closed beta surfaces a free-tier connection cap, channels coalesce | ADR-014 Negative §2 |
| **Outgoing PR 2× bandwidth** — outgoing PRs delivered via both incoming AND outgoing channels (since they pass RLS as participant). INSERT OR REPLACE on `id` makes it idempotent. Acceptable for v1; revisit consolidation if connection cap hits | ADR-014 §7 |
| **iOS Discovery thumbnail static placeholder** — separate concern; tracked under Phase 36.4 → 36.4.1 in archive | CLAUDE.md → Tech Debt Backlog (closed trail) |

## ADR + archive references

**ADRs**:

| ADR | File | Scope |
|---|---|---|
| ADR-012 | [docs/en/adr/012-fork-attribution.md](../../../docs/en/adr/012-fork-attribution.md) | Fork → upstream PR routing invariant |
| ADR-013 | [docs/en/adr/013-phase-37-collaboration-core.md](../../../docs/en/adr/013-phase-37-collaboration-core.md) | Append-only `chart_revisions`, parent chain, content hash, `chart_branches.tip_revision_id` |
| ADR-014 | [docs/en/adr/014-phase-38-pull-request.md](../../../docs/en/adr/014-phase-38-pull-request.md) | PR data model, RPC merge atomicity, conflict resolution shape, comment surface, scope cuts |

**Phase archive entries** in [docs/en/phase/completed-archive.md](../../../docs/en/phase/completed-archive.md):

- Phase 38.0 — ADR-014 cut
- Phase 38.1 — Data spine (schema + RPC + repository + sync + Realtime)
- Phase 38.2 — Read-only PR list + entry points
- Phase 38.3 — PR detail + open / close / comment write paths
- Phase 38.4 — Merge + conflict resolution + ConflictDetector algorithm
- Phase 38.4.1 — ChartViewer overflow → "Open pull request" entry

**Maintenance rule**: when a Phase commit changes the PR-flow surface, update this spec in the same commit (per CLAUDE.md `## Development Workflow` step 7).
