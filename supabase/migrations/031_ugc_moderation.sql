-- Pre-alpha UGC moderation foundation (audit items A1 + A5, 2026-05-12).
--
-- Implements ADR-021 §D1 — the data spine for Apple Guideline 1.2 and
-- Google Play UGC policy compliance:
--
--   1. public.ugc_reports — canonical authority record for content
--      reports (state machine: open → triaging → resolved_*/dismissed).
--      Operators triage via Supabase Dashboard SQL Editor; this slice
--      ships NO in-app Report button (deferred to pre-Phase-40 GA per
--      ADR-021 §D4). Reports route through the submit-ugc-report Edge
--      Function which mirrors a GitHub Issue to b150005/skeinly with
--      label `ugc-report`.
--
--   2. public.user_blocks — symmetric block table. RLS-level filter in
--      migration 032 hides blocked-user content from the blocker's
--      device server-side, before any UI code runs. Block UX surfaces
--      ship at GA; alpha operator inserts on user's behalf via email
--      request to skeinly.app@gmail.com.
--
-- Both tables are pre-alpha foundation. The Edge Function + operator
-- runbook complete the closure of A1 and A5 in the pre-alpha checklist.

BEGIN;

-- =========================================================================
-- Table: public.ugc_reports
-- =========================================================================
-- Per ADR-021 §D1. Reporter identity is captured (FK to auth.users with
-- ON DELETE CASCADE) so the operator can investigate patterns of false
-- reporting; reporter email is NOT denormalized here — operator resolves
-- to email via Dashboard SQL when due-process contact is required.

CREATE TABLE public.ugc_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    target_type TEXT NOT NULL CHECK (target_type IN (
        'pattern', 'comment', 'suggestion', 'suggestion_comment'
    )),
    target_id UUID NOT NULL,
    reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 2000),
    reason_category TEXT NOT NULL CHECK (reason_category IN (
        'spam', 'harassment', 'sexual', 'violence', 'hate', 'ip', 'other'
    )),
    state TEXT NOT NULL DEFAULT 'open' CHECK (state IN (
        'open', 'triaging', 'resolved_remove', 'resolved_keep', 'dismissed'
    )),
    operator_notes TEXT NULL CHECK (operator_notes IS NULL OR length(operator_notes) <= 4000),
    -- URL of the GitHub Issue created by the submit-ugc-report Edge
    -- Function. NULL when the Issue POST failed at submission time
    -- (DB row is canonical; operator can manually backfill or reopen).
    github_issue_url TEXT NULL CHECK (github_issue_url IS NULL OR length(github_issue_url) <= 512),
    resolved_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ugc_reports_state_created ON public.ugc_reports(state, created_at);
CREATE INDEX idx_ugc_reports_reporter ON public.ugc_reports(reporter_id);
CREATE INDEX idx_ugc_reports_target ON public.ugc_reports(target_type, target_id);

-- updated_at touch trigger.
CREATE OR REPLACE FUNCTION public.touch_ugc_reports_updated_at()
    RETURNS TRIGGER
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = ''
    AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ugc_reports_touch_updated_at
    BEFORE UPDATE ON public.ugc_reports
    FOR EACH ROW
    EXECUTE FUNCTION public.touch_ugc_reports_updated_at();

ALTER TABLE public.ugc_reports ENABLE ROW LEVEL SECURITY;

-- INSERT: authenticated user can file reports as themselves.
CREATE POLICY "Users can file own reports"
    ON public.ugc_reports
    FOR INSERT
    TO authenticated
    WITH CHECK (reporter_id = auth.uid());

-- SELECT: authenticated user can read only their own reports.
-- Operator-side queries run as service_role which bypasses RLS.
CREATE POLICY "Users can read own reports"
    ON public.ugc_reports
    FOR SELECT
    TO authenticated
    USING (reporter_id = auth.uid());

-- No UPDATE / DELETE policies for authenticated — operator handles state
-- transitions via Dashboard SQL Editor with service_role.

-- =========================================================================
-- Table: public.user_blocks
-- =========================================================================
-- Composite PK (blocker_id, blocked_id) gives idempotent INSERT semantics
-- — re-blocking the same user is a no-op via ON CONFLICT DO NOTHING.
-- Self-blocking is forbidden by CHECK.

CREATE TABLE public.user_blocks (
    blocker_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);

-- The PK already covers (blocker_id, blocked_id) lookups (Postgres
-- builds a unique index automatically). We add a separate index on
-- blocked_id for the inverse lookup direction used by the user_blocks
-- NOT-EXISTS arm in migration 032 (where the filter joins on
-- ub.blocked_id = <target_table>.author_id|owner_id and ub.blocker_id =
-- auth.uid() — Postgres can use the PK for that as long as blocker_id
-- is the leading column, but adding a blocked_id index keeps options
-- open for future inverse queries like "who has blocked me?").
CREATE INDEX idx_user_blocks_blocked ON public.user_blocks(blocked_id);

ALTER TABLE public.user_blocks ENABLE ROW LEVEL SECURITY;

-- INSERT: authenticated user can create blocks as themselves.
CREATE POLICY "Users can create own blocks"
    ON public.user_blocks
    FOR INSERT
    TO authenticated
    WITH CHECK (blocker_id = auth.uid());

-- SELECT: authenticated user can read only their own blocks (the list
-- of users they have blocked). Note: blocked users canNOT enumerate
-- who has blocked them — by design (privacy of the blocker).
CREATE POLICY "Users can read own blocks"
    ON public.user_blocks
    FOR SELECT
    TO authenticated
    USING (blocker_id = auth.uid());

-- DELETE: authenticated user can unblock users they previously blocked.
CREATE POLICY "Users can delete own blocks"
    ON public.user_blocks
    FOR DELETE
    TO authenticated
    USING (blocker_id = auth.uid());

-- No UPDATE policy — blocks are insert-or-delete; modifying an existing
-- row makes no semantic sense.

COMMIT;
