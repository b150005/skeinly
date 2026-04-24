-- Phase 34: Per-Segment Progress (ADR-010)
-- Per-project per-stitch progress rows. Absence of a row means the segment
-- is in the implicit `todo` state (ADR-010 §2), so storage scales with
-- progress made, not chart size.
--
-- Progress is ALWAYS private to each user, even when the parent pattern is
-- public. The RLS policy here is owner-only with no public-read escape —
-- unlike `chart_documents`, there is no "public progress readable" policy.

CREATE TABLE IF NOT EXISTS public.project_segments (
    id TEXT PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES public.projects(id) ON DELETE CASCADE,
    layer_id TEXT NOT NULL,
    cell_x INTEGER NOT NULL,
    cell_y INTEGER NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('wip', 'done')),
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(project_id, layer_id, cell_x, cell_y)
);

-- ADR-010 §3: id is deterministic (`seg:<projectId>:<layerId>:<x>:<y>`),
-- so we keep it TEXT rather than UUID to avoid a format round-trip on every
-- sync. Client is the sole author of this id.

CREATE INDEX IF NOT EXISTS idx_project_segments_project_id
    ON public.project_segments(project_id);
CREATE INDEX IF NOT EXISTS idx_project_segments_owner_id
    ON public.project_segments(owner_id);

ALTER TABLE public.project_segments ENABLE ROW LEVEL SECURITY;

-- Owner-only CRUD. No public-read policy — progress stays private even when
-- the parent pattern is public and the chart is shared.
CREATE POLICY "Users can CRUD own project segments"
    ON public.project_segments FOR ALL
    USING (owner_id = auth.uid())
    WITH CHECK (owner_id = auth.uid());

-- Auto-update updated_at (function defined in 001_initial_schema.sql).
CREATE TRIGGER set_updated_at
    BEFORE UPDATE ON public.project_segments
    FOR EACH ROW EXECUTE FUNCTION public.update_updated_at();

-- Realtime so second device receives per-stitch updates within the
-- ADR-010 §6 / AC-6.1 5-second latency target.
ALTER PUBLICATION supabase_realtime ADD TABLE public.project_segments;
