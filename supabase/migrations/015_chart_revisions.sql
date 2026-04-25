-- Phase 37.1: Collaboration Core — chart_revisions + chart_branches (ADR-013)
--
-- chart_revisions is an append-only sibling of chart_documents. Every chart
-- save now writes a new immutable row here, then advances chart_documents
-- as the tip pointer. The previous "overwrite-in-place" model is preserved
-- on chart_documents itself (UNIQUE on pattern_id, latest revision_id only)
-- so existing read paths and the chart-documents-<ownerId> Realtime channel
-- continue to work unchanged.
--
-- author_id is provisioned for Phase 38 PR/merge (multi-author commits) and
-- is intentionally nullable with ON DELETE SET NULL so revision rows outlive
-- author account deletion. INSERT-time RLS still enforces author_id = auth.uid().

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
    -- chart_branches.tip_revision_id below; the composite UNIQUE additionally
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

-- No UPDATE or DELETE policies — revisions are immutable once written
-- (mirrors Git's append-only history invariant).
CREATE POLICY "Users can read own chart revisions"
    ON public.chart_revisions FOR SELECT
    USING (owner_id = auth.uid());

CREATE POLICY "Users can insert own chart revisions"
    ON public.chart_revisions FOR INSERT
    WITH CHECK (owner_id = auth.uid() AND author_id = auth.uid());

-- Public read parity with chart_documents.
CREATE POLICY "Public chart revisions readable"
    ON public.chart_revisions FOR SELECT
    USING (
        pattern_id IN (
            SELECT id FROM public.patterns WHERE visibility = 'public'
        )
    );

ALTER PUBLICATION supabase_realtime ADD TABLE public.chart_revisions;

-- chart_branches is reserved in 37.1; UI lands in 37.4.
-- "main" is auto-created on first save by StructuredChartRepository.create
-- and again on forkFor's clone (so a forked pattern always has a usable
-- 'main' branch from the first load — without this the 37.4 branch picker
-- would surface no branches on forks).

CREATE TABLE IF NOT EXISTS public.chart_branches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pattern_id UUID NOT NULL REFERENCES public.patterns(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    branch_name TEXT NOT NULL,
    -- Hard FK to chart_revisions.revision_id (the commit id, globally unique
    -- per ADR-008 §6 and the standalone UNIQUE above) with ON DELETE RESTRICT.
    -- Revisions are immutable by RLS (no DELETE policy), so RESTRICT is
    -- structurally safe and enforces the invariant that a branch tip cannot
    -- dangle. This is stricter than the soft mirror between
    -- chart_documents.revision_id and chart_revisions.revision_id (ADR-013 §4)
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

ALTER PUBLICATION supabase_realtime ADD TABLE public.chart_branches;
