-- Phase 14: Add denormalized owner_id to progress table for Realtime filtering
-- Supabase Realtime can only filter on direct table columns, so we denormalize
-- the ownership chain (progress -> project -> owner_id) into a direct column.

BEGIN;

-- Add column (nullable first, then backfill, then set NOT NULL)
ALTER TABLE public.progress ADD COLUMN owner_id UUID REFERENCES public.profiles(id);

-- Backfill from projects
UPDATE public.progress
SET owner_id = (SELECT owner_id FROM public.projects WHERE id = progress.project_id);

-- Set NOT NULL after backfill
ALTER TABLE public.progress ALTER COLUMN owner_id SET NOT NULL;

-- Index for Realtime filtering
CREATE INDEX idx_progress_owner_id ON public.progress(owner_id);

-- Auto-populate owner_id from project on INSERT or UPDATE (upsert path)
CREATE OR REPLACE FUNCTION public.set_progress_owner_id()
RETURNS TRIGGER AS $$
BEGIN
    NEW.owner_id := (SELECT owner_id FROM public.projects WHERE id = NEW.project_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE TRIGGER set_progress_owner_id
    BEFORE INSERT OR UPDATE ON public.progress
    FOR EACH ROW EXECUTE FUNCTION public.set_progress_owner_id();

-- Update RLS policy to use direct owner_id (faster than subquery)
-- Wrapped in same transaction to avoid policy gap
DROP POLICY IF EXISTS "Users can CRUD own progress" ON public.progress;
CREATE POLICY "Users can CRUD own progress"
    ON public.progress FOR ALL
    USING (owner_id = auth.uid())
    WITH CHECK (owner_id = auth.uid());

COMMIT;
