-- Phase 25.1 (ADR-024) — Friend-Only / Private Circle Mode data spine
--
-- Implements the friendship graph + invite redemption primitives + RLS
-- visibility-aware arm extensions for friends-only content.
--
-- Schema additions (idempotent via IF NOT EXISTS where applicable, but
-- the migration as a whole assumes a clean apply against a 034-state
-- database):
--
--   1. public.friend_connections     — mutual-confirmation graph
--   2. public.friend_invites         — single-use 14-day invites
--   3. public.is_friend()            — STABLE helper used by RLS
--   4. patterns.visibility CHECK     — newly add the 4-value constraint
--                                       (was application-only until now)
--   5. RLS policy amendments:
--      - patterns: extend the SELECT policy to include the `friends`
--        visibility arm (gated on is_friend) alongside the existing
--        shared/public arm; preserve the user_blocks NOT EXISTS arm.
--      - chart_versions: replace "public-only" SELECT policy with
--        "visibility-aware via parent pattern" (matches patterns'
--        scope + user_blocks).
--      - comments: extend the pattern-target arm to include the
--        `friends` visibility; preserve all other arms.
--   6. RPCs:
--      - redeem_friend_invite_code(p_code)
--      - redeem_friend_invite_token(p_token)
--
-- Intentionally NOT included:
--   - wipe_own_data() amendment per ADR-024 §(g.1) — that's a follow-up
--     mini-slice. Until then, calling wipe_own_data() leaves
--     friend_connections / friend_invites rows intact for the wiping
--     user. Documented as Tech Debt.
--   - suggestions / suggestion_comments policy changes — those policies
--     are intentionally narrow (author + pattern owner only), not
--     friend-graph-aware. ADR-024 §(b) documents this scope cut.
--   - block_user RPC + atomic friend severance — ADR-024 §(g.3) calls
--     for this to fold into a future migration; until then the
--     application layer is responsible.

BEGIN;

-- =========================================================================
-- 1. public.friend_connections — mutual-confirmation friendship graph
-- =========================================================================
-- Composite PK on sorted (user_a, user_b) means each edge has exactly
-- one row regardless of which direction the request came from. The
-- requester_id column captures attribution for pending requests so the
-- receiver can render "<requester> wants to be your friend" UX.
--
-- The (state, accepted_at) CHECK enforces:
--   pending  -> accepted_at IS NULL
--   accepted -> accepted_at IS NOT NULL
--   blocked  -> accepted_at IS NULL
-- This makes the wipe path's accepted->blocked transition require
-- nulling accepted_at, which is intentional (the connection is no
-- longer in an "accepted" state).
CREATE TABLE public.friend_connections (
    user_a UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    user_b UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    state TEXT NOT NULL CHECK (state IN ('pending', 'accepted', 'blocked')),
    requester_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at TIMESTAMPTZ,
    PRIMARY KEY (user_a, user_b),
    CONSTRAINT friend_connections_sorted_pair CHECK (user_a < user_b),
    CONSTRAINT friend_connections_requester_is_participant
        CHECK (requester_id = user_a OR requester_id = user_b),
    CONSTRAINT friend_connections_accepted_at_matches_state
        CHECK ((state = 'accepted') = (accepted_at IS NOT NULL))
);

COMMENT ON TABLE public.friend_connections IS
    'Phase 25.1 (ADR-024) — mutual-confirmation friendship graph. Composite PK on sorted user pair eliminates duplicate edges; one row per edge regardless of direction.';

-- Indexes for the is_friend() function's symmetric lookup pattern.
-- The PK already covers (user_a) prefix lookups; the user_b index
-- covers the reverse direction.
CREATE INDEX idx_friend_connections_user_a_state
    ON public.friend_connections(user_a, state)
    WHERE state = 'accepted';
CREATE INDEX idx_friend_connections_user_b_state
    ON public.friend_connections(user_b, state)
    WHERE state = 'accepted';
