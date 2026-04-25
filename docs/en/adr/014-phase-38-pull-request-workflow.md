# ADR-014: Phase 38 Pull Request Workflow (Comment, Approve, Merge, Conflict Resolution)

## Status

Proposed

## Context

Phase 37 (ADR-013) shipped the read-side of Git-like collaboration: an
append-only `chart_revisions` history, a tip pointer in `chart_documents`,
named branches in `chart_branches`, and a side-by-side `ChartDiffScreen`.
The diff algorithm is cell-level over `(layer.id, x, y)` triples, layer
properties tracked separately, polar charts diff identically as
`(stitch, ring)`. Forks land on a populated `parent_revision_id` chain
from Phase 36; the data spine is end-to-end functional.

Phase 38 closes the collaboration loop: a forker submits their changes
back to the source author. The author reviews the proposed diff, optionally
discusses via comments, and merges (or closes). On merge, the contributor's
edits land on the target branch as a new commit attributed to the
contributor — the first time `chart_revisions.author_id` diverges from
`owner_id` in a row written by the codebase. The `author_id` column was
provisioned in ADR-013 §1 specifically for this slice.

**Phase 38 introduces three new structural elements that Phase 37 did not
have to address:**

1. **Multi-author commits.** ADR-013 §1's INSERT-time RLS policy on
   `chart_revisions` is `WITH CHECK (owner_id = auth.uid() AND author_id = auth.uid())`.
   This forbids a target owner from inserting a row attributed to a
   different author. Either the policy must relax to allow
   `author_id != auth.uid()` under some condition, or the merge write
   must route through a Postgres function with `SECURITY DEFINER` that
   bypasses the policy after validating the merge precondition itself.

2. **Cross-pattern lineage.** Phase 37 lineage is per-pattern: every
   `parent_revision_id` points at a revision in the *same* pattern.
   A merge inserts a revision into the target pattern whose drawing
   payload originated in the source pattern. The `parent_revision_id`
   stays in-pattern (points at the target tip the merge is built on top
   of), but the `author_id` is the source pattern's owner. Cross-pattern
   blame is encoded via `author_id`, not via lineage.

3. **Three-way state for conflict.** Phase 37 diff is two-way (base vs
   target). A merge needs three: the **common ancestor** revision (the
   source's parent in the target's history before the fork point), the
   **theirs** revision (source tip), and the **mine** revision (target
   tip). When both sides modified the same cell relative to the common
   ancestor, that's a conflict requiring resolution. Phase 37's diff
   algorithm is the right shape for the diff-of-diffs comparison but
   the UI primitive is different: conflict resolution is *interactive*
   (pick a side per cell), not read-only like ChartDiffScreen.

Phase 38 MVP scope per ADR-007:

- **Pull request workflow** — fork owner submits PR; target owner reviews
  diff, comments, merges or closes.
- **Comment thread** — flat comments on a PR, plus the merge / close
  decision is itself an audit trail entry.
- **Merge strategies** — at least one strategy that produces a single
  new revision on the target branch with the contributor as `author_id`.
- **Conflict visualization** — when source and target both edited cells
  whose common ancestor was the same, surface the conflict and let the
  target owner pick a side per cell.

Explicitly **not** in Phase 38 MVP:

- "Pull from upstream" affordance on a forked chart (Phase 38+; the PR
  flow runs source → target only in v1).
- Required-approval gates (the target owner is the implicit and sole
  approver; multi-reviewer is post-v1).
- Branch protection rules.
- Cross-fork PR routing (PR target is always the source pattern's
  `parent_pattern` per ADR-012 §1; arbitrary target selection is
  out of scope).
- Blame view ("who changed this cell when") — Phase 38+ build atop the
  populated `author_id`.
- CRDT concurrent editing.
- Cherry-pick, rebase, squash-with-fixup, amend.
- Merge-commit strategy with two parents — `chart_revisions` has a
  single `parent_revision_id` column; multi-parent commits would force
  a schema change, conflicting with the v1 simplification.
- Editing past comments (comments are append-only by RLS).
- Draft PR state.
- PR templates.

Constraints carried forward:

- **ADR-013 §1:** `chart_revisions` is append-only (no UPDATE, no DELETE
  policy). Merge writes a new revision row; nothing is rewritten.
- **ADR-013 §7:** `chart_branches.tip_revision_id` is the canonical "what
  branch X currently points at" value. Merge advances the target branch
  tip. The single-tip simplification (only the checked-out branch's tip
  is materialized in `chart_documents`) holds — merge does not change
  this contract.
- **ADR-012 §1:** `Pattern.parentPatternId` is the fork-attribution
  pointer. Phase 38 PR routing reads it: a PR's target is
  `source.parentPatternId`. PRs from a non-forked pattern are not
  permitted in v1.
- **ADR-008 §6:** `revision_id` is the canonical commit identifier;
  globally unique per the standalone UNIQUE in ADR-013 §1.
- **ADR-013 §10:** Realtime is owner-scoped, not public-fan-out.
  Phase 38 introduces a "watch upstream" requirement (the fork owner
  needs to be notified when the target owner comments / merges / closes
  their PR, and vice-versa). The `chart-revisions-<ownerId>` channel
  does not deliver this — a new channel is needed, scoped to PR
  membership rather than ownership.

## Agent team deliberation

Convened once for the full ADR. Five interacting topics: PR data model,
multi-author write path, merge strategy, conflict resolution UX, and
Realtime fan-out for PR notifications.

### Voices

- **architect:** Multi-author writes force a SECURITY DEFINER RPC, full
  stop. The current `chart_revisions` INSERT policy
  `WITH CHECK (owner_id = auth.uid() AND author_id = auth.uid())` is
  load-bearing — relaxing it to "INSERT a row whose `author_id` is any
  user who has an open PR targeting this pattern" buries cross-table
  validation in a row-level policy, which Postgres RLS handles poorly
  (subqueries in `WITH CHECK` are a known performance and correctness
  trap). Server-side `merge_pull_request(pr_id, strategy, resolved_cells)`
  RPC running with `SECURITY DEFINER` validates the precondition (open
  PR, caller is target owner, source tip unchanged since PR open) and
  performs the multi-table write atomically: INSERT new revision, UPDATE
  branch tip, UPDATE PR status. ADR-013 deferred RPCs as "revisit if
  telemetry shows divergence"; Phase 38 is the trigger because the
  alternative (relaxed RLS + multi-roundtrip client orchestration) is
  worse along multiple axes.

