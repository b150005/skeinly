-- Phase D Terminology Audit (2026-05-10) — suggestion workflow renames.
-- ADR-014 amendment: "Pull request" → "Suggestion" (UI: 「提案」),
--                    "Merge" → "Apply" (UI: 「変更を反映」),
--                    status enum 'merged' → 'applied'.
--
-- Scope: TABLE-level renames + RPC rename + status enum value rename.
-- Internal column names (source_branch_id, target_branch_id,
-- source_tip_revision_id, common_ancestor_revision_id,
-- merged_revision_id, merged_at, pull_request_id) are NOT renamed.
-- Same rationale as Migration 026: internal artifacts, no user-visible
-- payoff vs. cascading refactor cost in supabase-kt queries.
--
-- Renames in this migration:
-- - pull_requests → suggestions  (table)
-- - pull_request_comments → suggestion_comments  (table)
-- - status enum value: 'merged' → 'applied'  (USER-VISIBLE — surfaces in UI as
--   the status chip text)
-- - merge_pull_request RPC → apply_suggestion (with renamed argument names
--   to match the new function name)

-- =============================================================================
-- pull_requests → suggestions  (table only)
-- =============================================================================

ALTER TABLE public.pull_requests RENAME TO suggestions;

ALTER INDEX IF EXISTS idx_pull_requests_unique_open
    RENAME TO idx_suggestions_unique_open;
ALTER INDEX IF EXISTS idx_pull_requests_target_pattern_status
    RENAME TO idx_suggestions_target_pattern_status;
ALTER INDEX IF EXISTS idx_pull_requests_source_pattern_status
    RENAME TO idx_suggestions_source_pattern_status;
ALTER INDEX IF EXISTS idx_pull_requests_author_status
    RENAME TO idx_suggestions_author_status;

-- status enum value rename: 'merged' → 'applied'.
-- Drop CHECK constraint, UPDATE rows in place, recreate CHECK.
-- Pre-v1 breaking change permits beta-tester data conversion in place.
ALTER TABLE public.suggestions DROP CONSTRAINT IF EXISTS pull_requests_status_check;
UPDATE public.suggestions SET status = 'applied' WHERE status = 'merged';
ALTER TABLE public.suggestions ADD CONSTRAINT suggestions_status_check
    CHECK (status IN ('open', 'applied', 'closed'));

-- =============================================================================
-- pull_request_comments → suggestion_comments  (table only)
-- =============================================================================

ALTER TABLE public.pull_request_comments RENAME TO suggestion_comments;

ALTER INDEX IF EXISTS idx_pull_request_comments_pr_id_created
    RENAME TO idx_suggestion_comments_suggestion_id_created;

-- =============================================================================
-- merge_pull_request → apply_suggestion (RPC rename + argument rename)
-- =============================================================================
--
-- Body references chart_versions / chart_variations / suggestions tables
-- (already renamed in 026 + above). Column names within those tables
-- are unchanged, so the body's column references are unchanged.

DROP FUNCTION IF EXISTS public.merge_pull_request(UUID, TEXT, JSONB, TEXT, UUID);