-- Outbound lookups (Pending tab in Connections UI):
CREATE INDEX idx_friend_connections_requester_state
    ON public.friend_connections(requester_id, state);

ALTER TABLE public.friend_connections ENABLE ROW LEVEL SECURITY;

-- Read: participants only. Either side of the pair can see the row,
-- which surfaces inbound pending requests + accepted connections + (post-
-- block transition) the historical record of severance.
CREATE POLICY "Users can read own friend connections"
    ON public.friend_connections FOR SELECT
    USING (user_a = auth.uid() OR user_b = auth.uid());

-- Insert: requester writes the row at state='pending'. The application
-- layer sorts the user pair before INSERT (LEAST/GREATEST). RLS rejects
-- inserts where the caller isn't the requester or isn't a participant
-- or where state/accepted_at aren't the initial pending shape.
CREATE POLICY "Users can send friendship requests"
    ON public.friend_connections FOR INSERT
    WITH CHECK (
        requester_id = auth.uid()
        AND (user_a = auth.uid() OR user_b = auth.uid())
        AND state = 'pending'
        AND accepted_at IS NULL
    );

-- Update: any participant can mutate the row. Application-level logic
-- restricts the transitions:
--   pending  -> accepted (only the non-requester can do this)
--   accepted -> blocked  (either side can disconnect)
-- The RLS WITH CHECK guards the valid post-state shapes; application
-- code enforces the transition semantics.
CREATE POLICY "Users can respond to friendship requests"
    ON public.friend_connections FOR UPDATE
    USING (user_a = auth.uid() OR user_b = auth.uid())
    WITH CHECK (
        (user_a = auth.uid() OR user_b = auth.uid())
        AND state IN ('accepted', 'blocked')
    );

-- Delete: either participant can hard-delete (used by cancel-pending-
-- request flow + future wipe path's outbound DELETE per ADR-024 §(g.1)).
CREATE POLICY "Users can delete own friend connections"
    ON public.friend_connections FOR DELETE
    USING (user_a = auth.uid() OR user_b = auth.uid());

-- =========================================================================
-- 2. public.friend_invites — single-use 14-day invites
-- =========================================================================
-- Separate from friend_connections by design: an invite is a redeemable
-- token; a connection is the relationship state. Invites get DELETEd or
-- consumed; connections persist through the relationship lifecycle.
CREATE TABLE public.friend_invites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inviter_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    -- 32-byte URL-safe random; the Universal Link path embeds this.
    token TEXT NOT NULL UNIQUE,
    -- Human-typable 8-char base32 (alphanumeric, excluding O/0/I/l for
    -- readability per ADR-024 §(d)). UPPER-normalized on lookup.
    code TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '14 days'),
    consumed_at TIMESTAMPTZ,
    consumed_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT friend_invites_token_min_length CHECK (length(token) >= 32),
    CONSTRAINT friend_invites_code_length CHECK (length(code) BETWEEN 6 AND 12),
    CONSTRAINT friend_invites_consumed_pair
        CHECK ((consumed_at IS NULL) = (consumed_by IS NULL))
);

COMMENT ON TABLE public.friend_invites IS
    'Phase 25.1 (ADR-024) — single-use invites via 32-byte URL-safe token (Universal Link path) or 8-char base32 code (in-app paste). 14-day expiry.';

CREATE INDEX idx_friend_invites_inviter ON public.friend_invites(inviter_id);
-- Partial indexes scope to outstanding invites only (consumed/expired
-- rows are not the typical lookup path). The unique constraints on
-- token + code apply regardless.
CREATE INDEX idx_friend_invites_token_outstanding
    ON public.friend_invites(token)
    WHERE consumed_at IS NULL;
CREATE INDEX idx_friend_invites_code_outstanding
    ON public.friend_invites(code)
    WHERE consumed_at IS NULL;

ALTER TABLE public.friend_invites ENABLE ROW LEVEL SECURITY;

