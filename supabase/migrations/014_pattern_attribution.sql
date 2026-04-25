-- Phase 36 spine: Pattern fork attribution (ADR-012 §1)
-- `parent_pattern_id` is set at fork time (Phase 36.3) so the forked Pattern
-- carries a one-hop link back to its source. ON DELETE SET NULL preserves
-- the fork after the source is deleted (orphaned forks exist by design;
-- attribution UI in Phase 36.5 falls back to text-only when the link
-- resolves to null).

ALTER TABLE public.patterns
    ADD COLUMN IF NOT EXISTS parent_pattern_id UUID REFERENCES public.patterns(id) ON DELETE SET NULL;

-- Partial index because most rows are NOT forks; reverse-lookup ("how many
-- forks does this pattern have") will be rare relative to row count.
CREATE INDEX IF NOT EXISTS idx_patterns_parent_pattern_id
    ON public.patterns(parent_pattern_id)
    WHERE parent_pattern_id IS NOT NULL;
