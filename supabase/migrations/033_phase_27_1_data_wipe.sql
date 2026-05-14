-- Phase 27.1 (ADR-023) — Data Wipe (Account-Preserved)
--
-- Three coupled changes, applied atomically:
--   1. Relax `ugc_reports.reporter_id` from NOT NULL + ON DELETE CASCADE
--      to NULLABLE + ON DELETE SET NULL.
--      Rationale: the wipe RPC anonymizes the caller's outbound UGC reports
--      (operator audit trail stays, reporter identity is severed). The
--      original migration 031 CHECK + FK presumed account-deletion semantic
--      (reporter row goes when auth.users row goes); wipe needs the row
--      preserved with reporter_id = NULL. ADR-023 §3.1 preservation-matrix
--      row for ugc_reports.
--
--   2. Widen `activities.type` CHECK constraint to include `'data_wiped'`.
--      Backward-compatible widening: existing rows stay valid; new audit
--      rows can carry the new sentinel value. ADR-023 §3.5 + §(e).
--
--   3. Create `public.wipe_own_data()` SECURITY DEFINER function.
--      Atomic single-transaction. Idempotent under retry. Caller-scoped
--      via `auth.uid()`. Mirrors `delete_own_account` (migration 007)
--      precedent.
--
-- Forward-compat note: reverting this migration requires (a) the constraint
-- flip back to NOT NULL + CASCADE, (b) DELETE of any rows with reporter_id
-- IS NULL (now permitted), AND (c) DELETE of any activities rows with
-- type = 'data_wiped'.

-- ============================================================
-- 1. Relax ugc_reports.reporter_id
-- ============================================================

ALTER TABLE public.ugc_reports
    DROP CONSTRAINT ugc_reports_reporter_id_fkey;

ALTER TABLE public.ugc_reports
    ALTER COLUMN reporter_id DROP NOT NULL;

ALTER TABLE public.ugc_reports
    ADD CONSTRAINT ugc_reports_reporter_id_fkey
    FOREIGN KEY (reporter_id) REFERENCES auth.users(id) ON DELETE SET NULL;

COMMENT ON COLUMN public.ugc_reports.reporter_id IS
  'Phase 27.1 (ADR-023): NULLABLE + ON DELETE SET NULL. The wipe-own-data RPC '
  'UPDATEs this to NULL when the caller invokes `wipe_own_data()`, preserving '
  'the operator''s investigation thread with reporter identity severed. '
  'Account deletion (auth.users row removal) also SET NULLs via the FK clause.';

-- ============================================================
-- 2. Widen activities.type CHECK to include 'data_wiped'
-- ============================================================

ALTER TABLE public.activities
    DROP CONSTRAINT activities_type_check;

ALTER TABLE public.activities
    ADD CONSTRAINT activities_type_check
    CHECK (type IN ('shared', 'commented', 'forked', 'completed', 'started', 'created', 'data_wiped'));

COMMENT ON CONSTRAINT activities_type_check ON public.activities IS
  'Phase 27.1 (ADR-023 §(e)): adds `data_wiped` for the single audit row '
  'inserted at the end of public.wipe_own_data(). UI consumers (Phase 36.5 '
  'Activity Feed) hide target_type / target_id when type = ''data_wiped'' '
  '(target_* are sentinels to satisfy NOT NULL).';

-- ============================================================
-- 3. public.wipe_own_data() SECURITY DEFINER function
-- ============================================================

