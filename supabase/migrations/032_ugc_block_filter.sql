-- Pre-alpha UGC moderation foundation — RLS-level block filter
-- (audit items A1 + A5, 2026-05-12).
--
-- Implements ADR-021 §D2 — server-side filter so blocked-user content
-- never reaches the blocker's device. Amends four SELECT policies to
-- add a NOT EXISTS arm against public.user_blocks (introduced by
-- migration 031). The amendment is structural: when the policy returns
-- rows, none of them belong to a user the caller has blocked.
--
-- Defense in depth: the GA-time Block User UI (per ADR-021 §D4) will
-- gate client-side rendering as a UX nicety, but the RLS filter is
-- the actual guarantee — anyone bypassing the UI (curl, custom client)
-- still cannot read blocked-user content.
--
-- All four amendments use DROP POLICY + CREATE POLICY to preserve
-- transactional atomicity (DROP without CREATE would leave the table
-- unreadable until the matching CREATE landed).

BEGIN;

-- =========================================================================
-- patterns: amend "Users can read shared or public patterns".
-- =========================================================================
-- Block path: hide patterns whose owner is blocked by the caller.
-- Owner-of-own-patterns (FOR ALL policy "Users can CRUD own patterns")
-- is untouched — you can't block yourself (CHECK constraint in 031).

DROP POLICY IF EXISTS "Users can read shared or public patterns" ON public.patterns;

CREATE POLICY "Users can read shared or public patterns"
    ON public.patterns
    FOR SELECT
    USING (
        (visibility = ANY (ARRAY['shared'::text, 'public'::text]))
        AND NOT EXISTS (
            SELECT 1 FROM public.user_blocks ub
            WHERE ub.blocker_id = auth.uid()
              AND ub.blocked_id = patterns.owner_id
        )
    );

-- =========================================================================
-- comments: amend "Users can read comments on accessible targets"
-- (the migration-030-hardened policy).
-- =========================================================================
-- Block path: hide comments whose author is blocked. Independent of
-- whether the comment lives on a pattern or project I own — if I block
-- User X, I never want to see X's words anywhere.
--
-- Full re-statement of the qual from 030 + the new NOT EXISTS arm at
-- the end, ANDed with the outer auth.role() = 'authenticated' check.

DROP POLICY IF EXISTS "Users can read comments on accessible targets" ON public.comments;

CREATE POLICY "Users can read comments on accessible targets"
    ON public.comments
    FOR SELECT
    USING (
        auth.role() = 'authenticated'
        AND (
            author_id = auth.uid()
            OR EXISTS (
                SELECT 1 FROM public.patterns p
                WHERE p.id = comments.target_id
                  AND comments.target_type = 'pattern'
                  AND p.owner_id = auth.uid()
            )
            OR EXISTS (
                SELECT 1 FROM public.patterns p
                WHERE p.id = comments.target_id
                  AND comments.target_type = 'pattern'
                  AND p.visibility = ANY (ARRAY['shared'::text, 'public'::text])
            )
            OR EXISTS (
                SELECT 1 FROM public.projects pr
                WHERE pr.id = comments.target_id
                  AND comments.target_type = 'project'
                  AND pr.owner_id = auth.uid()
            )
            OR EXISTS (
                SELECT 1
                FROM public.projects pr
                JOIN public.shares s ON s.pattern_id = pr.pattern_id::uuid
                WHERE pr.id = comments.target_id
                  AND comments.target_type = 'project'
                  AND s.to_user_id = auth.uid()
            )
        )
        AND NOT EXISTS (
            SELECT 1 FROM public.user_blocks ub
            WHERE ub.blocker_id = auth.uid()
              AND ub.blocked_id = comments.author_id
        )
    );

-- =========================================================================
-- suggestions: amend "PR readable by source or target owner".
-- =========================================================================
-- Block path: hide suggestions whose author is blocked. Note: target
-- pattern owner can still block the suggestion author, in which case
-- they won't see the suggestion (and won't be able to comment on /
-- merge it without first unblocking) — this is the intended UX for
-- "I no longer want any interaction with this person".

DROP POLICY IF EXISTS "PR readable by source or target owner" ON public.suggestions;

CREATE POLICY "PR readable by source or target owner"
    ON public.suggestions
    FOR SELECT
    USING (
        (
            author_id = auth.uid()
            OR target_pattern_id IN (
                SELECT patterns.id
                FROM public.patterns
                WHERE patterns.owner_id = auth.uid()
            )
        )
        AND NOT EXISTS (
            SELECT 1 FROM public.user_blocks ub
            WHERE ub.blocker_id = auth.uid()
              AND ub.blocked_id = suggestions.author_id
        )
    );

-- =========================================================================
-- suggestion_comments: amend "Comments readable by PR participants".
-- =========================================================================
-- Block path: hide suggestion comments whose author is blocked.

DROP POLICY IF EXISTS "Comments readable by PR participants" ON public.suggestion_comments;

CREATE POLICY "Comments readable by PR participants"
    ON public.suggestion_comments
    FOR SELECT
    USING (
        pull_request_id IN (
            SELECT suggestions.id
            FROM public.suggestions
            WHERE suggestions.author_id = auth.uid()
               OR suggestions.target_pattern_id IN (
                    SELECT patterns.id
                    FROM public.patterns
                    WHERE patterns.owner_id = auth.uid()
               )
        )
        AND NOT EXISTS (
            SELECT 1 FROM public.user_blocks ub
            WHERE ub.blocker_id = auth.uid()
              AND ub.blocked_id = suggestion_comments.author_id
        )
    );

COMMIT;
