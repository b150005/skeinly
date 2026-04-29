-- Phase H: IAP / Subscription state (alpha1 monetization)
-- Source of truth for "is user Pro" — written exclusively by the
-- `verify-receipt` Edge Function (server-side StoreKit 2 / Play Billing
-- receipt validation). Client reads via RLS (own row SELECT only) for
-- paywall enforcement; no client-side INSERT / UPDATE / DELETE.
--
-- Apple App Store Server Notifications V2 + Google Play Real-Time
-- Developer Notifications fan into the same Edge Function via webhook
-- endpoints, so renewal / cancellation / refund / billing-retry state
-- transitions update this table without a client round-trip.
--
-- alpha1 testers get auto-Pro via a sentinel row with platform =
-- 'alpha-grant' and expires_at = NULL (perpetual until alpha closes).

CREATE TABLE IF NOT EXISTS public.subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    platform TEXT NOT NULL CHECK (platform IN ('ios', 'android', 'alpha-grant')),
    product_id TEXT NOT NULL CHECK (product_id IN (
        'knitnote.pro.monthly',
        'knitnote.pro.yearly',
        'knitnote.pro.alpha'
    )),
    status TEXT NOT NULL CHECK (status IN (
        'active',
        'expired',
        'canceled',
        'in_grace_period',
        'in_billing_retry',
        'refunded'
    )),
    -- Apple: `originalTransactionId` from the JWS payload.
    -- Google: `purchaseToken` from `Subscription.purchaseToken`.
    -- alpha-grant: `alpha-<user_id>` (deterministic, idempotent).
    original_transaction_id TEXT,
    expires_at TIMESTAMPTZ,             -- NULL for alpha-grant (perpetual)
    is_in_trial BOOLEAN NOT NULL DEFAULT false,
    auto_renew_status BOOLEAN NOT NULL DEFAULT true,
    -- Server-side raw receipt blob, retained for audit / re-verification.
    -- Truncated by Apple / Google policies; we store whatever fits.
    latest_receipt JSONB,
    last_verified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, platform, original_transaction_id)
);

-- Hot-path index for "is this user Pro right now". Covers the
-- `is_pro(uid)` RPC + client SELECT for paywall.
CREATE INDEX IF NOT EXISTS idx_subscriptions_active
    ON public.subscriptions (user_id, expires_at)
    WHERE status IN ('active', 'in_grace_period');

-- updated_at auto-touch on UPDATE so client cache invalidation stays
-- correct even when the Edge Function only sets `status`.
CREATE OR REPLACE FUNCTION public.touch_subscriptions_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS subscriptions_set_updated_at ON public.subscriptions;
CREATE TRIGGER subscriptions_set_updated_at
    BEFORE UPDATE ON public.subscriptions
    FOR EACH ROW
    EXECUTE FUNCTION public.touch_subscriptions_updated_at();

-- ---------------------------------------------------------------------
-- Row-Level Security
-- ---------------------------------------------------------------------
ALTER TABLE public.subscriptions ENABLE ROW LEVEL SECURITY;

-- Read: users see only their own subscription rows for paywall
-- enforcement on the client side.
CREATE POLICY "subscriptions_select_own"
    ON public.subscriptions FOR SELECT
    USING (auth.uid() = user_id);

-- INTENTIONALLY NO INSERT / UPDATE / DELETE policies for the public role.
-- The Edge Function `verify-receipt` runs with the service-role key
-- and bypasses RLS for writes. Client apps cannot self-mint a Pro
-- subscription by inserting into this table.

-- ---------------------------------------------------------------------
-- Helper RPC: is_pro(uid) — single source of truth for paywall checks
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.is_pro(uid UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.subscriptions
        WHERE user_id = uid
          AND status IN ('active', 'in_grace_period')
          AND (expires_at IS NULL OR expires_at > now())
    );
$$;

REVOKE ALL ON FUNCTION public.is_pro(UUID) FROM public;
GRANT EXECUTE ON FUNCTION public.is_pro(UUID) TO authenticated;

-- ---------------------------------------------------------------------
-- alpha-grant helper: idempotent INSERT for alpha tester auto-Pro.
-- Called once when an alpha tester signs up, OR retroactively for
-- existing alpha users via a one-shot admin SQL pass at alpha kickoff.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.grant_alpha_pro(uid UUID)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_id UUID;
BEGIN
    INSERT INTO public.subscriptions (
        user_id,
        platform,
        product_id,
        status,
        original_transaction_id,
        expires_at,
        is_in_trial,
        auto_renew_status
    ) VALUES (
        uid,
        'alpha-grant',
        'knitnote.pro.alpha',
        'active',
        'alpha-' || uid::text,
        NULL,
        false,
        false
    )
    ON CONFLICT (user_id, platform, original_transaction_id) DO NOTHING
    RETURNING id INTO v_id;

    RETURN v_id;
END;
$$;

REVOKE ALL ON FUNCTION public.grant_alpha_pro(UUID) FROM public;
-- NOT exposed to authenticated role — only callable via service role
-- (admin SQL or Edge Function with service-role key).

-- ---------------------------------------------------------------------
-- Realtime: NOT enabled for subscriptions.
-- ---------------------------------------------------------------------
-- Subscription state changes are observed by the client via:
--   1. Initial fetch on app foreground (StoreKit 2 / Play Billing
--      transaction listener triggers a Supabase SELECT).
--   2. Polling on Settings > Manage Subscription open (cheap, low-frequency).
-- Realtime fanout would add a 6th channel per authenticated user
-- (currently 5: projects, progress, patterns, project-segments,
-- chart-revisions) and bills against the free tier's 200-concurrent-
-- connection cap with no UX gain — subscription state is not push-time-
-- sensitive in the way collab notifications are.