CREATE OR REPLACE FUNCTION public.wipe_own_data()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_uid UUID := auth.uid();
BEGIN
    -- Defensive: anonymous invocation is impossible via Postgrest (RPC
    -- requires JWT), but a stray service-role call without the user
    -- impersonation header would resolve `auth.uid()` to NULL. RAISE
    -- forces a clean error rather than wiping zero rows silently.
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'wipe_own_data requires an authenticated session'
            USING ERRCODE = '28000';
    END IF;

    -- Serialize concurrent invocations from the same user. A second
    -- caller blocks until the first transaction commits, then runs
    -- against the now-empty state (idempotent no-op for content tables,
    -- fresh audit-row INSERT at the end).
    PERFORM 1 FROM auth.users WHERE id = v_uid FOR UPDATE;

    -- ---- DELETE phase ----
    -- FK-RESTRICT-safe descendant-first order. Critical RESTRICT edges:
    --   chart_variations.tip_revision_id  -> chart_versions  (RESTRICT)
    --   suggestions.source_tip_revision_id -> chart_versions (RESTRICT)
    --   suggestions.common_ancestor_revision_id -> chart_versions (RESTRICT)
    -- So: suggestions BEFORE chart_variations BEFORE chart_versions.
    -- Each statement is also CASCADE-safe; explicit DELETE is for audit
    -- clarity at the migration-file level.

    DELETE FROM public.suggestion_comments WHERE author_id = v_uid;

    -- Wipe caller's outbound suggestions AND inbound suggestions targeted
    -- at caller's patterns. Includes `author_id = v_uid` so the caller's
    -- suggestions against third-party patterns are removed too (otherwise
    -- the RESTRICT FK to chart_versions would fire when we delete the
    -- caller's chart_versions later in this transaction).
    DELETE FROM public.suggestions
    WHERE author_id = v_uid
       OR source_pattern_id IN (SELECT id FROM public.patterns WHERE owner_id = v_uid)
       OR target_pattern_id IN (SELECT id FROM public.patterns WHERE owner_id = v_uid);

    DELETE FROM public.comments WHERE author_id = v_uid;

    -- Shares: outbound (from_user_id) wipes the row. Inbound to_user_id
    -- is `ON DELETE SET NULL` so it would survive an auth.users delete,
    -- but the wipe semantic is "caller is leaving the share relationship
    -- symmetrically", so we also wipe rows where the caller is the
    -- recipient.
    DELETE FROM public.shares WHERE from_user_id = v_uid OR to_user_id = v_uid;

    DELETE FROM public.project_segments WHERE owner_id = v_uid;
    DELETE FROM public.progress WHERE project_id IN (
        SELECT id FROM public.projects WHERE owner_id = v_uid
    );
    DELETE FROM public.projects WHERE owner_id = v_uid;

    -- chart_variations BEFORE chart_versions to satisfy
    -- chart_variations.tip_revision_id RESTRICT FK.
    DELETE FROM public.chart_variations WHERE owner_id = v_uid;
    DELETE FROM public.chart_versions WHERE owner_id = v_uid;
    DELETE FROM public.chart_documents WHERE owner_id = v_uid;
    DELETE FROM public.patterns WHERE owner_id = v_uid;

    DELETE FROM public.activities WHERE user_id = v_uid;
    DELETE FROM public.device_tokens WHERE user_id = v_uid;
    DELETE FROM public.user_symbol_pack_state WHERE user_id = v_uid;

    -- user_blocks: wipe caller's outbound blocks only. Inbound blocks
    -- (other users who have blocked the caller) are preserved — those
    -- are other users' privacy choices, not the caller's.
    DELETE FROM public.user_blocks WHERE blocker_id = v_uid;

    -- ---- ANONYMIZE phase ----
    -- Operator-facing audit rows stay; caller identity is severed.
    UPDATE public.feedback SET user_id = NULL WHERE user_id = v_uid;
    UPDATE public.ugc_reports SET reporter_id = NULL WHERE reporter_id = v_uid;

    -- ---- AUDIT phase ----
    -- One row records "user wiped at <commit-time>" — every other
    -- activities row was just nuked above. target_type = 'project' and
    -- target_id = v_uid are sentinels to satisfy the existing NOT NULL
    -- columns; UI consumers hide them when type = 'data_wiped'.
    INSERT INTO public.activities (user_id, type, target_type, target_id, metadata)
    VALUES (v_uid, 'data_wiped', 'project', v_uid, NULL);
END;
$$;

-- Restrict execution to authenticated users only. Matches the precedent
-- from public.delete_own_account (migration 007).
--
-- IMPORTANT: Supabase grants default EXECUTE to `anon` for newly-created
-- functions; `REVOKE ALL ON FUNCTION ... FROM public` does NOT remove
-- that grant because `anon` holds it directly. Explicit REVOKE FROM anon
-- is required to match the security floor of delete_own_account.
REVOKE ALL ON FUNCTION public.wipe_own_data() FROM public;
REVOKE EXECUTE ON FUNCTION public.wipe_own_data() FROM anon;
GRANT EXECUTE ON FUNCTION public.wipe_own_data() TO authenticated;

COMMENT ON FUNCTION public.wipe_own_data() IS
  'Phase 27.1 (ADR-023): account-preserved bulk content deletion. Wipes the '
  'caller''s patterns, projects, charts, comments, suggestions, shares, '
  'activities, device tokens, symbol-pack state, and outbound blocks; '
  'anonymizes outbound feedback + UGC reports; preserves auth.users, '
  'profiles, and subscriptions (Pro entitlement). Idempotent under retry '
  'via PERFORM ... FOR UPDATE on auth.users. Inserts exactly one '
  '''data_wiped'' audit row at the end. Sibling primitive to '
  'public.delete_own_account (ADR-005).';
