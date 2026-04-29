-- Phase F3: In-app feedback collection (alpha1 learning loop)
-- Settings > Send Feedback writes a row here. Append-only — no UPDATE
-- or DELETE policies — so the feedback log is tamper-evident on the
-- client side. Admin reads happen via service-role queries on the
-- backend (or via the Supabase Studio table view).
--
-- Phase F1 (Sentry) ID is captured at submit time so we can correlate
-- a user-reported issue with the most recent crash session that user
-- experienced. The session ID is opaque; no PII is leaked into the
-- feedback row beyond what the user typed.

CREATE TABLE IF NOT EXISTS public.feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- ON DELETE SET NULL: account deletion preserves the feedback
    -- record (admins still see the body + version) but anonymizes
    -- the link to the deleted account.
    user_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    body TEXT NOT NULL CHECK (length(trim(body)) >= 1 AND length(body) <= 5000),
    platform TEXT CHECK (platform IN ('ios', 'android')),
    app_version TEXT CHECK (length(app_version) <= 32),
    -- Phase F1 correlation key. Format: `<sentry-event-id>` or
    -- `<sentry-session-id>`. NULL when Sentry is opted-out or unwired.
    sentry_session_id TEXT CHECK (length(sentry_session_id) <= 64),
    -- Phase F2 (PostHog) distinct_id for analytics correlation. NULL
    -- when analytics is opt-out (default).
    posthog_distinct_id TEXT CHECK (length(posthog_distinct_id) <= 64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Time-ordered admin browsing — most-recent-first is the default
-- read pattern for triage.
CREATE INDEX IF NOT EXISTS idx_feedback_created_at
    ON public.feedback (created_at DESC);

-- ---------------------------------------------------------------------
-- Row-Level Security
-- ---------------------------------------------------------------------
ALTER TABLE public.feedback ENABLE ROW LEVEL SECURITY;

-- INSERT: authenticated users submit feedback for themselves only.
-- The `auth.uid() = user_id` check prevents user-impersonation INSERTs.
CREATE POLICY "feedback_insert_own"
    ON public.feedback FOR INSERT
    WITH CHECK (auth.uid() = user_id);

-- SELECT: users see only their own feedback (so they can confirm
-- submission). Admin reads happen via service-role bypass.
CREATE POLICY "feedback_select_own"
    ON public.feedback FOR SELECT
    USING (auth.uid() = user_id);

-- INTENTIONALLY NO UPDATE / DELETE policies — feedback is append-only.
-- If a user submits accidentally, they can submit a follow-up
-- correction; they cannot redact prior submissions.

-- ---------------------------------------------------------------------
-- Realtime: NOT enabled.
-- ---------------------------------------------------------------------
-- Feedback is a one-shot user-to-admin write — there's no client-side
-- consumer that benefits from live updates. Admin triage happens via
-- Supabase Studio or a future admin dashboard.