- **product-manager:** PR is "give my changes back" not "open a code
  review with N approvers". v1 reviewer == owner. Approve / reject
  collapse into "merge" / "close" — both are end-states the owner
  reaches after seeing the diff. Comments are informational discussion,
  not gates. Required-approval is a feature for collaborative repos
  (Phase 38+ when "team" patterns become a thing); v1 is one fork owner
  asking one source owner. Single approver, single button: **merge**.
  An explicit `approve` action without merge is empty — the only thing
  approval would do is enable the merge button, which is already only
  visible to the target owner. Skip it.

- **knitter:** Knitters do not think in Git. "Pull request" is the term
  but the surface should read like "Suggest changes to this pattern" /
  "Review suggestion". Comment threading nice-to-have but flat is fine
  for v1 — knitting workflows don't have the nested-debate culture that
  software code reviews do. Most comments will be "great cuff!" or
  "is row 12 supposed to have a yo here?" — single-thread is enough.
  For merge strategy, knitters expect "I accept the changes" to land
  the contributor's edits on their chart. They do not expect — and will
  be confused by — a merge commit with "merged from <username>'s fork"
  showing up as a parent. **Squash is the right default**: collapse the
  contributor's chain into one new commit on target whose author is the
  contributor and message is the PR title. Fast-forward is fine when
  applicable but invisible to the user (auto-applied; no UI choice).

- **implementer:** Conflict detection is a diff-of-diffs against the
  common ancestor, which Phase 37's diff algorithm already supports
  shape-wise. New helper `ConflictDetector(ancestor, theirs, mine):
  ConflictReport` returns three sets per layer per cell coordinate:
  cells changed only in theirs (auto-take theirs), cells changed only
  in mine (auto-keep mine), cells changed in both with different target
  values (conflict — user resolves). Layer-property conflicts (both
  sides renamed the same layer differently) handled the same way at the
  layer level. This is pure Kotlin, fully testable. The interactive
  resolution UI feeds back a `Map<CellCoordinate, Resolution>` to the
  merge RPC. A separate `ChartConflictResolutionScreen` is correct —
  ChartDiffScreen is read-only and 2-pane; conflict resolution is
  interactive and 3-pane (ancestor pinned center, theirs/mine
  side-by-side, click to pick). Reusing ChartDiffScreen would require
  adding interaction + a third pane mode — bigger change than a new
  screen.

- **ui-ux-designer:** Three concerns. (1) The PR list screen lives at
  `Pattern → "Suggestions"` and at `Project → "Outgoing suggestions"`
  — same data model surfaced from two perspectives (target-of vs
  source-of). Avoid building two screens; one
  `PullRequestListScreen(filter: incoming|outgoing)` parameterized.
  (2) Conflict resolution UI must NOT use traffic-light palette —
  ChartDiffScreen owns that visual language and the user has just
  internalized it. Conflict cells need a fourth, distinct color
  (recommend `Color.Magenta` semi-transparent, or `tertiary` from the
  Material color role). (3) PR title + description must be markdown-
  free for v1. Plain text only. Allow line breaks. Stretching to
  markdown opens render-target divergence between Compose `Text` and
  SwiftUI `Text` that we don't have a shared sanitizer for.

### Decision points resolved by the team

1. **Data model** → new `pull_requests` table + `pull_request_comments`
   table. PR is its own entity with foreign keys to `patterns` and
   `chart_branches` on both sides (architect, strong; PM agrees).

2. **Merge atomicity** → server-side `merge_pull_request` RPC running
   with `SECURITY DEFINER`. Validates PR open, caller is target owner,
   source tip unchanged, conflict resolution complete; performs INSERT
   of merged revision + UPDATE of target branch tip + UPDATE of PR
   status atomically (architect, strong; implementer agrees;
   PM yields). The Phase 37-deferred RPC question is reopened here
   because Phase 38 needs atomicity that's user-observable, not just
   "best-effort eventually consistent" like Phase 37's tip pointer.

3. **Merge strategy default** → **squash**. Fast-forward auto-applied
   when applicable (target tip == source's parent_revision_id chain
   start, no divergent commits on target since fork point) without
   surfacing as a user choice. Merge-commit (multi-parent) is rejected
   for v1 — chart_revisions has a single `parent_revision_id` column
   and adding a `parent_revision_id_2` would force a schema redesign.
   Knitters do not benefit from multi-parent history (knitter, strong;
   PM agrees).

4. **Approval shape** → informational comments + owner-can-merge
   anytime. No required-approval gate. The merge button itself is the
   approval action. The close button is the rejection action (PM,
   strong; ui-ux-designer agrees).

5. **Conflict resolution UI** → new `ChartConflictResolutionScreen`,
   not a mode of ChartDiffScreen. Shared diff helper underneath but
   separate screen on top. Three-pane layout (ancestor / theirs / mine)
   with picker UI for each conflicted cell (implementer, strong;
   ui-ux-designer agrees with palette caveat).

6. **PR list screen** → one `PullRequestListScreen(filter)` parameterized
   on incoming vs outgoing rather than two screens (ui-ux-designer).

7. **Realtime fan-out** → new `pull-request-<prId>` channels subscribed
   to by both source and target owners while the PR is open. Closes on
   PR merge/close. PR-list screens use a different shape: they
   subscribe to `pull-requests-incoming-<ownerId>` /
   `pull-requests-outgoing-<ownerId>` channels filtered by owner role
   (target / source) so the list updates live when a new PR is opened
   against the user's pattern (architect; implementer agrees).

8. **PR title and body format** → plain text, no markdown. Soft 200-
   char limit on title (consistent with ADR-013's commit message
   convention), soft 2000-char limit on description. UI enforces
   limits; server accepts any TEXT (ui-ux-designer; architect agrees).

## Decision

### 1. Data model: `pull_requests` + `pull_request_comments`

Migration 016 creates `pull_requests`:

