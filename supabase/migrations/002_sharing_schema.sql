-- Knit Note: Sharing Schema
-- Phase 4b-1: Link Sharing MVP
-- Adds patterns and shares tables

-- Patterns (minimal for Phase 4b — no gauge/yarn/needle/chart columns yet)
CREATE TABLE public.patterns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    description TEXT,
    difficulty TEXT,
    visibility TEXT NOT NULL DEFAULT 'private',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_patterns_owner_id ON public.patterns(owner_id);

ALTER TABLE public.patterns ENABLE ROW LEVEL SECURITY;

-- Owner has full access to own patterns
CREATE POLICY "Users can CRUD own patterns"
    ON public.patterns FOR ALL
    USING (owner_id = auth.uid());

-- Shared/public patterns readable by any authenticated user
CREATE POLICY "Users can read shared or public patterns"
    ON public.patterns FOR SELECT
    USING (visibility IN ('shared', 'public'));

-- Reuse existing update_updated_at() trigger function from 001 migration
CREATE TRIGGER set_patterns_updated_at
    BEFORE UPDATE ON public.patterns
    FOR EACH ROW EXECUTE FUNCTION public.update_updated_at();

-- Shares
CREATE TABLE public.shares (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pattern_id UUID NOT NULL REFERENCES public.patterns(id) ON DELETE CASCADE,
    from_user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    to_user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    permission TEXT NOT NULL DEFAULT 'view',
    status TEXT NOT NULL DEFAULT 'accepted',
    share_token TEXT UNIQUE,
    shared_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_shares_pattern_id ON public.shares(pattern_id);
CREATE INDEX idx_shares_from_user_id ON public.shares(from_user_id);
CREATE INDEX idx_shares_to_user_id ON public.shares(to_user_id);
CREATE INDEX idx_shares_share_token ON public.shares(share_token);

ALTER TABLE public.shares ENABLE ROW LEVEL SECURITY;

-- Share creator can manage their own shares
CREATE POLICY "Users can manage own shares"
    ON public.shares FOR ALL
    USING (from_user_id = auth.uid());

-- Recipients can read shares sent to them
CREATE POLICY "Users can read received shares"
    ON public.shares FOR SELECT
    USING (to_user_id = auth.uid());

-- Link shares (with token) are readable by any authenticated user
CREATE POLICY "Authenticated users can read link shares"
    ON public.shares FOR SELECT
    USING (share_token IS NOT NULL);