CREATE OR REPLACE FUNCTION public.apply_suggestion(
    p_suggestion_id UUID,
    p_strategy TEXT,
    p_applied_document JSONB,
    p_applied_content_hash TEXT,
    p_resolved_revision_id UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_suggestion public.suggestions;
    v_source_tip_now UUID;
    v_caller UUID := auth.uid();
    v_new_revision_id UUID := p_resolved_revision_id;
    v_target_tip_pre_apply UUID;
    v_inserted_parent_revision_id UUID;
BEGIN
    SELECT * INTO v_suggestion FROM public.suggestions WHERE id = p_suggestion_id FOR UPDATE;
    IF v_suggestion IS NULL THEN RAISE EXCEPTION 'Suggestion not found'; END IF;
    IF v_suggestion.status != 'open' THEN RAISE EXCEPTION 'Suggestion not open'; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.patterns
        WHERE id = v_suggestion.target_pattern_id AND owner_id = v_caller
    ) THEN
        RAISE EXCEPTION 'Caller is not target owner';
    END IF;

    IF p_strategy NOT IN ('squash', 'fast_forward') THEN
        RAISE EXCEPTION 'Unsupported strategy: %', p_strategy;
    END IF;

    SELECT tip_revision_id INTO v_source_tip_now
        FROM public.chart_variations WHERE id = v_suggestion.source_branch_id;
    IF v_source_tip_now IS DISTINCT FROM v_suggestion.source_tip_revision_id THEN
        RAISE EXCEPTION 'Source tip drifted; re-resolve required';
    END IF;

    SELECT tip_revision_id INTO v_target_tip_pre_apply
        FROM public.chart_variations WHERE id = v_suggestion.target_branch_id;
    IF v_target_tip_pre_apply IS NULL THEN
        RAISE EXCEPTION 'Target variation has no tip; apply precondition failed';
    END IF;

    INSERT INTO public.chart_versions (
        revision_id, pattern_id, owner_id, author_id,
        schema_version, storage_variant, coordinate_system,
        document, parent_revision_id, content_hash,
        commit_message, created_at
    )
    SELECT
        v_new_revision_id,
        v_suggestion.target_pattern_id,
        v_caller,
        v_suggestion.author_id,
        cv.schema_version, cv.storage_variant, cv.coordinate_system,
        p_applied_document,
        v_target_tip_pre_apply,
        p_applied_content_hash,
        v_suggestion.title,
        now()
    FROM public.chart_versions cv
    WHERE cv.revision_id = v_suggestion.source_tip_revision_id
    RETURNING parent_revision_id INTO v_inserted_parent_revision_id;

    IF v_inserted_parent_revision_id IS DISTINCT FROM v_target_tip_pre_apply THEN
        RAISE EXCEPTION 'Applied version INSERT did not match precondition';
    END IF;

    UPDATE public.chart_variations
        SET tip_revision_id = v_new_revision_id, updated_at = now()
        WHERE id = v_suggestion.target_branch_id;

    UPDATE public.chart_documents
        SET revision_id = v_new_revision_id,
            parent_revision_id = v_target_tip_pre_apply,
            content_hash = p_applied_content_hash,
            document = p_applied_document,
            updated_at = now()
        WHERE pattern_id = v_suggestion.target_pattern_id;

    UPDATE public.suggestions
        SET status = 'applied',
            merged_revision_id = v_new_revision_id,
            merged_at = now(),
            updated_at = now()
        WHERE id = p_suggestion_id;

    RETURN v_new_revision_id;
END;
$$;

REVOKE ALL ON FUNCTION public.apply_suggestion FROM public;
GRANT EXECUTE ON FUNCTION public.apply_suggestion TO authenticated;

-- Post-apply user-side actions (NOT autonomously executable):
-- 1. Redeploy `notify-on-write` Edge Function: it switches on the
--    payload's `table` field. After this migration, payloads carry
--    `table: "suggestions"` / `"suggestion_comments"` instead of the
--    old names. The mapping.ts dispatch logic must be updated to
--    recognize these. Update + `supabase functions deploy notify-on-write`.
-- 2. Verify Database Webhooks in Supabase Dashboard. Postgres
--    triggers follow the table by OID across rename, but the
--    Dashboard's source-table dropdown may need refresh.
-- 3. The CLIENT side (Kotlin shared module via supabase-kt) sends
--    queries against the new table names — apply this migration BEFORE
--    rolling out client builds that reference `from("suggestions")`.

-- Verify post-apply (manual SQL):
--   \d public.suggestions
--   \d public.suggestion_comments
--   SELECT proname FROM pg_proc WHERE proname = 'apply_suggestion';
--   SELECT proname FROM pg_proc WHERE proname = 'merge_pull_request';  -- expect zero
--   SELECT DISTINCT status FROM public.suggestions;  -- expect open/applied/closed
