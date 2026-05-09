-- Phase 24.1 (ADR-017 §3.5): per-user device tokens for push notifications
--
-- Stores APNs / FCM device tokens that the `notify-on-write` Edge Function
-- looks up at notification fan-out time. Client-side `PushTokenRegistrar`
-- (Phase 24.2) upserts into this table; the Edge Function reads via
-- service-role bypass when a Database Webhook fires.
--
-- Schema notes:
-- * `(user_id, platform, token)` UNIQUE composite key — same physical
--   device's token can be re-registered idempotently on app foreground;
--   token rotation comes through as a new (user_id, platform, NEW_TOKEN)
--   row.
-- * `locale` is BCP-47 closed enum (EN + JA only — Skeinly's i18n
--   supported set). Edge Function reads this column to render localized
--   notification body server-side per ADR-017 §3.7.
-- * Token rotation handled by client upsert (ON CONFLICT DO UPDATE).
--   Invalid token cleanup happens reactively via the Edge Function's
--   APNs / FCM error code path — when APNs returns 410 BadDeviceToken
--   or FCM returns 404 UNREGISTERED, the Edge Function deletes the row
--   (ADR-017 §3.10). No proactive sweep cron.
-- * ON DELETE CASCADE on user_id — when a user deletes their account
--   via the `delete_own_account` RPC (ADR-005), all their device tokens
--   cascade out atomically. No dangling rows.
--
-- Phase 24.1 lands schema only; Edge Function `notify-on-write` shell
-- alongside in the same slice writes nothing here yet (placeholder
-- log-only `dispatchPush`). Phase 24.2 wires the client-side upsert
-- path. Phase 24.3 wires the actual APNs / FCM fan-out + 410/404 cleanup.

CREATE TABLE IF NOT EXISTS public.device_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    platform        TEXT NOT NULL CHECK (platform IN ('ios', 'android')),
    -- Apple device tokens are 64-hex chars (32 bytes encoded). FCM
    -- registration tokens are ~163 chars (Base64-URL encoded). Neither
    -- platform exposes a documented upper bound; TEXT (no length cap)
    -- mirrors the upstream contract.
    token           TEXT NOT NULL,
    -- BCP-47 locale tag. Edge Function reads this to select EN/JA from
    -- the in-function notification template table. Closed enum; adding
    -- a 3rd locale requires an `ALTER TABLE ... DROP CONSTRAINT` +
    -- re-add migration AND a new entry in the Edge Function string table.
    locale          TEXT NOT NULL DEFAULT 'en-US' CHECK (locale IN ('en-US', 'ja-JP')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, platform, token)
);

-- Hot-path index for the `notify-on-write` Edge Function's recipient
-- lookup: SELECT token, platform, locale FROM device_tokens WHERE user_id IN (...).
CREATE INDEX IF NOT EXISTS idx_device_tokens_user_platform
    ON public.device_tokens (user_id, platform);

-- updated_at auto-touch on UPDATE so reactivation paths (token rotation,
-- locale change on system language switch) record the freshness signal.
-- search_path locked per Supabase database-linter 0011
-- (function_search_path_mutable). Defense-in-depth: a role with mutable
-- search_path could be tricked into executing a malicious operator/function
-- from an attacker-controlled schema. Pinning to `public, pg_temp` prevents
-- hijacking.
CREATE OR REPLACE FUNCTION public.touch_device_tokens_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS device_tokens_set_updated_at ON public.device_tokens;
CREATE TRIGGER device_tokens_set_updated_at
    BEFORE UPDATE ON public.device_tokens
    FOR EACH ROW
    EXECUTE FUNCTION public.touch_device_tokens_updated_at();

-- ---------------------------------------------------------------------
-- Row-Level Security
-- ---------------------------------------------------------------------
ALTER TABLE public.device_tokens ENABLE ROW LEVEL SECURITY;

-- Read: users see only their own tokens. Defense-in-depth — the
-- Edge Function uses service-role and bypasses this anyway, but if a
-- future client-side debug surface needs to enumerate "my devices",
-- it will Just Work without a policy change.
CREATE POLICY "device_tokens_select_own"
    ON public.device_tokens FOR SELECT
    USING (auth.uid() = user_id);

-- Insert: users register their own tokens. The `WITH CHECK` clause
-- prevents an authenticated client from minting a row pointing at
-- another user_id.
CREATE POLICY "device_tokens_insert_own"
    ON public.device_tokens FOR INSERT
    WITH CHECK (auth.uid() = user_id);

-- Update: users update their own rows (e.g. locale change on system
-- language switch). The `USING` + `WITH CHECK` pair prevents a row
-- ownership transfer mid-UPDATE.
CREATE POLICY "device_tokens_update_own"
    ON public.device_tokens FOR UPDATE
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- Delete: users can delete their own tokens (e.g. on logout from a
-- specific device). The Edge Function's reactive cleanup path uses
-- service-role and bypasses this policy.
CREATE POLICY "device_tokens_delete_own"
    ON public.device_tokens FOR DELETE
    USING (auth.uid() = user_id);

-- INTENTIONALLY no SELECT policy for service_role — service_role
-- bypasses RLS by definition. The Edge Function uses
-- SUPABASE_SERVICE_ROLE_KEY (auto-injected) to read all users' tokens
-- at fan-out time.

-- No Realtime publication for `device_tokens` — clients do not need
-- live updates of their own token rows (token registration is a
-- one-shot write per app-foreground; no UI subscribes to live tokens).
-- Saves an Edge Function-irrelevant Realtime channel.
