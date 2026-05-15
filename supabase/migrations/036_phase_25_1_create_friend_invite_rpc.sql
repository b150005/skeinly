-- Phase 25.1 follow-up — create_friend_invite() RPC
--
-- Generates an invite row with cryptographically random token + code,
-- inserting under the caller's session. Used in lieu of a client-side
-- INSERT path to keep the token randomness server-side (via pgcrypto's
-- gen_random_bytes) and avoid the KMP expect/actual surface for
-- platform-specific SecureRandom.
--
-- Returns the new invite row (id, token, code, expires_at). The client
-- then surfaces the code to the user (paste-text) and the token to the
-- OS share sheet (Universal Link template).

BEGIN;

CREATE OR REPLACE FUNCTION public.create_friend_invite()
RETURNS TABLE (
    id UUID,
    token TEXT,
    code TEXT,
    expires_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_caller UUID := auth.uid();
    v_token TEXT;
    v_code TEXT;
    v_alphabet TEXT := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; -- base32 excl O/0/I/1
    v_id UUID;
    v_expires TIMESTAMPTZ;
    v_attempt INT := 0;
    v_max_attempts INT := 8;
BEGIN
    IF v_caller IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    -- Token: 32 bytes of cryptographic random, base64url-encoded.
    -- Length after base64url ≈ 43 chars (32 * 4 / 3, no padding).
    -- The 32-char minimum in the CHECK constraint accommodates this.
    v_token := translate(encode(gen_random_bytes(32), 'base64'), '+/=', '-_');

    -- Code: 8-char base32-excluding-O/0/I/1. Retry on UNIQUE collision
    -- (~1.4 trillion possibilities; collision probability is
    -- effectively zero, but the retry loop is cheap insurance).
    LOOP
        v_attempt := v_attempt + 1;
        IF v_attempt > v_max_attempts THEN
            RAISE EXCEPTION 'invite code generation exhausted retries' USING ERRCODE = 'P0001';
        END IF;

        v_code := '';
        FOR i IN 1..8 LOOP
            v_code := v_code || substr(
                v_alphabet,
                1 + (get_byte(gen_random_bytes(1), 0) % length(v_alphabet)),
                1
            );
        END LOOP;

        -- Try the INSERT; on UNIQUE collision (token or code), loop.
        BEGIN
            INSERT INTO public.friend_invites (inviter_id, token, code)
            VALUES (v_caller, v_token, v_code)
            RETURNING friend_invites.id, friend_invites.expires_at
            INTO v_id, v_expires;
            EXIT; -- success
        EXCEPTION WHEN unique_violation THEN
            -- Regenerate token too in case the collision was on token
            v_token := translate(encode(gen_random_bytes(32), 'base64'), '+/=', '-_');
            -- code regenerates at the top of the next iteration
            CONTINUE;
        END;
    END LOOP;

    RETURN QUERY SELECT v_id, v_token, v_code, v_expires;
END;
$$;

COMMENT ON FUNCTION public.create_friend_invite() IS
    'Phase 25.1 (ADR-024) — generates a friend_invites row with cryptographically random token + code. Returns the new row so the client can surface code (paste) + token (Universal Link).';

REVOKE EXECUTE ON FUNCTION public.create_friend_invite() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_friend_invite() TO authenticated;

COMMIT;
