-- Phase 29: Structured Chart Data Model (ADR-008)
-- One `chart_documents` row per pattern holds the structured chart as a jsonb blob.
-- `storage_variant = 'inline'` in Phase 29; future 'chunked' variant reserves the escape
-- hatch for AI-imported charts that exceed a single jsonb row.
-- `schema_version` lets the in-document layout evolve without a table reshape.

CREATE TABLE IF NOT EXISTS public.chart_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pattern_id UUID NOT NULL UNIQUE REFERENCES public.patterns(id) ON DELETE CASCADE,
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
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chart_documents_pattern_id ON public.chart_documents(pattern_id);
CREATE INDEX IF NOT EXISTS idx_chart_documents_owner_id ON public.chart_documents(owner_id);

ALTER TABLE public.chart_documents ENABLE ROW LEVEL SECURITY;

-- Owner has full access to their own charts.
CREATE POLICY "Users can CRUD own chart documents"
    ON public.chart_documents FOR ALL
    USING (owner_id = auth.uid())
    WITH CHECK (owner_id = auth.uid());

-- Authenticated users can read chart documents whose parent pattern is public.
CREATE POLICY "Public chart documents readable"
    ON public.chart_documents FOR SELECT
    USING (
        pattern_id IN (
            SELECT id FROM public.patterns WHERE visibility = 'public'
        )
    );

-- Auto-update updated_at (function defined in 001_initial_schema.sql).
CREATE TRIGGER set_updated_at
    BEFORE UPDATE ON public.chart_documents
    FOR EACH ROW EXECUTE FUNCTION public.update_updated_at();

-- Realtime subscriptions so peer devices receive chart edits.
ALTER PUBLICATION supabase_realtime ADD TABLE public.chart_documents;