```sql
CREATE TABLE IF NOT EXISTS public.pull_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Source side: the fork's chart, the contributor's branch + tip.
    -- The two `*_revision_id` FKs target chart_revisions.revision_id (the
    -- standalone UNIQUE column from ADR-013 §1) rather than the table's
    -- PK `id`. This matches the chart_branches.tip_revision_id FK
    -- precedent in migration 015 — `revision_id` is the canonical commit
    -- identifier per ADR-008 §6, and the standalone UNIQUE makes it a
    -- valid FK target.
    source_pattern_id UUID NOT NULL REFERENCES public.patterns(id) ON DELETE CASCADE,
    source_branch_id UUID NOT NULL REFERENCES public.chart_branches(id) ON DELETE CASCADE,
    source_tip_revision_id UUID NOT NULL REFERENCES public.chart_revisions(revision_id) ON DELETE RESTRICT,

    -- Target side: the upstream pattern, the branch the merge will land on.
    target_pattern_id UUID NOT NULL REFERENCES public.patterns(id) ON DELETE CASCADE,
    target_branch_id UUID NOT NULL REFERENCES public.chart_branches(id) ON DELETE CASCADE,

    -- Snapshot at PR open time. The source tip can advance while the PR is
    -- open; this column records the tip the PR was originally opened against
    -- so the diff is reproducible and re-conflict-detection on merge can
    -- detect "source moved underneath the PR" (re-resolve required).
    common_ancestor_revision_id UUID NOT NULL REFERENCES public.chart_revisions(revision_id) ON DELETE RESTRICT,

    -- Authorship and ownership. The PR creator is always source.owner;
    -- the resolver is always target.owner. Both must be non-null at row
    -- creation time; SET NULL on FK so PR rows survive account deletion
    -- the same way revisions do (ADR-013 §1 precedent).
    author_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,

    title TEXT NOT NULL,
    description TEXT,

    -- Lifecycle: OPEN → MERGED | CLOSED. No DRAFT for v1.
    status TEXT NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'merged', 'closed')),

    -- Populated by the merge RPC. NULL for OPEN and CLOSED PRs.
    merged_revision_id UUID REFERENCES public.chart_revisions(revision_id) ON DELETE SET NULL,
    merged_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A single OPEN PR per (source_branch, target_branch) pair. Closing and
-- reopening (= a new PR row, the prior one stays as CLOSED) is the
-- workflow for "I want to try again with a different message". A plain
-- UNIQUE on (source_branch_id, target_branch_id, status) would also
-- forbid multiple CLOSED rows for the same pair, which is wrong — users
-- close and reopen indefinitely. Partial unique index on the open subset
-- only.
CREATE UNIQUE INDEX IF NOT EXISTS idx_pull_requests_unique_open
    ON public.pull_requests (source_branch_id, target_branch_id)
    WHERE status = 'open';

CREATE INDEX IF NOT EXISTS idx_pull_requests_target_pattern_status
    ON public.pull_requests(target_pattern_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_pull_requests_source_pattern_status
    ON public.pull_requests(source_pattern_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_pull_requests_author_status
    ON public.pull_requests(author_id, status);

ALTER TABLE public.pull_requests ENABLE ROW LEVEL SECURITY;

CREATE POLICY "PR readable by source or target owner"
    ON public.pull_requests FOR SELECT
    USING (
        author_id = auth.uid()
        OR target_pattern_id IN (
            SELECT id FROM public.patterns WHERE owner_id = auth.uid()
        )
    );

CREATE POLICY "PR insertable by source owner only"
    ON public.pull_requests FOR INSERT
    WITH CHECK (
        author_id = auth.uid()
        AND source_pattern_id IN (
            SELECT id FROM public.patterns WHERE owner_id = auth.uid()
        )
        -- v1 invariant: PR target must be source's upstream (parent fork
        -- pattern). Without this RLS clause, an authenticated user could
        -- open a PR from any pattern they own against any target pattern
        -- they happen to know the UUID of. ADR-014 §1 + Considered
        -- Alternatives row "PR open from arbitrary target" rule this out;
        -- the policy enforces the stated invariant. Internal cross-branch
        -- PRs within the same pattern are rejected by this clause too —
        -- v1 routes PRs only fork → upstream.
        AND source_pattern_id IN (
            SELECT id FROM public.patterns
            WHERE parent_pattern_id = target_pattern_id
        )
    );

CREATE POLICY "PR closeable by either party"
    ON public.pull_requests FOR UPDATE
    USING (
        author_id = auth.uid()
        OR target_pattern_id IN (
            SELECT id FROM public.patterns WHERE owner_id = auth.uid()
        )
    )
    WITH CHECK (
        -- Only status / closed_at fields may be UPDATEd via this policy.
        -- The merge RPC bypasses RLS via SECURITY DEFINER for status
        -- transitions to 'merged'.
        status IN ('open', 'closed')
    );

ALTER PUBLICATION supabase_realtime ADD TABLE public.pull_requests;
```

Comments table:

```sql
CREATE TABLE IF NOT EXISTS public.pull_request_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pull_request_id UUID NOT NULL REFERENCES public.pull_requests(id) ON DELETE CASCADE,
    author_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    body TEXT NOT NULL CHECK (length(body) <= 5000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pull_request_comments_pr_id_created
    ON public.pull_request_comments(pull_request_id, created_at);

ALTER TABLE public.pull_request_comments ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Comments readable by PR participants"
    ON public.pull_request_comments FOR SELECT
    USING (
        pull_request_id IN (
            SELECT id FROM public.pull_requests
            WHERE author_id = auth.uid()
               OR target_pattern_id IN (SELECT id FROM public.patterns WHERE owner_id = auth.uid())
        )
    );

CREATE POLICY "Comments insertable by PR participants"
    ON public.pull_request_comments FOR INSERT
    WITH CHECK (
        author_id = auth.uid()
        AND pull_request_id IN (
            SELECT id FROM public.pull_requests
            WHERE author_id = auth.uid()
               OR target_pattern_id IN (SELECT id FROM public.patterns WHERE owner_id = auth.uid())
        )
    );

-- No UPDATE / DELETE policies. Comments are append-only.

ALTER PUBLICATION supabase_realtime ADD TABLE public.pull_request_comments;
```

Notes:

- **No UPDATE/DELETE on comments** — append-only mirrors `chart_revisions`
  (ADR-013 §1) and Git's history immutability invariant. Editing a
  comment that the other party has already read silently changes the
  audit trail; v1 forbids it. A "delete my comment" affordance is
  Phase 38+.
