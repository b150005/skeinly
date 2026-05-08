-- Migration 024 — Phase 39 closed beta infrastructure: sandbox vs production
-- environment separation on `public.subscriptions`.
--
-- Adds an `environment` column so subscription rows can be filtered by
-- sandbox vs production at query time. Without this, post-Phase 40 GA
-- analytics queries get polluted by sandbox dev-test rows (devs running
-- regression tests after launch produce rows that mix with real-user
-- production rows). The column is additive + NOT NULL with DEFAULT
-- 'production' so existing rows + future alpha-grant rows naturally fall
-- into 'production' without backfill gymnastics.
--
-- Why during closed beta vs post-Phase 40:
--   - Closed beta is sandbox-dominated (5–10 testers using Apple Sandbox
--     / Play License test). Adding the column NOW means every webhook
--     event lands with a structurally-correct environment label from day
--     one, no retroactive backfill needed when Phase 40 traffic starts.
--   - The `latest_receipt` JSONB already preserves `event.environment`
--     in raw form, but querying via `latest_receipt->'event'->>'environment'`
--     bypasses the type system + indexes. A dedicated column with
--     CHECK constraint + index is the analytics-grade shape.
--
-- Why `'sandbox'` and `'production'` lowercase (not Apple/Google's
-- uppercase): Postgres convention for closed-enum-textual columns is
-- lowercase (mirrors `subscriptions.platform` ('ios'/'android') +
-- `pull_requests.status` ('open'/'merged'/'closed')). The Edge Function
-- normalizes RevenueCat's "SANDBOX"/"PRODUCTION" → lowercase before
-- calling the RPC.
--
-- Why no CHECK on alpha-grant rows requiring 'production':
-- alpha-grant gives real Pro entitlement to real testers, so it
-- semantically belongs to 'production' rather than 'sandbox' (sandbox
-- specifically denotes IAP receipt origin, not entitlement level). The
-- DEFAULT 'production' catches alpha-grant inserts; the CHECK on
-- environment values keeps things tight.

ALTER TABLE public.subscriptions
    ADD COLUMN IF NOT EXISTS environment TEXT NOT NULL DEFAULT 'production'
        CHECK (environment IN ('production', 'sandbox'));

-- Backfill: extract environment from latest_receipt JSONB if a prior
-- webhook event payload was preserved there. Most rows currently in the
-- table are alpha-grant (no latest_receipt) or pre-webhook test data;
-- this UPDATE is a no-op for those rows and the DEFAULT 'production'
-- already covers them.
UPDATE public.subscriptions
SET environment = LOWER(latest_receipt->'event'->>'environment')
WHERE latest_receipt IS NOT NULL
  AND latest_receipt->'event'->>'environment' IN ('SANDBOX', 'PRODUCTION');

-- Hot-path index for "production active subscriptions" — speeds up the
-- analytics queries that exclude sandbox dev-noise post-Phase 40.
-- The pre-existing idx_subscriptions_active (migration 017) covers the
-- per-user is_pro check; this new partial index covers the cross-user
-- aggregate (e.g. "how many production active subs do we have right now").
CREATE INDEX IF NOT EXISTS idx_subscriptions_active_production
    ON public.subscriptions (user_id, environment, expires_at)
    WHERE status IN ('active', 'in_grace_period');

-- Replace the upsert RPC to accept + write the environment column.
-- DROP first because the parameter signature changes (added p_environment),
-- and Postgres treats overloaded functions as distinct entities — keeping
-- the old signature would let stale callers silently bypass the new
-- column, leaving every row at DEFAULT 'production' regardless of
-- actual receipt origin.
DROP FUNCTION IF EXISTS public.upsert_subscription_from_webhook(
    UUID, TEXT, TEXT, TEXT, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, BOOLEAN, BOOLEAN, JSONB
);

CREATE OR REPLACE FUNCTION public.upsert_subscription_from_webhook(
    p_user_id UUID,
    p_platform TEXT,
    p_product_id TEXT,
    p_status TEXT,
    p_original_transaction_id TEXT,
    p_expires_at TIMESTAMPTZ,
    p_event_timestamp TIMESTAMPTZ,
    p_is_in_trial BOOLEAN DEFAULT FALSE,
    p_auto_renew_status BOOLEAN DEFAULT TRUE,
    p_latest_receipt JSONB DEFAULT NULL,
    p_environment TEXT DEFAULT 'production'
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_id UUID;
BEGIN
    IF p_platform NOT IN ('ios', 'android', 'alpha-grant') THEN
        RAISE EXCEPTION 'Invalid platform: %', p_platform USING ERRCODE = '22023';
    END IF;
    IF p_product_id NOT IN ('skeinly.pro.monthly', 'skeinly.pro.yearly', 'skeinly.pro.alpha') THEN
        RAISE EXCEPTION 'Invalid product_id: %', p_product_id USING ERRCODE = '22023';
    END IF;
    IF p_status NOT IN ('active', 'expired', 'canceled', 'in_grace_period', 'in_billing_retry', 'refunded') THEN
        RAISE EXCEPTION 'Invalid status: %', p_status USING ERRCODE = '22023';
    END IF;
    IF p_environment NOT IN ('production', 'sandbox') THEN
        RAISE EXCEPTION 'Invalid environment: %', p_environment USING ERRCODE = '22023';
    END IF;

    INSERT INTO public.subscriptions (
        user_id,
        platform,
        product_id,
        status,
        original_transaction_id,
        expires_at,
        is_in_trial,
        auto_renew_status,
        latest_receipt,
        last_verified_at,
        environment
    ) VALUES (
        p_user_id,
        p_platform,
        p_product_id,
        p_status,
        p_original_transaction_id,
        p_expires_at,
        p_is_in_trial,
        p_auto_renew_status,
        p_latest_receipt,
        p_event_timestamp,
        p_environment
    )
    ON CONFLICT (user_id, platform, original_transaction_id)
    DO UPDATE SET
        status = EXCLUDED.status,
        product_id = EXCLUDED.product_id,
        expires_at = EXCLUDED.expires_at,
        is_in_trial = EXCLUDED.is_in_trial,
        auto_renew_status = EXCLUDED.auto_renew_status,
        latest_receipt = COALESCE(EXCLUDED.latest_receipt, public.subscriptions.latest_receipt),
        last_verified_at = EXCLUDED.last_verified_at,
        environment = EXCLUDED.environment
    WHERE public.subscriptions.last_verified_at < EXCLUDED.last_verified_at
    RETURNING id INTO v_id;

    IF v_id IS NULL THEN
        SELECT id
            INTO v_id
            FROM public.subscriptions
            WHERE user_id = p_user_id
              AND platform = p_platform
              AND original_transaction_id IS NOT DISTINCT FROM p_original_transaction_id;
    END IF;

    RETURN v_id;
END;
$$;

REVOKE ALL ON FUNCTION public.upsert_subscription_from_webhook(
    UUID, TEXT, TEXT, TEXT, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, BOOLEAN, BOOLEAN, JSONB, TEXT
) FROM public;
