-- Pre-alpha security hardening (audit items A10–A13, 2026-05-12).
--
-- Bundles four orthogonal hardening changes that all surfaced in the
-- Supabase database-linter advisor scan and the manual RLS audit:
--
--   A10 — Lock search_path on the 4 functions still flagged by lint
--         0011 (function_search_path_mutable). Prevents identifier
--         hijack via a malicious schema being prepended to the
--         resolution path; aligned with previously hardened functions
--         (apply_suggestion, grant_alpha_pro, upsert_subscription_from_webhook,
--         touch_app_config_updated_at).
--
--   A11 — Revoke EXECUTE on internal SECURITY DEFINER functions from
--         the public-callable roles (anon, authenticated, PUBLIC).
--         Closes lint 0028 / 0029 — these functions were inadvertently
--         exposed at /rest/v1/rpc/ via PostgREST's default GRANT to
--         anon + authenticated when CREATE FUNCTION ran.
--
--         Three categories of SECURITY DEFINER functions exist:
--           (a) intentionally public → keep both roles
--                 - get_app_config()           (force-update gate, pre-sign-in)
--           (b) authenticated-only RPCs → revoke from anon only
--                 - apply_suggestion(...)       (Phase 38 PR apply flow)
--                 - delete_own_account()        (ADR-005)
--                 - is_pro(uid)                 (RLS policy dependency)
--           (c) internal-only (triggers / admin / webhook) → revoke both
--                 - grant_alpha_pro(uid)
--                 - handle_new_user()
--                 - rls_auto_enable()
--                 - set_progress_owner_id()
--                 - touch_app_config_updated_at()
--                 - upsert_subscription_from_webhook(...)
--
--         Trigger functions do NOT require EXECUTE on the triggering
--         role — Postgres invokes them as part of the table operation
--         regardless of grants. Webhook RPCs (upsert_subscription_*) run
--         under service_role which bypasses ACL checks.
--
--   A12 — Tighten the public.comments SELECT policy to remove the bare
--         `share_token IS NOT NULL` arm. The original arm allowed any
--         authenticated user to read comments on a project as long as
--         *some* token-share existed on the underlying pattern — without
--         the caller having to present the token. Replaced with an
--         explicit share-recipient check (s.to_user_id = auth.uid()).
--         Token-based viewers (anonymous link recipients) do not see
--         comments by design; this matches the UX where token-share is
--         read-only "view this pattern" rather than full engagement.
--
--   A13 — Drop the broad "Anyone can read avatars" SELECT policy on
--         storage.objects. The avatars bucket is marked public = true,
--         so URL-based reads continue to work via Supabase Storage's
--         HTTP API without an RLS SELECT policy. The previous policy
--         additionally enabled `from('avatars').list()` enumeration —
--         which the app never uses — exposing every avatar file path
--         to any authenticated client. Closes lint 0025
--         (public_bucket_allows_listing).

BEGIN;

-- =========================================================================
-- A10. Lock search_path on remaining mutable functions.
-- =========================================================================
-- All four functions either reference no schema-qualified objects
-- (now() is in pg_catalog which is implicitly searched), or already
-- fully qualify their references (public.profiles, public.projects).
-- Setting search_path to '' is safe and is the strictest configuration.

ALTER FUNCTION public.handle_new_user()              SET search_path = '';
ALTER FUNCTION public.set_progress_owner_id()        SET search_path = '';
ALTER FUNCTION public.touch_subscriptions_updated_at() SET search_path = '';
ALTER FUNCTION public.update_updated_at()            SET search_path = '';

-- =========================================================================
-- A11. Revoke EXECUTE on SECURITY DEFINER functions from public-callable
-- roles per the matrix in the header comment above.
-- =========================================================================

-- (c) Internal-only: revoke from anon + authenticated + PUBLIC.
REVOKE EXECUTE ON FUNCTION public.grant_alpha_pro(uuid)            FROM anon, authenticated, PUBLIC;
REVOKE EXECUTE ON FUNCTION public.handle_new_user()                FROM anon, authenticated, PUBLIC;
REVOKE EXECUTE ON FUNCTION public.rls_auto_enable()                FROM anon, authenticated, PUBLIC;
REVOKE EXECUTE ON FUNCTION public.set_progress_owner_id()          FROM anon, authenticated, PUBLIC;
REVOKE EXECUTE ON FUNCTION public.touch_app_config_updated_at()    FROM anon, authenticated, PUBLIC;
REVOKE EXECUTE ON FUNCTION public.upsert_subscription_from_webhook(
    uuid, text, text, text, text, timestamptz, timestamptz, boolean, boolean, jsonb, text
) FROM anon, authenticated, PUBLIC;

-- (b) Authenticated-only: revoke from anon only.
REVOKE EXECUTE ON FUNCTION public.apply_suggestion(
    uuid, text, jsonb, text, uuid
) FROM anon;
REVOKE EXECUTE ON FUNCTION public.delete_own_account() FROM anon;
REVOKE EXECUTE ON FUNCTION public.is_pro(uuid)         FROM anon;

-- =========================================================================
-- A12. Tighten public.comments SELECT policy — remove `share_token IS NOT NULL`
-- arm that leaked comments to any authenticated user on token-shared projects.
-- =========================================================================

DROP POLICY IF EXISTS "Users can read comments on accessible targets" ON public.comments;

CREATE POLICY "Users can read comments on accessible targets"
    ON public.comments
    FOR SELECT
    USING (
        auth.role() = 'authenticated'
        AND (
            -- Author of the comment.
            author_id = auth.uid()
            -- Pattern owner.
            OR EXISTS (
                SELECT 1 FROM public.patterns p
                WHERE p.id = comments.target_id
                  AND comments.target_type = 'pattern'
                  AND p.owner_id = auth.uid()
            )
            -- Comments on shared / public patterns: visible to any
            -- authenticated user.
            OR EXISTS (
                SELECT 1 FROM public.patterns p
                WHERE p.id = comments.target_id
                  AND comments.target_type = 'pattern'
                  AND p.visibility = ANY (ARRAY['shared'::text, 'public'::text])
            )
            -- Project owner.
            OR EXISTS (
                SELECT 1 FROM public.projects pr
                WHERE pr.id = comments.target_id
                  AND comments.target_type = 'project'
                  AND pr.owner_id = auth.uid()
            )
            -- Explicit share recipient on the underlying pattern.
            -- (Removed: the prior `OR s.share_token IS NOT NULL` arm.)
            OR EXISTS (
                SELECT 1
                FROM public.projects pr
                JOIN public.shares s ON s.pattern_id = pr.pattern_id::uuid
                WHERE pr.id = comments.target_id
                  AND comments.target_type = 'project'
                  AND s.to_user_id = auth.uid()
            )
        )
    );

-- =========================================================================
-- A13. Drop broad "Anyone can read avatars" SELECT policy.
-- =========================================================================
-- The avatars bucket has public = true, so the HTTP API serves files via
-- the public URL path without consulting RLS. Authenticated upload /
-- update / delete continue to work because the three remaining policies
-- (Users can upload own avatar / Users can update own avatar / Users can
-- delete own avatar) scope to the user's own folder by the
-- `auth.uid()::text = (storage.foldername(name))[1]` predicate.

DROP POLICY IF EXISTS "Anyone can read avatars" ON storage.objects;

COMMIT;