- **`common_ancestor_revision_id`** is captured at PR-open time. It's
  the source's `parent_revision_id` chain walked back to the most
  recent revision present in the target's history. For Phase 38 v1,
  this is computed client-side at PR-open (cheap — both histories are
  in local cache when the user navigates to "Open PR" from a forked
  chart) and stored on the PR row. The merge RPC re-validates it.
- **`source_tip_revision_id`** snapshots the source tip at PR-open.
  If the PR author keeps committing to source after opening, the
  source tip drifts forward. The merge RPC reads the current
  `chart_branches.tip_revision_id` for `source_branch_id` at merge
  time — if it differs from `source_tip_revision_id`, the merge
  surface re-runs conflict detection against the new tip and the
  resolver goes back to the conflict resolution screen.
- **No `target_tip_revision_id` snapshot.** The target tip moves under
  the PR as the target owner commits to their own branch; that's
  expected, and the merge RPC always uses the *current* target tip
  as the merge base.
- **`status` enum is closed** — three values, no DRAFT in v1.

### 2. SQLDelight mirror

`shared/src/commonMain/sqldelight/io/github/b150005/knitnote/db/PullRequest.sq`:

```sql
CREATE TABLE PullRequestEntity (
    id TEXT NOT NULL PRIMARY KEY,
    source_pattern_id TEXT NOT NULL,
    source_branch_id TEXT NOT NULL,
    source_tip_revision_id TEXT NOT NULL,
    target_pattern_id TEXT NOT NULL,
    target_branch_id TEXT NOT NULL,
    common_ancestor_revision_id TEXT NOT NULL,
    author_id TEXT,
    title TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL DEFAULT 'open',
    merged_revision_id TEXT,
    merged_at TEXT,
    closed_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pr_target_pattern_status
    ON PullRequestEntity(target_pattern_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_pr_source_pattern_status
    ON PullRequestEntity(source_pattern_id, status, created_at);
```

Queries: `getById`, `getByTargetPattern`, `getBySourcePattern`,
`getIncomingForOwner`, `getOutgoingForOwner`,
`observeIncomingForOwner`, `observeOutgoingForOwner`, `upsert`,
`updateStatus`, `deleteByPatternId` (CASCADE backfill on local
delete). No `update` of `title` / `description` for v1 — a typoed
title is fixed by closing and reopening.

`PullRequestComment.sq` follows the same shape as `Comment.sq` from
Phase 4b sharing — append-only, indexed by parent.

### 3. Domain model

```kotlin
@Serializable
enum class PullRequestStatus { OPEN, MERGED, CLOSED }

@Serializable
data class PullRequest(
    val id: String,
    @SerialName("source_pattern_id") val sourcePatternId: String,
    @SerialName("source_branch_id") val sourceBranchId: String,
    @SerialName("source_tip_revision_id") val sourceTipRevisionId: String,
    @SerialName("target_pattern_id") val targetPatternId: String,
    @SerialName("target_branch_id") val targetBranchId: String,
    @SerialName("common_ancestor_revision_id") val commonAncestorRevisionId: String,
    @SerialName("author_id") val authorId: String?,
    val title: String,
    val description: String?,
    val status: PullRequestStatus,
    @SerialName("merged_revision_id") val mergedRevisionId: String?,
    @SerialName("merged_at") val mergedAt: Instant?,
    @SerialName("closed_at") val closedAt: Instant?,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
)

@Serializable
data class PullRequestComment(
    val id: String,
    @SerialName("pull_request_id") val pullRequestId: String,
    @SerialName("author_id") val authorId: String?,
    val body: String,
    @SerialName("created_at") val createdAt: Instant,
)
```

`PullRequest.canMerge(currentUserId: String, targetOwnerId: String): Boolean` —
extension property that gates the merge button: status == OPEN and
currentUserId == targetOwnerId. The merge RPC re-validates server-side.

### 4. Conflict detection algorithm

Pure Kotlin, reuses Phase 37 cell-keyed diff shape. New helper
`domain/chart/ConflictDetector.kt`:

```kotlin
data class ConflictReport(
    val autoFromTheirs: List<CellChange>,
    val autoFromMine: List<CellChange>,
    val conflicts: List<CellConflict>,
    val layerConflicts: List<LayerConflict>,
)

data class CellConflict(
    val layerId: String,
    val x: Int,
    val y: Int,
    val ancestor: ChartCell?,
    val theirs: ChartCell?,
    val mine: ChartCell?,
)

data class LayerConflict(
    val layerId: String,
    val ancestor: ChartLayer?,
    val theirs: ChartLayer?,
    val mine: ChartLayer?,
)

object ConflictDetector {
    fun detect(
        ancestor: StructuredChart,
        theirs: StructuredChart,
        mine: StructuredChart,
    ): ConflictReport
}
```

Algorithm: compute `diff(ancestor, theirs)` and `diff(ancestor, mine)`
using Phase 37's existing diff helper, then per-cell-coordinate
classify into autoFromTheirs / autoFromMine / conflicts based on
whether both sides changed the same `(layerId, x, y)`.

Polar charts handled identically — `(x, y)` keys are `(stitch, ring)`.
Wide cells (`cell.width > 1`) use the top-left anchor as the key,
identical to Phase 37 §5.

A "conflict" is two sides producing different target cells. Two sides
producing the *same* target cell (rare but possible — both decided
to draw the same symbol at the same coordinate) is auto-resolved with
no user prompt; both contributions agree.

### 5. Merge RPC

Server-side function `merge_pull_request` in migration 016, executed
with `SECURITY DEFINER`:

