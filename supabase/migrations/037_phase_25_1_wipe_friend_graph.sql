-- Phase 25.1 follow-up (ADR-024 §(g.1)) — friend-graph wipe semantics
--
-- Closes the Tech Debt explicitly carried since migration 035 (lines 29-31:
-- "wipe_own_data() amendment per ADR-024 §(g.1) — that's a follow-up
-- mini-slice. Until then, calling wipe_own_data() leaves friend_connections
-- / friend_invites rows intact for the wiping user.").
--
-- Single change: CREATE OR REPLACE public.wipe_own_data() to additionally
-- wipe the caller's friend graph. Non-destructive function redefinition;
-- no schema/data migration, idempotent, reversible (revert = re-apply the
-- migration 033 body verbatim). The RPC name is unchanged so the Kotlin
-- Postgrest binding (RemoteWipeDataDataSource.RPC_NAME) needs no edit.
--
-- ============================================================
-- ADR-024 §(g.1) decision — "Wipe outbound, preserve inbound"
-- ============================================================
-- For every friend_connections row where the wiping user (v_uid) is a
-- participant (user_a or user_b):
--
--   1. OUTBOUND  — requester_id = v_uid (the wiping user initiated the
--      edge), any state: DELETE. "Starting over socially" — the requests
--      and connections the user originated disappear entirely. The other
--      side simply loses the row (intentional asymmetry vs. case 2; the
--      ADR explicitly chose no "severed" signal for connections the
--      wiping user themselves created).
--
--   2. INBOUND ACCEPTED — v_uid is a participant but NOT the requester,
--      state = 'accepted': transition to state = 'blocked'. The other
--      side (the original requester) sees a clean "this connection was
--      severed" signal next time they query, rather than a row that
--      silently vanished. The friend_connections_accepted_at_matches_state
--      CHECK ((state = 'accepted') = (accepted_at IS NOT NULL)) forces
--      accepted_at = NULL on this transition.
--
--   3. INBOUND PENDING — v_uid is a participant but NOT the requester,
--      state = 'pending' (an unanswered request someone sent the wiping
--      user): DELETE. No notification needed — it was never accepted.
--
--   4. INBOUND BLOCKED — v_uid is a participant, NOT the requester,
--      state = 'blocked': NOT touched. The ADR §(g.1) decision prescribes
--      transitions only for accepted/pending inbound rows; a row already
--      in the terminal 'blocked' state is left as the faithful reading of
--      the decision (no content flows through a blocked edge anyway).
--
-- friend_invites (bundled into this mini-slice per migration 035 §note):
--
--   - inviter_id = v_uid: DELETE. The wiping user's own created invites
--     are outbound artifacts, mirroring the user_blocks / shares outbound
--     wipe precedent in migration 033.
--
--   - consumed_by = v_uid (an invite the wiping user redeemed, created by
--     someone else): NOT touched. The friend_invites_consumed_pair CHECK
--     ((consumed_at IS NULL) = (consumed_by IS NULL)) couples the two
--     columns, so nulling consumed_by alone violates the CHECK, and
--     nulling both would resurrect a single-use invite as redeemable
--     again (a security regression). The invite row is the inviter's
--     private artifact (RLS "Inviter can read own invites"); the
--     consumed_by attribution is a single UUID in the inviter's own
--     invite log, analogous to migration 033 preserving inbound
--     user_blocks ("other users' privacy choices, not the caller's").
--
-- The friend-graph block is placed after the user_blocks DELETE and
-- before the ANONYMIZE phase. friend_connections / friend_invites have no
-- inbound FK from any other wiped table (friend_invites is "separate from
-- friend_connections by design" per migration 035 §2), so the placement
-- is FK-order-free; it is grouped here for audit clarity only.

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

    -- ---- FRIEND GRAPH phase (ADR-024 §(g.1), Phase 25.1 follow-up) ----
    -- See the migration header for the full decision rationale. Order
    -- within this block is correctness-free (disjoint row sets); grouped
    -- for audit clarity.

    -- (g.1) case 1 — OUTBOUND: every edge the caller initiated, any
    -- state. Covers caller's unanswered outbound requests, accepted
    -- connections the caller requested, and any blocked rows the caller
    -- requested. The other side simply loses the row.
    DELETE FROM public.friend_connections WHERE requester_id = v_uid;

    -- (g.1) case 3 — INBOUND PENDING: requests sent TO the caller that
    -- were never accepted. The caller is a participant but not the
    -- requester. No "severed" signal needed (never accepted).
    DELETE FROM public.friend_connections
    WHERE (user_a = v_uid OR user_b = v_uid)
      AND requester_id <> v_uid
      AND state = 'pending';

    -- (g.1) case 2 — INBOUND ACCEPTED: connections the caller accepted
    -- from someone else. Transition accepted -> blocked so the original
    -- requester sees a clean severance signal. accepted_at must go NULL
    -- to satisfy friend_connections_accepted_at_matches_state.
    UPDATE public.friend_connections
    SET state = 'blocked', accepted_at = NULL
    WHERE (user_a = v_uid OR user_b = v_uid)
      AND requester_id <> v_uid
      AND state = 'accepted';

    -- (g.1) case 4 — INBOUND BLOCKED: intentionally untouched (terminal
    -- severed state; ADR §(g.1) prescribes transitions only for inbound
    -- accepted/pending).

    -- friend_invites — outbound only (invites the caller created).
    -- consumed_by = v_uid rows are NOT touched (consumed_pair CHECK +
    -- single-use security + inviter-owns-the-row; see migration header).
    DELETE FROM public.friend_invites WHERE inviter_id = v_uid;

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

-- Re-assert the execution grants. CREATE OR REPLACE preserves existing
-- grants, but restating them keeps this migration self-contained and
-- matches the migration 033 security floor (and delete_own_account,
-- migration 007). Supabase grants default EXECUTE to `anon` for
-- newly-created functions; REPLACE does not re-trigger that default, but
-- the explicit REVOKE FROM anon is idempotent and documents intent.
REVOKE ALL ON FUNCTION public.wipe_own_data() FROM public;
REVOKE EXECUTE ON FUNCTION public.wipe_own_data() FROM anon;
GRANT EXECUTE ON FUNCTION public.wipe_own_data() TO authenticated;

COMMENT ON FUNCTION public.wipe_own_data() IS
  'Phase 27.1 (ADR-023) + Phase 25.1 follow-up (ADR-024 §(g.1)): '
  'account-preserved bulk content deletion. Wipes the caller''s patterns, '
  'projects, charts, comments, suggestions, shares, activities, device '
  'tokens, symbol-pack state, outbound blocks, outbound friend '
  'connections + caller-created invites; transitions inbound accepted '
  'friend connections to blocked; deletes inbound pending friend '
  'requests; anonymizes outbound feedback + UGC reports; preserves '
  'auth.users, profiles, and subscriptions (Pro entitlement). Idempotent '
  'under retry via PERFORM ... FOR UPDATE on auth.users. Inserts exactly '
  'one ''data_wiped'' audit row at the end. Sibling primitive to '
  'public.delete_own_account (ADR-005).';
