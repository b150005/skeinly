-- Pre-alpha A15 — Kill Switch / Maintenance mode extension to public.app_config.
--
-- Purpose: when a server-side incident or planned maintenance window makes
-- the backend unavailable, flip `maintenance_mode_active = true` to surface
-- a non-dismissable maintenance screen to all clients. Distinct from
-- force-update (migration 028) — force-update tells users their version is
-- too old; maintenance tells users service is temporarily unavailable
-- regardless of version. Maintenance takes priority over force-update at
-- the client gate because if service is down the user can't act on a
-- force-update CTA anyway.
--
-- Schema delta:
--   + maintenance_mode_active BOOLEAN NOT NULL DEFAULT false
--   + maintenance_message_en TEXT (nullable, fallback to bundled default)
--   + maintenance_message_ja TEXT (nullable, fallback to bundled default)
--
-- Operational activation runbook (Owner / on-call):
--   UPDATE public.app_config
--   SET maintenance_mode_active = true,
--       maintenance_message_en = 'We are upgrading the backend. Please try again in 10 minutes.',
--       maintenance_message_ja = 'バックエンドのアップグレード中です。10 分後に再度お試しください。'
--   WHERE id = 'main';
--
--   -- Deactivate:
--   UPDATE public.app_config SET maintenance_mode_active = false WHERE id = 'main';
--
-- Client-side gate logic (see shared/.../ui/forceupdate/AppConfigGate.kt):
--   if maintenance_mode_active = true → MaintenanceScreen (no update CTA, retry only)
--   else if currentVersion < min_required_version_* → ForceUpdateScreen
--   else → normal app

BEGIN;

ALTER TABLE public.app_config
    ADD COLUMN maintenance_mode_active BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN maintenance_message_en TEXT,
    ADD COLUMN maintenance_message_ja TEXT;

COMMENT ON COLUMN public.app_config.maintenance_mode_active IS
    'Pre-alpha A15 — when true, all clients show a blocking maintenance screen at startup. Distinct from force-update (version-floor gate).';

-- Replace the get_app_config() RPC to surface the new columns. SECURITY
-- DEFINER + search_path + STABLE are preserved verbatim from migration 028.
-- DROP+CREATE rather than CREATE OR REPLACE because Postgres rejects an
-- OR REPLACE that changes the function's return-type signature (42P13:
-- "cannot change return type of existing function").
DROP FUNCTION public.get_app_config();

CREATE FUNCTION public.get_app_config()
RETURNS TABLE (
    min_required_version_android TEXT,
    min_required_version_ios TEXT,
    force_update_message_en TEXT,
    force_update_message_ja TEXT,
    maintenance_mode_active BOOLEAN,
    maintenance_message_en TEXT,
    maintenance_message_ja TEXT
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
        force_update_message_ja,
        maintenance_mode_active,
        maintenance_message_en,
        maintenance_message_ja
    FROM public.app_config
    WHERE id = 'main';
$$;

-- Grants stay anon + authenticated (offline-first read path; the gate
-- runs at startup before any auth check). Already granted in migration
-- 028 + CREATE OR REPLACE preserves them, but re-stating for clarity.
GRANT EXECUTE ON FUNCTION public.get_app_config() TO anon, authenticated;

COMMIT;