```sql
CREATE OR REPLACE FUNCTION public.merge_pull_request(
    p_pull_request_id UUID,
    p_strategy TEXT,
    p_merged_document JSONB,
    p_merged_content_hash TEXT,
    p_resolved_revision_id UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_pr public.pull_requests;
    v_target_branch public.chart_branches;
    v_source_tip_now UUID;
    v_caller UUID := auth.uid();
    v_new_revision_id UUID := p_resolved_revision_id;
    v_target_tip_pre_merge UUID;
    v_inserted_parent_revision_id UUID;
BEGIN
    -- Validate PR existence + caller is target owner.
    SELECT * INTO v_pr FROM public.pull_requests WHERE id = p_pull_request_id FOR UPDATE;
    IF v_pr IS NULL THEN RAISE EXCEPTION 'PR not found'; END IF;
    IF v_pr.status != 'open' THEN RAISE EXCEPTION 'PR not open'; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.patterns
        WHERE id = v_pr.target_pattern_id AND owner_id = v_caller
    ) THEN
        RAISE EXCEPTION 'Caller is not target owner';
    END IF;

    -- Validate strategy.
    IF p_strategy NOT IN ('squash', 'fast_forward') THEN
        RAISE EXCEPTION 'Unsupported strategy: %', p_strategy;
    END IF;

    -- Validate source tip unchanged. Drift means resolver must re-run.
    SELECT tip_revision_id INTO v_source_tip_now
        FROM public.chart_branches WHERE id = v_pr.source_branch_id;
    IF v_source_tip_now IS DISTINCT FROM v_pr.source_tip_revision_id THEN
        RAISE EXCEPTION 'Source tip drifted; re-resolve required';
    END IF;

    -- Snapshot target branch tip before INSERT — used as parent for the
    -- merged revision row and forwarded into chart_documents below.
    -- Captured into a local variable so the chart_documents UPDATE does
    -- not depend on a subquery that could silently NULL out the tip
    -- pointer if a data-integrity gap loses the just-inserted row.
    SELECT tip_revision_id INTO v_target_tip_pre_merge
        FROM public.chart_branches WHERE id = v_pr.target_branch_id;
    IF v_target_tip_pre_merge IS NULL THEN
        RAISE EXCEPTION 'Target branch has no tip; merge precondition failed';
    END IF;

    -- INSERT merged revision. author_id = PR author (the contributor),
    -- owner_id = target.owner. SECURITY DEFINER bypasses the table's
    -- INSERT policy WITH CHECK clause; this function is the *only*
    -- writer that can produce author_id != owner_id rows.
    -- RETURNING captures the row's parent_revision_id into a local so
    -- the chart_documents UPDATE below can reuse it without a subquery
    -- read-back (which would return NULL silently if the INSERT
    -- somehow produced no row, instead of raising).
    INSERT INTO public.chart_revisions (
        revision_id, pattern_id, owner_id, author_id,
        schema_version, storage_variant, coordinate_system,
        document, parent_revision_id, content_hash,
        commit_message, created_at
    )
    SELECT
        v_new_revision_id,
        v_pr.target_pattern_id,
        v_caller,
        v_pr.author_id,
        cr.schema_version, cr.storage_variant, cr.coordinate_system,
        p_merged_document,
        v_target_tip_pre_merge,
        p_merged_content_hash,
        v_pr.title,
        now()
    FROM public.chart_revisions cr
    WHERE cr.revision_id = v_pr.source_tip_revision_id
    RETURNING parent_revision_id INTO v_inserted_parent_revision_id;

    IF v_inserted_parent_revision_id IS DISTINCT FROM v_target_tip_pre_merge THEN
        -- Defensive: should be impossible. If the INSERT silently
        -- inserted zero rows (e.g. source revision missing), RETURNING
        -- would not assign and the variable would stay NULL — and the
        -- equality check above would fire. Raises rather than continuing
        -- with a half-applied merge.
        RAISE EXCEPTION 'Merged revision INSERT did not match precondition';
    END IF;

    -- UPDATE target branch tip.
    UPDATE public.chart_branches
        SET tip_revision_id = v_new_revision_id, updated_at = now()
        WHERE id = v_pr.target_branch_id;

    -- UPDATE chart_documents tip pointer for the target pattern. Uses
    -- the captured local v_target_tip_pre_merge directly instead of a
    -- subquery against the just-inserted row — same value, no NULL
    -- hazard.
    UPDATE public.chart_documents
        SET revision_id = v_new_revision_id,
            parent_revision_id = v_target_tip_pre_merge,
            content_hash = p_merged_content_hash,
            document = p_merged_document,
            updated_at = now()
        WHERE pattern_id = v_pr.target_pattern_id;

    -- Mark PR merged.
    UPDATE public.pull_requests
        SET status = 'merged',
            merged_revision_id = v_new_revision_id,
            merged_at = now(),
            updated_at = now()
        WHERE id = p_pull_request_id;

    RETURN v_new_revision_id;
END;
$$;

REVOKE ALL ON FUNCTION public.merge_pull_request FROM public;
GRANT EXECUTE ON FUNCTION public.merge_pull_request TO authenticated;
```

**Concurrency model.** The function takes `FOR UPDATE` on the PR row at
its top, which serializes any concurrent merge attempts against the same
PR — the second caller blocks until the first commits, then sees
`status='merged'` and bails on the "PR not open" check. This is the
load-bearing mechanism preventing double-merge. The partial unique
index on `(source_branch_id, target_branch_id) WHERE status = 'open'`
prevents *INSERT-side* duplicate OPEN rows; it does not gate the merge
race. These two mechanisms are complementary, not redundant.

**Why SECURITY DEFINER is required here** (and was not in Phase 37):
the new revision's `author_id` is the PR contributor, not `auth.uid()`.
The base table's INSERT policy `WITH CHECK author_id = auth.uid()`
forbids that combination. SECURITY DEFINER bypasses RLS for the
function body's SQL, and the function's own validation is what
enforces the actual security invariant ("caller is target owner;
referenced PR is open and matches"). This pattern is the same shape
as `delete_own_account` from Phase 17 (ADR-005) which also runs as
SECURITY DEFINER for cross-table deletion.

The RPC accepts the *resolved* document (post-merge JSONB) as a
parameter rather than re-running merge logic server-side. The client
performs the merge using `ConflictDetector` + the resolver UI, then
hands the final document to the RPC. Server validates structural
preconditions but not the merge's *content* — that's the resolver's
job. This matches the "thin server, rich client" stance from ADR-001.

`fast_forward` strategy in v1: defined as "the resulting squash
revision's document is byte-identical to `source_tip_revision_id`'s
document — no conflicts, no target-side divergence since the common
ancestor". v1 still produces a new revision row (single-parent,
attributed to the contributor); we do **not** implement true Git-style
FF (tip-pointer move with no new commit) because the correctness
benefit over squash is zero and the testing surface doubles. The
client always invokes the RPC with `strategy = 'squash'` and a
freshly-minted `revision_id`; the `'fast_forward'` strategy enum value
is reserved on the SQL side for forward compatibility but no client
path exercises it in 38.4. This is a deliberate v1 simplification;
revisit when telemetry shows users notice the squash-commit on what
would have been a clean FF.

