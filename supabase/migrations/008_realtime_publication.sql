-- Phase 19a: Add missing tables to Realtime publication
-- RealtimeSyncManager subscribes to projects, progress, patterns via
-- postgresChangeFlow; ShareRepositoryImpl subscribes to shares.
-- Without publication membership, postgres_changes events are not emitted.

ALTER PUBLICATION supabase_realtime ADD TABLE public.projects;
ALTER PUBLICATION supabase_realtime ADD TABLE public.progress;
ALTER PUBLICATION supabase_realtime ADD TABLE public.patterns;
ALTER PUBLICATION supabase_realtime ADD TABLE public.shares;
