-- Phase 26.5 (ADR-022 §6.4 / Phase 26.4 in ADR-numbering) — MFA Recovery Codes
--
-- Supabase Auth's native MFA does NOT include a recovery-code mechanism.
-- We layer one on top: at TOTP enrollment, the client generates a 16-char
-- base32 recovery code, displays it ONCE to the user, and posts only the
-- bcrypt hash to `register_mfa_recovery_code`. At recovery-code-use, the
-- client posts the plaintext to `consume_mfa_recovery_code` which
-- bcrypt-verifies, marks the row consumed, and returns boolean success.
-- The client then calls `auth.mfa.unenroll(factorId)` to drop the TOTP
-- factor — the recovery code is a "reset TOTP enrollment" bypass, not a
-- permanent AAL2 elevation.
--
-- Ships:
--   1. `public.mfa_recovery_codes` table (user_id FK to auth.users, bcrypt
--      hash, created/consumed timestamps) with own-row SELECT RLS only.
--   2. `register_mfa_recovery_code(p_code_hash text)` SECURITY DEFINER —
--      wipes any prior active row for the user + inserts a fresh row.
--   3. `consume_mfa_recovery_code(p_code text)` SECURITY DEFINER — bcrypt-
--      verifies against the active row, marks it consumed on match,
--      returns boolean. Bcrypt computation uses the `extensions.crypt`
--      function from pgcrypto (extension already installed in the
--      `extensions` schema).
--   4. `hash_recovery_code(p_plain text)` SECURITY DEFINER — server-side
--      bcrypt helper so KMP client doesn't need a portable bcrypt impl.
--
-- All RPCs lock search_path = '' per the A10 hardening precedent (no
-- identifier hijack via attacker-controlled schema). All schema-
-- qualified references inline.
--
-- ON DELETE CASCADE on user_id ensures ADR-005 account-deletion +
-- Phase 27.1 data-wipe (ADR-023) both clean up recovery codes
-- automatically. The single-active-row unique partial index prevents
-- accidental dual-active recovery codes if a re-register races with
-- the prior consume.

BEGIN;

-- ============================================================
-- 1. mfa_recovery_codes table
-- ============================================================

CREATE TABLE public.mfa_recovery_codes (
    id          uuid PRIMARY KEY DEFAULT extensions.uuid_generate_v4(),
    user_id     uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    code_hash   text NOT NULL,  -- bcrypt: extensions.crypt(plaintext, extensions.gen_salt('bf', 10))
    created_at  timestamptz NOT NULL DEFAULT now(),
    consumed_at timestamptz
);

CREATE UNIQUE INDEX idx_mfa_recovery_codes_user_active
    ON public.mfa_recovery_codes (user_id)
    WHERE consumed_at IS NULL;

ALTER TABLE public.mfa_recovery_codes ENABLE ROW LEVEL SECURITY;

CREATE POLICY own_recovery_codes_select
    ON public.mfa_recovery_codes
    FOR SELECT
    USING (user_id = auth.uid());

COMMENT ON TABLE public.mfa_recovery_codes IS
    'Phase 26.5 (ADR-022 §6.4): bcrypt-hashed single-use recovery codes '
    'layered on top of Supabase Auth native MFA. Plaintext shown ONCE at '
    'enrollment; hash stored here. Consume = bcrypt verify + mark consumed '
    'via the SECURITY DEFINER RPC. ON DELETE CASCADE for ADR-005 + ADR-023 '
    'parity.';

-- ============================================================
-- 2. register_mfa_recovery_code RPC
-- ============================================================

CREATE OR REPLACE FUNCTION public.register_mfa_recovery_code(p_code_hash text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'register_mfa_recovery_code requires an authenticated session'
            USING ERRCODE = '28000';
    END IF;

    IF p_code_hash IS NULL OR length(p_code_hash) < 30 THEN
        RAISE EXCEPTION 'register_mfa_recovery_code: invalid hash length'
            USING ERRCODE = '22023';
    END IF;

    UPDATE public.mfa_recovery_codes
       SET consumed_at = now()
     WHERE user_id = v_uid
       AND consumed_at IS NULL;

    INSERT INTO public.mfa_recovery_codes (user_id, code_hash)
    VALUES (v_uid, p_code_hash);
END;
$$;

REVOKE EXECUTE ON FUNCTION public.register_mfa_recovery_code(text) FROM anon, PUBLIC;
GRANT  EXECUTE ON FUNCTION public.register_mfa_recovery_code(text) TO authenticated;

COMMENT ON FUNCTION public.register_mfa_recovery_code(text) IS
    'Phase 26.5: register a fresh bcrypt-hashed recovery code for the '
    'caller, marking any prior active code consumed. Hash is computed '
    'server-side by hash_recovery_code; this RPC takes the already-hashed '
    'value.';

-- ============================================================
-- 3. consume_mfa_recovery_code RPC
-- ============================================================

CREATE OR REPLACE FUNCTION public.consume_mfa_recovery_code(p_code text)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_uid       uuid := auth.uid();
    v_row_id    uuid;
    v_hash      text;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'consume_mfa_recovery_code requires an authenticated session'
            USING ERRCODE = '28000';
    END IF;

    IF p_code IS NULL OR length(p_code) = 0 THEN
        RETURN false;
    END IF;

    SELECT id, code_hash
      INTO v_row_id, v_hash
      FROM public.mfa_recovery_codes
     WHERE user_id = v_uid
       AND consumed_at IS NULL
     FOR UPDATE;

    IF v_row_id IS NULL THEN
        RETURN false;
    END IF;

    IF extensions.crypt(p_code, v_hash) <> v_hash THEN
        RETURN false;
    END IF;

    UPDATE public.mfa_recovery_codes
       SET consumed_at = now()
     WHERE id = v_row_id;

    RETURN true;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.consume_mfa_recovery_code(text) FROM anon, PUBLIC;
GRANT  EXECUTE ON FUNCTION public.consume_mfa_recovery_code(text) TO authenticated;

COMMENT ON FUNCTION public.consume_mfa_recovery_code(text) IS
    'Phase 26.5: bcrypt-verify a plaintext recovery code against the '
    'caller''s active stored hash. On match, marks the row consumed and '
    'returns true. Client follows with auth.mfa.unenroll(factorId).';

-- ============================================================
-- 4. hash_recovery_code helper RPC
-- ============================================================

CREATE OR REPLACE FUNCTION public.hash_recovery_code(p_plain text)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    IF p_plain IS NULL OR length(p_plain) < 8 THEN
        RAISE EXCEPTION 'hash_recovery_code: plaintext too short'
            USING ERRCODE = '22023';
    END IF;
    RETURN extensions.crypt(p_plain, extensions.gen_salt('bf', 10));
END;
$$;

REVOKE EXECUTE ON FUNCTION public.hash_recovery_code(text) FROM anon, PUBLIC;
GRANT  EXECUTE ON FUNCTION public.hash_recovery_code(text) TO authenticated;

COMMENT ON FUNCTION public.hash_recovery_code(text) IS
    'Phase 26.5: server-side bcrypt helper. Client posts plaintext, '
    'receives bcrypt hash for storage via register_mfa_recovery_code. '
    'Avoids needing a KMP-portable bcrypt implementation client-side. '
    'Cost 10 (~100 ms compute).';

COMMIT;