### 6. UX surfaces

Three new screens, all shared Compose + SwiftUI mirror.

#### `PullRequestListScreen(filter: Incoming | Outgoing)`

- Top bar with back navigation + filter chips (Incoming / Outgoing).
- Vertical list of PRs grouped by status: OPEN at top, then MERGED,
  then CLOSED.
- Each row shows: title, source pattern title (truncated), author
  display name, relative timestamp, status chip.
- Tap a row → opens `PullRequestDetailScreen(prId)`.
- testTags: `pullRequestListScreen` (root), `prRow_<prId>`,
  `incomingFilterChip`, `outgoingFilterChip`.
- i18n keys (additive): `title_pull_requests`,
  `label_filter_incoming`, `label_filter_outgoing`,
  `state_no_pull_requests`, `state_no_pull_requests_body`,
  `label_pr_status_open`, `label_pr_status_merged`,
  `label_pr_status_closed`, `label_pr_authored_by` parametric.

Reachable from:

- `ProjectListScreen` overflow → "Suggestions" badge with unread count
  (read-state tracked client-side, last-seen-at per filter).
- `ProjectDetailScreen` → "Suggestions" link in PatternInfoSection
  when `pattern.parentPatternId == null` (incoming) OR when the
  pattern itself is a fork (outgoing).

#### `PullRequestDetailScreen(prId)`

- Top bar with back + PR title + status chip.
- Description card (plain text, multi-line).
- Diff section: inline `ChartDiffScreen`-style canvas pair showing
  `common_ancestor_revision` vs `source_tip_revision`. Tap to expand
  to fullscreen diff.
- Comments section: chronological list, compose box at bottom.
- Action bar (target owner only): "Merge" + "Close" buttons.
- Action bar (source author only): "Close" button (cannot merge own PR).
- testTags: `pullRequestDetailScreen` (root), `prTitleLabel`,
  `prDescriptionLabel`, `prDiffPreview`, `prCommentsList`,
  `commentInputField`, `postCommentButton`, `mergeButton`,
  `closeButton`, `commentRow_<commentId>`.
- i18n keys (additive, ~14): `title_pull_request_detail`,
  `label_pr_description`, `label_pr_diff_preview`,
  `label_pr_comments`, `hint_add_comment_to_pr`,
  `action_post_comment`, `action_merge_pr`, `action_close_pr`,
  `dialog_merge_pr_title`, `dialog_merge_pr_body`,
  `dialog_close_pr_title`, `dialog_close_pr_body`,
  `state_pr_not_found`, `message_pr_merged_successfully`,
  `message_pr_closed_successfully`.

#### `ChartConflictResolutionScreen(prId)`

Reached from `PullRequestDetailScreen` when the user taps "Merge"
*and* `ConflictDetector` returns non-empty `conflicts` /
`layerConflicts`. If empty, the merge RPC is invoked directly with
the auto-resolved document.

- Top bar with back + title + summary chip ("3 conflicts to resolve").
- Three-pane canvas layout: ancestor (top, smaller, pinned reference)
  + theirs (bottom-left) + mine (bottom-right). On phones, stacks
  vertically with the conflict list as the primary affordance.
- Conflict list: scrollable list of `CellConflict` rows, each showing
  `(layerId, x, y)` + a 3-button picker (Take Theirs / Keep Mine /
  Skip) with the picked option highlighted. Skip leaves the cell as
  the ancestor value.
- Bottom bar: "Apply and Merge" button enabled only when every
  conflict has a resolution.
- testTags: `chartConflictResolutionScreen` (root),
  `conflictRow_<layerId>_<x>_<y>`, `takeTheirsButton_<layerId>_<x>_<y>`,
  `keepMineButton_<layerId>_<x>_<y>`, `applyAndMergeButton`.
- i18n keys (additive, ~9): `title_resolve_conflicts`,
  `label_conflict_summary` parametric, `label_conflict_layer`,
  `label_conflict_cell` parametric, `action_take_theirs`,
  `action_keep_mine`, `action_skip_conflict`,
  `action_apply_and_merge`, `state_all_conflicts_resolved`.

**Color palette**: conflict cells use a fourth color distinct from
the Phase 37 traffic-light diff palette (added/modified/removed).
Recommend `tertiary` Material role (purple-ish family by default in
M3 dynamic color) at 50% alpha so it reads as "needs attention" not
"diff change". On iOS, `Color(.systemPurple).opacity(0.5)` per the
ADR-013 idiom of hand-tuned per-platform RGBA matching.

### 7. Sync wiring

`SyncEntityType.PULL_REQUEST` and `SyncEntityType.PULL_REQUEST_COMMENT`
added. `SyncExecutor` branch maps:

- PR INSERT → `remote.upsert(pr)`.
- PR UPDATE → `remote.upsert(pr)` (covers `status` change to CLOSED;
  status to MERGED happens via the RPC and is not enqueued through
  PendingSync).
- PR DELETE → no-op (PRs are not deleted; CASCADE on pattern deletion
  cleans them up server-side).
- PR_COMMENT INSERT → `remote.append(comment)`.
- PR_COMMENT UPDATE/DELETE → no-op + log (RLS forbids; defensive
  silent-success per the Phase 37.1 chart_revisions UPDATE/DELETE
  pattern).

**Realtime channels** (5 → 7 per authenticated user):

- `pull-requests-incoming-<ownerId>` filtered by `target_pattern_id IN
  (patterns owned by ownerId)`. Drives the PullRequestListScreen
  Incoming filter and the unread badge.
- `pull-requests-outgoing-<ownerId>` filtered by `author_id = ownerId`.
  Drives the Outgoing filter.
- `pull-request-comments-<prId>` subscribed dynamically when the user
  is on PullRequestDetailScreen; unsubscribed on screen exit. Filters
  comments to a single PR. Avoids the cost of subscribing to *every*
  PR's comments — only the open detail screen's PR.

