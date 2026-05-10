-- Phase 39 (W4 / 2026-05-11) — app-level config table for force-update gating.
--
-- Purpose: when a breaking schema/API change ships in a new app version
-- (pre-v1 breaking-changes-accepted policy), bump min_required_version_*
-- here. Old clients on the previous version see a non-dismissable update
-- prompt at startup and cannot proceed until they upgrade.
--
-- Single-row pattern: PRIMARY KEY default 'main' + CHECK constraint
-- prevents accidental multi-row state.
--
-- Offline-first read path: clients call public.get_app_config() at every
-- launch + cache the result via multiplatform-settings. On offline launch,
-- the cached value gates startup. First-install offline (no cache yet)
-- skips the gate (fail-open) so the app is reachable without network on
-- a fresh install — the gate engages from the second launch onward,
-- preventing offline stranding while still enforcing the kill-switch
-- once any cache exists.
--
-- Mutation path: Supabase Dashboard SQL editor + service-role only. RLS
-- denies all PostgREST writes (no INSERT/UPDATE/DELETE policy → default
-- deny). The single seed row is inserted by this migration. Subsequent
-- updates use:
--   UPDATE public.app_config
--   SET min_required_version_android = 'X.Y.Z',
--       min_required_version_ios = 'X.Y.Z',
--       force_update_message_en = '...',
--       force_update_message_ja = '...'
--   WHERE id = 'main';

BEGIN;

CREATE TABLE public.app_config (
    id TEXT PRIMARY KEY DEFAULT 'main',
    min_required_version_android TEXT NOT NULL,
    min_required_version_ios TEXT NOT NULL,
    -- Custom force-update message visible to users in their locale.
    -- Nullable: when null, the client falls back to a default localized
    -- copy bundled in i18n resources (`force_update_message_default_*`)
    -- so a kill-switch trigger doesn't depend on copy being set.
    force_update_message_en TEXT,
    force_update_message_ja TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT app_config_single_row CHECK (id = 'main')
);

COMMENT ON TABLE public.app_config IS
    'Phase 39 (W4) — single-row force-update config. Single source of truth for min_required_version_*; bump to enforce client upgrades on breaking changes.';

-- Seed initial row matching the v0.1.0 alpha launch baseline. Anyone
-- already on 0.1.0+ is allowed; the gate engages only when this row's
-- min_required_version_* is bumped above the client's installed version.
INSERT INTO public.app_config (
    id,
    min_required_version_android,
    min_required_version_ios
) VALUES (
    'main',
    '0.1.0',
    '0.1.0'
);

-- BEFORE UPDATE trigger to keep updated_at fresh on every config bump.
-- Audit trail = updated_at + Postgres logical-replication trail (not
-- captured in this migration, but available via Supabase activity log).
CREATE OR REPLACE FUNCTION public.touch_app_config_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER app_config_touch_updated_at
    BEFORE UPDATE ON public.app_config
    FOR EACH ROW
    EXECUTE FUNCTION public.touch_app_config_updated_at();

-- RLS — read-only for both anon and authenticated. Mutation requires
-- service-role (Dashboard SQL editor) per the no-policy default-deny
-- pattern.
ALTER TABLE public.app_config ENABLE ROW LEVEL SECURITY;

CREATE POLICY "app_config readable by anyone"
    ON public.app_config
    FOR SELECT
    USING (true);

-- RPC entry point. Returns the single config row. SECURITY DEFINER so
-- the function bypasses RLS internally — though SELECT RLS already
-- allows anyone, the DEFINER form keeps the contract stable if RLS
-- ever tightens further.
--
-- Why an RPC instead of `supabase.from('app_config').select('*')`:
-- a single function call is a cleaner contract for the client repo
-- (one call, one error path, one cache key) and keeps the column-list
-- coupling to the schema ownership inside the database, not in the
-- client.
CREATE OR REPLACE FUNCTION public.get_app_config()
RETURNS TABLE (
    min_required_version_android TEXT,
    min_required_version_ios TEXT,
    force_update_message_en TEXT,
    force_update_message_ja TEXT
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
STABLE
AS $$
    SELECT
        min_required_version_android,
        min_required_version_ios,
        force_update_message_en,
        force_update_message_ja
    FROM public.app_config
    WHERE id = 'main';
$$;

-- Grant execute to the public-facing PostgREST roles. `anon` is the
-- offline-first / pre-login path; `authenticated` is the post-login
-- path. Both must be able to read this config — the gate runs at app
-- startup before any auth check.
GRANT EXECUTE ON FUNCTION public.get_app_config() TO anon, authenticated;

-- Revoke direct REST-API execute on the trigger function. Triggers fire
-- as the function owner (postgres) regardless of REST grants, so this
-- only blocks `/rest/v1/rpc/touch_app_config_updated_at` from being
-- callable directly by clients — closes the
-- `anon_security_definer_function_executable` linter warning without
-- breaking the BEFORE UPDATE trigger above.
REVOKE EXECUTE ON FUNCTION public.touch_app_config_updated_at() FROM PUBLIC;

COMMIT;
