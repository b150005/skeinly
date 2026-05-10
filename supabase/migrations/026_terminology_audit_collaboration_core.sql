-- Phase D Terminology Audit (2026-05-10) — collaboration core renames.
-- ADR-013 amendment: "Branch" → "Variation" (UI: 「アレンジ」),
--                    "Revision" / "Commit" → "Version" (UI: 「バージョン」).
--
-- Scope: TABLE-level renames only. Internal column names (revision_id,
-- branch_name, tip_revision_id, parent_revision_id, commit_message etc.)
-- are NOT renamed. Reason: those are internal artifacts that never
-- surface to end users; renaming them would cascade through every
-- supabase-kt query in the Kotlin shared module without user-visible
-- payoff. The user-visible payoff is captured at the table-name level
-- + the user-visible status enum value rename (Migration 027 §status).
--
-- Pre-v1 breaking-change policy permits this rename per CLAUDE.md
-- `### Planned — Phase 39` HARD-GATE.
--
-- Renames in this migration:
-- - chart_revisions → chart_versions  (table only)
-- - chart_branches → chart_variations (table only)
--
-- Postgres FK + index + RLS policy + supabase_realtime publication
-- entries follow the rename automatically (referenced by OID, not
-- by name) — verified at apply time.
--
-- The merge_pull_request RPC (created in 016) references both renamed
-- tables. Its body needs updating to use the new table names. We
-- CREATE OR REPLACE the function with adjusted FROM/INTO references
-- at the END of this migration so the RPC continues to work between
-- Migration 026 and Migration 027 (which renames the function itself
-- to apply_suggestion + adjusts argument names).

-- =============================================================================
-- chart_revisions → chart_versions  (table only)
-- =============================================================================

ALTER TABLE public.chart_revisions RENAME TO chart_versions;

ALTER INDEX IF EXISTS idx_chart_revisions_pattern_id_created_at
    RENAME TO idx_chart_versions_pattern_id_created_at;
ALTER INDEX IF EXISTS idx_chart_revisions_revision_id
    RENAME TO idx_chart_versions_revision_id;
ALTER INDEX IF EXISTS idx_chart_revisions_parent_revision_id
    RENAME TO idx_chart_versions_parent_revision_id;

-- =============================================================================
-- chart_branches → chart_variations  (table only)
-- =============================================================================

ALTER TABLE public.chart_branches RENAME TO chart_variations;

ALTER INDEX IF EXISTS idx_chart_branches_pattern_id
    RENAME TO idx_chart_variations_pattern_id;

-- =============================================================================
-- merge_pull_request RPC — body refresh to reference renamed tables.
-- The function name stays merge_pull_request (renamed to apply_suggestion
-- in Migration 027). Argument names also stay (renamed in 027).
-- Column names within tables are unchanged so column references stay
-- the same; only the FROM/INTO table identifiers update.
-- =============================================================================

CREATE OR REPLACE FUNCTION public.merge_pull_request(
    p_pull_request_id UUID,
    p_strategy TEXT,
    p_merged_document JSONB,
    p_merged_content_hash TEXT,
    p_resolved_revision_id UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_pr public.pull_requests;
    v_source_tip_now UUID;
    v_caller UUID := auth.uid();
    v_new_revision_id UUID := p_resolved_revision_id;
    v_target_tip_pre_merge UUID;
    v_inserted_parent_revision_id UUID;
BEGIN
    SELECT * INTO v_pr FROM public.pull_requests WHERE id = p_pull_request_id FOR UPDATE;
    IF v_pr IS NULL THEN RAISE EXCEPTION 'PR not found'; END IF;
    IF v_pr.status != 'open' THEN RAISE EXCEPTION 'PR not open'; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.patterns
        WHERE id = v_pr.target_pattern_id AND owner_id = v_caller
    ) THEN
        RAISE EXCEPTION 'Caller is not target owner';
    END IF;

    IF p_strategy NOT IN ('squash', 'fast_forward') THEN
        RAISE EXCEPTION 'Unsupported strategy: %', p_strategy;
    END IF;

    SELECT tip_revision_id INTO v_source_tip_now
        FROM public.chart_variations WHERE id = v_pr.source_branch_id;
    IF v_source_tip_now IS DISTINCT FROM v_pr.source_tip_revision_id THEN
        RAISE EXCEPTION 'Source tip drifted; re-resolve required';
    END IF;

    SELECT tip_revision_id INTO v_target_tip_pre_merge
        FROM public.chart_variations WHERE id = v_pr.target_branch_id;
    IF v_target_tip_pre_merge IS NULL THEN
        RAISE EXCEPTION 'Target variation has no tip; merge precondition failed';
    END IF;

    INSERT INTO public.chart_versions (
        revision_id, pattern_id, owner_id, author_id,
        schema_version, storage_variant, coordinate_system,
        document, parent_revision_id, content_hash,
        commit_message, created_at
    )
    SELECT
        v_new_revision_id,
        v_pr.target_pattern_id,
        v_caller,
        v_pr.author_id,
        cv.schema_version, cv.storage_variant, cv.coordinate_system,
        p_merged_document,
        v_target_tip_pre_merge,
        p_merged_content_hash,
        v_pr.title,
        now()
    FROM public.chart_versions cv
    WHERE cv.revision_id = v_pr.source_tip_revision_id
    RETURNING parent_revision_id INTO v_inserted_parent_revision_id;

    IF v_inserted_parent_revision_id IS DISTINCT FROM v_target_tip_pre_merge THEN
        RAISE EXCEPTION 'Merged revision INSERT did not match precondition';
    END IF;

    UPDATE public.chart_variations
        SET tip_revision_id = v_new_revision_id, updated_at = now()
        WHERE id = v_pr.target_branch_id;

    UPDATE public.chart_documents
        SET revision_id = v_new_revision_id,
            parent_revision_id = v_target_tip_pre_merge,
            content_hash = p_merged_content_hash,
            document = p_merged_document,
            updated_at = now()
        WHERE pattern_id = v_pr.target_pattern_id;

    UPDATE public.pull_requests
        SET status = 'merged',
            merged_revision_id = v_new_revision_id,
            merged_at = now(),
            updated_at = now()
        WHERE id = p_pull_request_id;

    RETURN v_new_revision_id;
END;
$$;

REVOKE ALL ON FUNCTION public.merge_pull_request FROM public;
GRANT EXECUTE ON FUNCTION public.merge_pull_request TO authenticated;

-- Verify post-apply (manual SQL):
--   \d public.chart_versions
--   \d public.chart_variations
--   SELECT proname FROM pg_proc WHERE proname = 'merge_pull_request';