Channel cleanup on PR transition to MERGED / CLOSED: the dynamic
comments channel for that PR auto-closes via the existing
`CloseRealtimeChannelsUseCase` pattern (Phase 18).

### 8. Phase 38 sub-slice plan

Bounded, mergeable independently. Each sub-slice ships its own commit,
CI verification, test delta, and code review.

| Slice | Scope | Test delta | Migrations | i18n keys |
|---|---|---|---|---|
| **38.0** | This ADR (no code) | 0 | 0 | 0 |
| **38.1** | `pull_requests` + `pull_request_comments` schema, `merge_pull_request` RPC, domain `PullRequest` / `PullRequestStatus` / `PullRequestComment`, `PullRequestRepository` (local + remote + impl), sync wiring, Realtime channels. **No UI yet.** | +25–35 commonTest | 016 | 0 |
| **38.2** | `GetIncomingPullRequestsUseCase`, `GetOutgoingPullRequestsUseCase`, `PullRequestListViewModel`, `PullRequestListScreen` (Compose + SwiftUI), entry points from ProjectList overflow + ProjectDetail. **PR open flow not yet** — list is read-only. | +10 commonTest | 0 | 9 |
| **38.3** | `GetPullRequestUseCase`, `GetPullRequestCommentsUseCase`, `PostPullRequestCommentUseCase`, `OpenPullRequestUseCase`, `ClosePullRequestUseCase`, `PullRequestDetailViewModel`, `PullRequestDetailScreen`, "Open PR" entry point from ChartViewer overflow on a forked pattern. **No merge yet.** | +20–25 commonTest | 0 | 14 |
| **38.4** | `ConflictDetector` algorithm, `ConflictResolutionViewModel`, `ChartConflictResolutionScreen`, `MergePullRequestUseCase` invoking the RPC, merge flow wiring from PullRequestDetailScreen "Merge" button. | +20 commonTest | 0 | 9 |

Each sub-slice updates `CLAUDE.md`'s "Completed" section in the same
commit that ships it. Slices 38.2 / 38.3 / 38.4 form a coherent flow:
list, detail, merge. Slipping 38.4 out of MVP is acceptable
(read-only review with merge blocked behind a "Coming soon"); slipping
38.3 leaves the list inert; slipping 38.2 leaves the data spine
unobservable.

### 9. Realtime + Discovery interaction

PRs are private to participants (RLS in §1) — Discovery does not
surface PRs. A future "this fork has open PRs" badge on the
attribution UI (ADR-012 §1) is post-v1.

When a public pattern's owner merges a PR from a forker, the resulting
revision is publicly readable via `chart_revisions`'s public-pattern
RLS policy (ADR-013 §1). Merge events therefore propagate through the
existing `chart-revisions-<ownerId>` channel for the target owner's
own devices, and through the existing public-read path for any
forker watching upstream. No new public-fan-out is introduced — Phase
38 still bounds Realtime to participants.

### 10. Explicitly NOT in Phase 38 MVP

- "Pull from upstream" affordance (Phase 38+).
- Required-approval gates / branch protection.
- Cross-fork PR routing (PR target is always `source.parentPatternId`).
- Multi-author diff blame ("who last touched this cell" on a merged
  chart) — Phase 38+ builds atop the now-populated `author_id`.
- CRDT concurrent editing (post-v1).
- Cherry-pick, rebase, squash-with-fixup, amend.
- Merge-commit (multi-parent) strategy.
- Editing past comments.
- Draft PR state.
- PR templates / saved descriptions.
- Reactions on comments (emoji / +1).
- @-mentions in comments + push notifications.
- PR diff in the embedded preview rendered at full pan/zoom — the
  detail screen's diff is a small read-only preview; full diff is the
  existing ChartDiffScreen reached via "Open full diff".
- Merge-conflict re-resolution after source drift mid-resolution
  (resolver re-enters the conflict screen with the new source tip;
  this is documented as a "rare edge case" but not specially handled).
- Markdown / formatted comments — plain text + line breaks only.

## Consequences

### Positive

- The collaboration loop closes. The 35.x editor + 36.x discovery +
  37.x history investments are observable end-to-end: a knitter who
  forks a pattern can give their changes back, and the source author
  has a coherent review-and-accept surface.
- `chart_revisions.author_id` finally diverges from `owner_id`. The
  column was provisioned in ADR-013 §1 specifically for this; the
  forward-compat anchor pays off.
- The diff algorithm (ADR-013 §5) is reused unchanged. `ConflictDetector`
  is a pure 3-way wrapper over the existing 2-way diff; no new
  algorithmic primitive at the comparison layer.
- Server-side `merge_pull_request` RPC sets the precedent for
  user-observable transactional writes. The `delete_own_account` RPC
  (ADR-005) is the only prior SECURITY DEFINER function; Phase 38
  cements the pattern for future cross-table atomic operations.
- Squash-only merge default keeps `chart_revisions.parent_revision_id`
  single-valued. No schema change, no multi-parent commit complexity,
  no Git-power-user features that confuse knitters.

### Negative

- Multi-author writes through SECURITY DEFINER carry a privilege-
  escalation risk if the function's validation has a bug. The function
  is the *only* writer that can produce `author_id != owner_id` rows;
  a logic flaw lets a malicious PR contributor spoof authorship.
  Mitigation: function tested at +6–8 RPC tests under the 38.4 budget,
  with focused tests on each WHERE clause and IF guard. Function body
  is small (~50 lines) and amenable to manual review.
- Realtime channel count grows from 5 to 7 per authenticated user.
  Plus a dynamic per-PR channel while the detail screen is open.
  Free-tier connection budget gets tighter; revisit consolidation
  if Phase 39 closed beta surfaces a cap.
- Conflict resolution UI is the most complex new screen in v1. Three
  panes + interactive picker + bottom-bar enable-state synchronization
  + the merge RPC roundtrip. Risk surface for 38.4.
- "Source tip drifted" mid-resolution forces the resolver back to the
  conflict screen — disorienting if the contributor pushed multiple
  edits while the resolver was deciding. v1 surfaces a generic error
  message; better UX (diff between the previous source tip and the
  new one shown alongside) is post-v1.
