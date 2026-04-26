-- Phase 38.1: Pull request workflow data spine (ADR-014 §1, §2, §5, §7).
--
-- Two new tables — `pull_requests` (the PR entity, references both source +
-- target branches/patterns + the common ancestor revision) and
-- `pull_request_comments` (append-only flat thread) — plus a SECURITY
-- DEFINER `merge_pull_request` RPC that is the sole writer permitted to
-- produce `chart_revisions` rows where `author_id != owner_id`.
--
-- author_id on both tables is nullable with ON DELETE SET NULL so PR rows
-- and comment rows survive author account deletion the same way revisions
-- do (mirrors ADR-013 §1). INSERT-time RLS still enforces author_id =
-- auth.uid() on row creation.

-- =============================================================================
-- pull_requests
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.pull_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Source side: the fork's chart, the contributor's branch + tip.
    -- The two `*_revision_id` FKs target chart_revisions.revision_id (the
    -- standalone UNIQUE column from ADR-013 §1) rather than the table's PK
    -- `id`. This matches the chart_branches.tip_revision_id FK precedent in
    -- migration 015 — `revision_id` is the canonical commit identifier per
    -- ADR-008 §6, and the standalone UNIQUE makes it a valid FK target.
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
    -- the resolver is always target.owner. SET NULL on FK so PR rows survive
    -- account deletion the same way revisions do (ADR-013 §1 precedent).
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
-- reopening (= a new PR row, the prior one stays as CLOSED) is the workflow
-- for "I want to try again with a different message". A plain UNIQUE on
-- (source_branch_id, target_branch_id, status) would also forbid multiple
-- CLOSED rows for the same pair, which is wrong — users close and reopen
-- indefinitely. Partial unique index on the open subset only.
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
        -- pattern). Without this RLS clause, an authenticated user could open
        -- a PR from any pattern they own against any target pattern they
        -- happen to know the UUID of. Internal cross-branch PRs within the
        -- same pattern are rejected by this clause too — v1 routes PRs only
        -- fork → upstream (ADR-014 §1).
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

-- No DELETE policy — PRs are kept as audit trail. CASCADE on pattern
-- deletion is the only cleanup path.

ALTER PUBLICATION supabase_realtime ADD TABLE public.pull_requests;

-- =============================================================================
-- pull_request_comments
-- =============================================================================

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

-- No UPDATE / DELETE policies. Comments are append-only — mirrors
-- chart_revisions (ADR-013 §1) and Git's history immutability invariant.

ALTER PUBLICATION supabase_realtime ADD TABLE public.pull_request_comments;

-- =============================================================================
-- merge_pull_request RPC (ADR-014 §5)
-- =============================================================================
--
-- The only writer permitted to produce chart_revisions rows where
-- author_id != owner_id (the PR contributor's authorship of a row landing on
-- the target owner's pattern). Validates the precondition (PR open, caller
-- is target owner, source tip unchanged), inserts the merged revision,
-- advances the target branch tip + chart_documents tip pointer, and marks
-- the PR row as merged — all atomically.
--
-- FOR UPDATE on the PR row is the load-bearing concurrency mechanism:
-- concurrent merge attempts on the same PR serialize, the second sees
-- status='merged' and bails on the "PR not open" check. The partial unique
-- index on (source_branch_id, target_branch_id) WHERE status = 'open'
-- prevents INSERT-side duplicate OPEN rows; it does not gate the merge
-- race. These two mechanisms are complementary, not redundant.
--
-- HIGH-1 fix: target tip captured into a local variable BEFORE the INSERT
-- so the chart_documents UPDATE does NOT re-read it via subquery against
-- the just-inserted row. INSERT ... RETURNING parent_revision_id INTO
-- ensures the row landed; if the source revision was missing, the INSERT
-- yields zero rows, RETURNING leaves the local NULL, and the equality
-- check raises rather than continuing with a half-applied merge.

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
    v_source_tip_now UUID;
    v_caller UUID := auth.uid();
    v_new_revision_id UUID := p_resolved_revision_id;
    v_target_tip_pre_merge UUID;
    v_inserted_parent_revision_id UUID;
BEGIN
    -- Validate PR existence + caller is target owner. FOR UPDATE serializes
    -- concurrent merges on the same PR; the second caller blocks until the
    -- first commits then sees status='merged' and bails.
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
    -- Captured into a local variable so the chart_documents UPDATE does not
    -- depend on a subquery that could silently NULL out the tip pointer if
    -- a data-integrity gap loses the just-inserted row.
    SELECT tip_revision_id INTO v_target_tip_pre_merge
        FROM public.chart_branches WHERE id = v_pr.target_branch_id;
    IF v_target_tip_pre_merge IS NULL THEN
        RAISE EXCEPTION 'Target branch has no tip; merge precondition failed';
    END IF;

    -- INSERT merged revision. author_id = PR author (the contributor),
    -- owner_id = target.owner. SECURITY DEFINER bypasses the table's INSERT
    -- policy WITH CHECK clause; this function is the *only* writer that can
    -- produce author_id != owner_id rows. RETURNING captures the inserted
    -- row's parent_revision_id into a local so the chart_documents UPDATE
    -- below can verify the INSERT actually landed.
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
        -- Defensive: should be impossible. If the INSERT silently inserted
        -- zero rows (e.g. source revision missing), RETURNING would not
        -- assign and the variable would stay NULL — and the equality check
        -- above would fire. Raises rather than continuing with a
        -- half-applied merge.
        RAISE EXCEPTION 'Merged revision INSERT did not match precondition';
    END IF;

    -- UPDATE target branch tip.
    UPDATE public.chart_branches
        SET tip_revision_id = v_new_revision_id, updated_at = now()
        WHERE id = v_pr.target_branch_id;

    -- UPDATE chart_documents tip pointer for the target pattern. Uses the
    -- captured local v_target_tip_pre_merge directly instead of a subquery
    -- against the just-inserted row — same value, no NULL hazard.
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
