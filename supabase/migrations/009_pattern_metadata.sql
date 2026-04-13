-- Phase 22: Pattern Management — add knitting metadata columns
ALTER TABLE public.patterns
    ADD COLUMN IF NOT EXISTS gauge TEXT,
    ADD COLUMN IF NOT EXISTS yarn_info TEXT,
    ADD COLUMN IF NOT EXISTS needle_size TEXT;
