-- Knit Note: Social Layer
-- Phase 5a-5b: Comments and Activity Feed
-- Creates comments and activities tables with RLS policies

-- ============================================================
-- COMMENTS TABLE
-- ============================================================

CREATE TABLE public.comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE DEFAULT auth.uid(),
    target_type TEXT NOT NULL CHECK (target_type IN ('pattern', 'project')),
    target_id UUID NOT NULL,
    body TEXT NOT NULL CHECK (char_length(body) > 0 AND char_length(body) <= 2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_comments_target ON public.comments(target_type, target_id);
CREATE INDEX idx_comments_author_id ON public.comments(author_id);

ALTER TABLE public.comments ENABLE ROW LEVEL SECURITY;

-- Users can read comments on targets they can access:
--   1. Own patterns/projects
--   2. Shared/public patterns
--   3. Projects linked to shared patterns (via shares)
CREATE POLICY "Users can read comments on accessible targets"
    ON public.comments FOR SELECT
    USING (
        auth.role() = 'authenticated'
        AND (
            -- Own comments
            author_id = auth.uid()
            -- Comments on own patterns
            OR EXISTS (
                SELECT 1 FROM public.patterns p
                WHERE p.id = target_id AND target_type = 'pattern'
                  AND p.owner_id = auth.uid()
            )
            -- Comments on shared/public patterns
            OR EXISTS (
                SELECT 1 FROM public.patterns p
                WHERE p.id = target_id AND target_type = 'pattern'
                  AND p.visibility IN ('shared', 'public')
            )
            -- Comments on own projects
            OR EXISTS (
                SELECT 1 FROM public.projects pr
                WHERE pr.id = target_id AND target_type = 'project'
                  AND pr.owner_id = auth.uid()
            )
            -- Comments on projects linked to patterns shared with the user
            OR EXISTS (
                SELECT 1 FROM public.projects pr
                JOIN public.shares s ON s.pattern_id = pr.pattern_id::uuid
                WHERE pr.id = target_id AND target_type = 'project'
                  AND (s.to_user_id = auth.uid() OR s.share_token IS NOT NULL)
            )
        )
    );

-- Authors can create comments (author_id defaults to auth.uid() via column default)
CREATE POLICY "Authenticated users can create comments"
    ON public.comments FOR INSERT
    WITH CHECK (auth.role() = 'authenticated' AND author_id = auth.uid());

-- Authors can delete their own comments only
CREATE POLICY "Users can delete own comments"
    ON public.comments FOR DELETE
    USING (author_id = auth.uid());

-- No UPDATE policy — comments are immutable once created

-- Enable Realtime for comments
ALTER PUBLICATION supabase_realtime ADD TABLE public.comments;

-- ============================================================
-- ACTIVITIES TABLE
-- ============================================================

CREATE TABLE public.activities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE DEFAULT auth.uid(),
    type TEXT NOT NULL CHECK (type IN ('shared', 'commented', 'forked', 'completed', 'started')),
    target_type TEXT NOT NULL CHECK (target_type IN ('pattern', 'project')),
    target_id UUID NOT NULL,
    metadata TEXT CHECK (metadata IS NULL OR char_length(metadata) <= 500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_activities_user_id ON public.activities(user_id);
CREATE INDEX idx_activities_created_at ON public.activities(created_at DESC);

ALTER TABLE public.activities ENABLE ROW LEVEL SECURITY;

-- Users can only read their own activities
CREATE POLICY "Users can read own activities"
    ON public.activities FOR SELECT
    USING (user_id = auth.uid());

-- Users can create their own activity entries only
CREATE POLICY "Users can create own activities"
    ON public.activities FOR INSERT
    WITH CHECK (auth.role() = 'authenticated' AND user_id = auth.uid());

-- No UPDATE or DELETE policy — activities are append-only (deleted via CASCADE on profile delete)

-- Enable Realtime for activities
ALTER PUBLICATION supabase_realtime ADD TABLE public.activities;