-- Read: inviter sees own invites (for the Connections > Invite tab's
-- "your outstanding invites" list + revoke action). The invitee
-- doesn't need direct SELECT — they redeem via RPC, which is
-- SECURITY DEFINER and bypasses RLS.
CREATE POLICY "Inviter can read own invites"
    ON public.friend_invites FOR SELECT
    USING (inviter_id = auth.uid());

-- Insert: inviter creates own. Application layer generates token+code;
-- DB enforces the initial shape (no consumed_at on insert).
CREATE POLICY "Users can create own invites"
    ON public.friend_invites FOR INSERT
    WITH CHECK (
        inviter_id = auth.uid()
        AND consumed_at IS NULL
        AND consumed_by IS NULL
    );

-- Delete: inviter can revoke outstanding invites.
CREATE POLICY "Inviter can revoke own invites"
    ON public.friend_invites FOR DELETE
    USING (inviter_id = auth.uid());

-- =========================================================================
-- 3. public.is_friend() — RLS-callable helper
-- =========================================================================
-- SECURITY DEFINER so it bypasses friend_connections RLS (which scopes
-- reads to participants). The function takes two arbitrary UUIDs and
-- returns whether they share an accepted edge — usable from RLS
-- policies on tables where the caller wouldn't otherwise have direct
-- read access to friend_connections rows for the owner_id pair.
--
-- STABLE = within a single query, the result is consistent (no
-- side effects, deterministic given inputs at that snapshot).
CREATE OR REPLACE FUNCTION public.is_friend(p_user_a UUID, p_user_b UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.friend_connections
        WHERE state = 'accepted'
          AND user_a = LEAST(p_user_a, p_user_b)
          AND user_b = GREATEST(p_user_a, p_user_b)
    );
$$;

COMMENT ON FUNCTION public.is_friend(UUID, UUID) IS
    'Phase 25.1 (ADR-024) — symmetric friendship check. SECURITY DEFINER to bypass friend_connections RLS so RLS policies on other tables can call this for any owner_id pair.';