- Squash collapses the contributor's chain into one commit, losing
  per-edit attribution within the merge. Knitters who care about
  "which contributor edit produced which cell" will not have it.
  Multi-parent merge would preserve this; v1 does not.
- Append-only comments mean a typo-laden comment is permanent until
  the PR is closed (which itself preserves the comment chain). v1
  accepts this for the audit-trail benefit; "soft delete" is post-v1.

### Neutral

- `chart_documents` table unchanged. Tip-pointer semantics from ADR-
  013 §1 still hold — merge moves the tip via the RPC, same shape.
- `chart_revisions` table unchanged structurally. The `author_id`
  column gets its first non-trivial use; no DDL change.
- `chart_branches` table unchanged. The merge RPC reads
  `tip_revision_id` for both source and target and writes the target
  side; no new column.
- `Pattern.parentPatternId` (ADR-012 §1) is the load-bearing pointer
  for "what's the upstream of this fork". Phase 38 reads it; doesn't
  add new attribution infrastructure.
- `EditHistory` (in-memory undo stack, Phase 32) unchanged. Merge does
  not touch the editor's undo stack — it's a write that lands at the
  history layer, not the editor layer.
- Existing fork path (Phase 36.3) unchanged. Forks open PRs against
  their `parentPatternId`; nothing changes about how forks are
  created.

## Considered alternatives

| Alternative | Pros | Cons | Why not chosen |
|---|---|---|---|
| Relax `chart_revisions` INSERT RLS to allow `author_id` matching any open PR's author | No SECURITY DEFINER function needed | Buries cross-table validation in a row-level policy subquery; performance and correctness traps; harder to reason about than a function body | RPC is the right shape for cross-table validation |
| Merge as client-side multi-roundtrip (insert revision, update branch, update PR) without RPC | No new server-side function | Three roundtrips + non-atomic; partial-failure leaves the system in a state where the merge revision exists but the PR is still open, or the PR is merged but the tip wasn't advanced; user-observable inconsistency | RPC's atomicity is load-bearing for merge |
| Merge-commit (multi-parent) as default | Preserves contributor chain in history; matches Git default | `chart_revisions.parent_revision_id` is single-valued; would force a redesign or a sibling table; knitters do not benefit from multi-parent visualization | Squash matches the v1 mental model |
| Fast-forward as a separately-exposed merge strategy | Linear history when applicable | Zero correctness benefit over auto-detection within squash; doubles the testing surface | Auto-detect, don't expose |
| Required-approval gate (target owner + N reviewers) | Matches GitHub's flow | v1 has no concept of "team" or "collaborator role"; a knitter accepting their own contributor's PR has nothing to gate against | v1 is single-approver |
| Conflict resolution as a mode of ChartDiffScreen | One screen, tighter integration | Diff is read-only and 2-pane; conflict is interactive and 3-pane; reusing forces a mode flag that complicates both flows | Separate screen |
| Conflict resolution as a fullscreen modal over ChartDiffScreen | Compositional | Same complexity as a separate screen, but with extra navigation-stack-state to manage | Separate screen, simpler |
| Merge resolver computes the merged document server-side from the resolution map | Thin client | Server-side merge logic is hard to evolve; client already has the catalog and renderers; "thin server, rich client" stance from ADR-001 | Client computes, server validates |
| Comments threaded (parent_comment_id) | Matches GitHub's threaded reviews | Knitting workflows don't have nested-debate culture; flat is enough | Flat for v1 |
| Comments markdown-rendered | Richer expression | Render-target divergence between Compose and SwiftUI; no shared sanitizer; cross-platform parity risk | Plain text v1 |
| Draft PR status | Author iterates before exposing | One more state for a v1 with low collaboration volume; close-and-reopen is a sufficient workaround | Skip for v1 |
| PR open from arbitrary target (not parentPatternId) | Future-flexible | No v1 use case; arbitrary routing complicates RLS and badge counts; locks decisions before they're needed | Restrict to fork-upstream in v1 |
| Comment editing (UPDATE) | More forgiving UX | Edit-after-read silently changes audit trail; append-only matches `chart_revisions` precedent | Append-only |
| Soft delete on comments | Removes mistakes | Adds a `deleted_at` column and a "deleted" state to render; complicates the comment list; v1 accepts the cost of permanence | Skip for v1 |
| `merged_revision_id` as FK with `ON DELETE CASCADE` | Auto-cleanup on revision delete | Revisions are immutable (no DELETE policy in §1); CASCADE never fires; SET NULL is the correct shape | SET NULL |
| Per-PR Realtime channel for the entire PR (not just comments) | Single subscription per PR | Most PR data changes happen through the RPC, not Realtime — channel would mostly carry comment events; explicit comment-only channel is clearer | Comment channel only |
| Markdown-render PR description | Familiar GitHub-like UX | Same render-divergence cost as comments; v1 keeps consistency with comment plain-text | Plain text |
| Merge directly without conflict screen if resolution is trivial | Skips a screen for trivial cases | The "trivial" check is itself a 3-way diff that the resolver already runs; routing the trivial case to a fast-path complicates the flow | Always route through resolver helper; trivial case skips the UI not the algorithm |

## References

- ADR-001: Backend platform (thin server, SECURITY DEFINER pattern from
  the social-layer iteration)
- ADR-005: Account deletion (`delete_own_account` SECURITY DEFINER
  RPC — the precedent for cross-table atomic operations)
- ADR-007: Pivot to chart authoring (Phase 38 framed as the close of
  the collaboration loop)
- ADR-008: Structured chart data model (`revision_id` is the canonical
  commit identifier; ADR-013's §6 globally unique constraint)
- ADR-012: Phase 36 Discovery + fork (`Pattern.parentPatternId` is
  the upstream pointer Phase 38 PR routing reads)
- ADR-013: Phase 37 Collaboration Core (history, branch, diff —
  Phase 38 builds atop §1 `chart_revisions`, §5 diff algorithm,
  §7 `chart_branches`)
- Phase 17 Completed notes: `delete_own_account` RPC implementation
  (the SECURITY DEFINER pattern Phase 38's merge RPC follows)
- `supabase/migrations/015_chart_revisions.sql`: existing
  `chart_revisions` + `chart_branches` schema and RLS that Phase 38
  builds on top of
