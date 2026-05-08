-- Migration 023 — Phase 39 closed beta prep
--
-- Adds `upsert_subscription_from_webhook(...)` SECURITY DEFINER RPC
-- that the upcoming `revenuecat-webhook` Edge Function calls to upsert
-- subscription state events delivered by RevenueCat. Encapsulates:
--   1. Conditional upsert keyed on (user_id, platform, original_transaction_id)
--      — the same UNIQUE constraint defined in migration 017.
--   2. event-timestamp ordering — only updates when the new event is
--      newer than the existing row's `last_verified_at`. Out-of-order
--      RevenueCat retry deliveries (which CAN happen per RevenueCat docs:
--      "delivery is not guaranteed to be in order") cannot revert a
--      newer state to an older one.
--   3. atomic semantics — the entire upsert + ordering check happens
--      in one statement under PostgreSQL's row-level locking, so two
--      concurrent webhook deliveries for the same subscription cannot
--      interleave.
--
-- Why a SECURITY DEFINER RPC rather than direct PostgREST UPSERT from
-- the Edge Function:
--   - The ordering check needs a conditional ("only update if newer")
--     that PostgREST's `?on_conflict` syntax cannot express directly.
--   - Encapsulating the upsert + ordering in SQL keeps the contract
--     atomic; expressing it in TypeScript would require a SELECT-then-
--     UPDATE round-trip with explicit row locking — error-prone.
--   - Same precedent as `merge_pull_request` (migration 016) —
--     server-side write atomicity for cross-row invariants.
--
-- The function runs with service-role privileges (called from the
-- Edge Function's service-role client), bypassing RLS. The `subscriptions`
-- table has no INSERT/UPDATE/DELETE RLS policies for `authenticated`
-- role, so this RPC is the only path that can write rows from the
-- webhook context.

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
    p_latest_receipt JSONB DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_id UUID;
BEGIN
    -- Validate platform / product_id / status against the same CHECK
    -- constraints in migration 017's table definition. Defense-in-depth:
    -- the Edge Function should map RevenueCat's event types to these
    -- closed enums, but the RPC re-validates so a mis-mapped value
    -- raises here rather than silently failing the CHECK constraint
    -- with a less-specific PG error.
    IF p_platform NOT IN ('ios', 'android', 'alpha-grant') THEN
        RAISE EXCEPTION 'Invalid platform: %', p_platform USING ERRCODE = '22023';
    END IF;
    IF p_product_id NOT IN ('skeinly.pro.monthly', 'skeinly.pro.yearly', 'skeinly.pro.alpha') THEN
        RAISE EXCEPTION 'Invalid product_id: %', p_product_id USING ERRCODE = '22023';
    END IF;
    IF p_status NOT IN ('active', 'expired', 'canceled', 'in_grace_period', 'in_billing_retry', 'refunded') THEN
        RAISE EXCEPTION 'Invalid status: %', p_status USING ERRCODE = '22023';
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
        last_verified_at
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
        p_event_timestamp
    )
    ON CONFLICT (user_id, platform, original_transaction_id)
    DO UPDATE SET
        status = EXCLUDED.status,
        product_id = EXCLUDED.product_id,
        expires_at = EXCLUDED.expires_at,
        is_in_trial = EXCLUDED.is_in_trial,
        auto_renew_status = EXCLUDED.auto_renew_status,
        latest_receipt = COALESCE(EXCLUDED.latest_receipt, public.subscriptions.latest_receipt),
        last_verified_at = EXCLUDED.last_verified_at
    -- Ordering guard: only apply the UPDATE when the incoming event is
    -- strictly newer than the existing row's `last_verified_at`. A
    -- duplicate retry of the same event (same timestamp) is a no-op;
    -- an out-of-order older retry after a newer event has landed is
    -- silently dropped. The trigger-driven `updated_at` still fires on
    -- accepted updates so client cache invalidation stays correct.
    WHERE public.subscriptions.last_verified_at < EXCLUDED.last_verified_at
    RETURNING id INTO v_id;

    -- If the WHERE clause filtered out the UPDATE (older event), v_id
    -- is null because RETURNING is bypassed. Fall back to the existing
    -- row's id so the caller has a stable handle for logging.
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
    UUID, TEXT, TEXT, TEXT, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, BOOLEAN, BOOLEAN, JSONB
) FROM public;

-- service-role calls bypass GRANT — the Edge Function's service-role
-- client invokes this without needing an explicit GRANT. We deliberately
-- do NOT grant EXECUTE to `authenticated` because client apps must not
-- be able to mint subscription rows directly.