REVOKE EXECUTE ON FUNCTION public.is_friend(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_friend(UUID, UUID) TO authenticated;

-- =========================================================================
-- 4. patterns.visibility CHECK constraint — newly add the 4-value enum
-- =========================================================================
-- Existing column has no CHECK constraint (migration 002 stored
-- visibility as TEXT NOT NULL DEFAULT 'private' but without explicit
-- value-set validation). Phase 25.1 introduces the 4-value enum:
--   private | friends | shared | public
-- The constraint is forward-compatible — any new value addition
-- requires explicit ALTER. Add via NOT VALID + VALIDATE for online
-- safety (skips full-table scan during ADD, then VALIDATE checks
-- without blocking writes longer than the constraint scan).
ALTER TABLE public.patterns
    ADD CONSTRAINT patterns_visibility_check
    CHECK (visibility IN ('private', 'friends', 'shared', 'public'))
    NOT VALID;
ALTER TABLE public.patterns VALIDATE CONSTRAINT patterns_visibility_check;

-- =========================================================================
-- 5. RLS policy amendments — add friends visibility-aware arm
-- =========================================================================

-- 5a. patterns: replace the SELECT policy.
--
-- Previous (migration 002 + 032): visibility IN ('shared', 'public')
--                                  AND NOT EXISTS user_blocks
-- New: add friends arm gated on is_friend().
DROP POLICY IF EXISTS "Users can read shared or public patterns" ON public.patterns;

CREATE POLICY "Users can read accessible patterns"
    ON public.patterns FOR SELECT
    USING (
        (
            visibility IN ('shared', 'public')
            OR (visibility = 'friends' AND public.is_friend(auth.uid(), patterns.owner_id))
        )
        AND NOT EXISTS (
            SELECT 1 FROM public.user_blocks ub
            WHERE ub.blocker_id = auth.uid()
              AND ub.blocked_id = patterns.owner_id
        )
    );

-- 5b. chart_versions: extend the public-read policy to be visibility-aware.
--
-- Previous (migration 015 / 026 rename): pattern_id IN (... visibility = 'public')
-- New: include the shared / friends arms + add user_blocks NOT EXISTS
-- (parity with the patterns policy — chart_versions shouldn't be
-- visible if the parent pattern's owner is blocked).
--
-- Note: the "Users can read own chart revisions" policy (owner-side
-- access) is left intact; that's the read path for owners regardless
-- of visibility.
DROP POLICY IF EXISTS "Public chart revisions readable" ON public.chart_versions;

CREATE POLICY "Users can read accessible chart versions"
    ON public.chart_versions FOR SELECT
    USING (
        pattern_id IN (
            SELECT p.id FROM public.patterns p
            WHERE (
                p.visibility IN ('shared', 'public')
                OR (p.visibility = 'friends' AND public.is_friend(auth.uid(), p.owner_id))
            )
            AND NOT EXISTS (
                SELECT 1 FROM public.user_blocks ub
                WHERE ub.blocker_id = auth.uid()
                  AND ub.blocked_id = p.owner_id
            )
        )
    );

-- 5c. comments: extend the pattern-target visibility arm.
--
-- Previous (migration 032): EXISTS arms for pattern owner, shared/public
-- pattern, project owner, project shared via token.
-- New: extend the "shared/public pattern" arm to also include the
-- friends arm via is_friend().
--
-- All other arms (author_id, owner-of-pattern, owner-of-project,
-- project-shared-via-token) preserved verbatim. The user_blocks
-- NOT EXISTS outer arm is preserved.
DROP POLICY IF EXISTS "Users can read comments on accessible targets" ON public.comments;

CREATE POLICY "Users can read comments on accessible targets"
    ON public.comments FOR SELECT
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
                  AND (
                      p.visibility IN ('shared', 'public')
                      OR (p.visibility = 'friends' AND public.is_friend(auth.uid(), p.owner_id))
                  )
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
-- 6. Invite redemption RPCs
-- =========================================================================
-- Both RPCs share an identical body except for the WHERE clause. They
-- run under SECURITY DEFINER so they:
--   - bypass the friend_invites RLS (which only allows inviter to read)
--   - bypass the friend_connections RLS (the INSERT comes from the RPC
--     not from the caller's direct INSERT)
--
-- Atomicity: each call wraps in an implicit transaction. The SELECT
-- ... FOR UPDATE on friend_invites blocks concurrent redemptions of
-- the same invite. The UPDATE marking consumed runs in the same
-- transaction as the friend_connections INSERT.
--
-- On conflict (re-redeem after both sides already friends): the
-- ON CONFLICT clause upserts state to 'accepted' if it wasn't already,
-- preserving idempotency. If the existing row is 'blocked', the
-- conflict update is no-op (the WHERE clause filters it out).

CREATE OR REPLACE FUNCTION public.redeem_friend_invite_code(p_code TEXT)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_invite_id UUID;
    v_inviter UUID;
    v_caller UUID := auth.uid();
BEGIN
    IF v_caller IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    -- Normalize code to uppercase for case-insensitive lookup (the
    -- code column stores upper-case base32 by application convention).
    SELECT id, inviter_id INTO v_invite_id, v_inviter
    FROM public.friend_invites
    WHERE code = UPPER(p_code)
      AND consumed_at IS NULL
      AND expires_at > now()
    FOR UPDATE;

    IF v_invite_id IS NULL THEN
        RAISE EXCEPTION 'invite not found or expired' USING ERRCODE = 'P0002';
    END IF;

    IF v_inviter = v_caller THEN
        RAISE EXCEPTION 'cannot redeem own invite' USING ERRCODE = 'P0001';
    END IF;

    -- Symmetric block check: either side blocking the other rejects
    -- the invite. ADR-024 §(g.3) — block atomically severs friend
    -- intent in both directions.
    IF EXISTS (
        SELECT 1 FROM public.user_blocks
        WHERE (blocker_id = v_inviter AND blocked_id = v_caller)
           OR (blocker_id = v_caller AND blocked_id = v_inviter)
    ) THEN
        RAISE EXCEPTION 'blocked' USING ERRCODE = 'P0001';
    END IF;

    UPDATE public.friend_invites
    SET consumed_at = now(), consumed_by = v_caller
    WHERE id = v_invite_id;

    -- Direct token / code redemption = implicit mutual acceptance per
    -- ADR-024 §(d) (both parties participated in the invite exchange).
    -- State goes straight to 'accepted' with accepted_at NOT NULL.
    INSERT INTO public.friend_connections (user_a, user_b, state, requester_id, accepted_at)
    VALUES (
        LEAST(v_inviter, v_caller),
        GREATEST(v_inviter, v_caller),
        'accepted',
        v_inviter,
        now()
    )
    ON CONFLICT (user_a, user_b) DO UPDATE
        SET state = 'accepted',
            accepted_at = COALESCE(public.friend_connections.accepted_at, EXCLUDED.accepted_at)
        WHERE public.friend_connections.state != 'blocked';

    RETURN v_inviter;
END;
$$;

COMMENT ON FUNCTION public.redeem_friend_invite_code(TEXT) IS
    'Phase 25.1 (ADR-024) — redeems a friend invite code, creating a mutual friend_connections row at state=accepted.';

REVOKE EXECUTE ON FUNCTION public.redeem_friend_invite_code(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.redeem_friend_invite_code(TEXT) TO authenticated;

-- Token-redemption mirror — same body, different WHERE clause.
CREATE OR REPLACE FUNCTION public.redeem_friend_invite_token(p_token TEXT)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_invite_id UUID;
    v_inviter UUID;
    v_caller UUID := auth.uid();
BEGIN
    IF v_caller IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    SELECT id, inviter_id INTO v_invite_id, v_inviter
    FROM public.friend_invites
    WHERE token = p_token
      AND consumed_at IS NULL
      AND expires_at > now()
    FOR UPDATE;

    IF v_invite_id IS NULL THEN
        RAISE EXCEPTION 'invite not found or expired' USING ERRCODE = 'P0002';
    END IF;

    IF v_inviter = v_caller THEN
        RAISE EXCEPTION 'cannot redeem own invite' USING ERRCODE = 'P0001';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.user_blocks
        WHERE (blocker_id = v_inviter AND blocked_id = v_caller)
           OR (blocker_id = v_caller AND blocked_id = v_inviter)
    ) THEN
        RAISE EXCEPTION 'blocked' USING ERRCODE = 'P0001';
    END IF;

    UPDATE public.friend_invites
    SET consumed_at = now(), consumed_by = v_caller
    WHERE id = v_invite_id;

    INSERT INTO public.friend_connections (user_a, user_b, state, requester_id, accepted_at)
    VALUES (
        LEAST(v_inviter, v_caller),
        GREATEST(v_inviter, v_caller),
        'accepted',
        v_inviter,
        now()
    )
    ON CONFLICT (user_a, user_b) DO UPDATE
        SET state = 'accepted',
            accepted_at = COALESCE(public.friend_connections.accepted_at, EXCLUDED.accepted_at)
        WHERE public.friend_connections.state != 'blocked';

    RETURN v_inviter;
END;
$$;

COMMENT ON FUNCTION public.redeem_friend_invite_token(TEXT) IS
    'Phase 25.1 (ADR-024) — redeems a friend invite token (Universal Link path), creating a mutual friend_connections row at state=accepted.';

REVOKE EXECUTE ON FUNCTION public.redeem_friend_invite_token(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.redeem_friend_invite_token(TEXT) TO authenticated;

COMMIT;
