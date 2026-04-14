-- Phase 26: Public Pattern Discovery
-- Add index on visibility column for efficient public pattern queries.
-- The RLS policy "Users can read shared or public patterns" (from 002) already permits
-- SELECT access; this index optimises the query path.

CREATE INDEX IF NOT EXISTS idx_patterns_visibility ON public.patterns(visibility);
