// Phase 39 closed beta prep — RevenueCat → Supabase webhook receiver.
//
// Receives subscription state-change events from RevenueCat (renewal,
// cancellation, expiration, refund, etc.) and upserts the corresponding
// row in `public.subscriptions` via the `upsert_subscription_from_webhook`
// RPC (migration 023). The RPC enforces event-timestamp ordering so
// out-of-order RevenueCat retry deliveries cannot revert newer state.
//
// Secrets required (registered via `supabase secrets set` per
// docs/{en,ja}/release-secrets.md EF-5):
//   - REVENUECAT_WEBHOOK_SECRET (the value matched against the incoming
//     `Authorization: Bearer <value>` header; same value entered into the
//     RevenueCat Dashboard → Webhooks → Authorization header field)
//
// Deploy:
//   supabase functions deploy revenuecat-webhook
//
// Configure the webhook URL in RevenueCat Dashboard:
//   - URL: https://<project-ref>.supabase.co/functions/v1/revenuecat-webhook
//   - Authorization header: same value as REVENUECAT_WEBHOOK_SECRET
//   - Environment: leave unfiltered (sandbox AND production both flow
//     through; we distinguish via event.environment in logs only)
//
// Smoke test (after deploy + dashboard wiring):
//
//   curl -X POST https://<project-ref>.supabase.co/functions/v1/revenuecat-webhook \
//     -H "Authorization: Bearer <REVENUECAT_WEBHOOK_SECRET>" \
//     -H "Content-Type: application/json" \
//     -d '{"event":{"type":"TEST","id":"smoke-test-1","event_timestamp_ms":1700000000000}}'
//
//   Expected: HTTP 200 with body `{"status":"ok","note":"test_event_acknowledged"}`.
//
// Database writes use the SUPABASE_SERVICE_ROLE_KEY (auto-injected
// into every Edge Function context) so RLS does not block the upsert.
// The `upsert_subscription_from_webhook` RPC is SECURITY DEFINER so it
// runs with the service-role's privileges regardless.

import { createClient } from "@supabase/supabase-js";
import {
    extractWebhookEvent,
    mapEnvironment,
    mapEventToStatus,
    mapStoreToPlatform,
    type RevenueCatWebhookPayload,
} from "./mapping.ts";

// ---------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------

const RFC_4122_UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// ---------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------

Deno.serve(async (req: Request) => {
    if (req.method === "OPTIONS") {
        return new Response(null, {
            headers: {
                "Access-Control-Allow-Origin": "*",
                "Access-Control-Allow-Headers": "authorization, content-type",
                "Access-Control-Allow-Methods": "POST, OPTIONS",
            },
        });
    }

    if (req.method !== "POST") {
        return jsonResponse({ error: "method_not_allowed" }, 405);
    }

    // Authorization is the shared secret stored in Supabase Edge Function
    // secrets (REVENUECAT_WEBHOOK_SECRET) and mirrored in the RevenueCat
    // dashboard's Webhook Authorization field. Constant-time comparison
    // avoids leaking the secret length via timing side channel.
    const expectedSecret = Deno.env.get("REVENUECAT_WEBHOOK_SECRET");
    if (!expectedSecret) {
        console.error("revenuecat-webhook: REVENUECAT_WEBHOOK_SECRET not configured");
        return jsonResponse({ error: "edge_function_misconfigured" }, 500);
    }

    const authHeader = req.headers.get("authorization") ?? "";
    if (!authHeader.startsWith("Bearer ")) {
        return jsonResponse({ error: "unauthorized" }, 401);
    }
    const incomingSecret = authHeader.slice("Bearer ".length);
    if (!constantTimeEqual(incomingSecret, expectedSecret)) {
        return jsonResponse({ error: "unauthorized" }, 401);
    }

    let payload: RevenueCatWebhookPayload;
    try {
        payload = (await req.json()) as RevenueCatWebhookPayload;
    } catch {
        return jsonResponse({ error: "invalid_json" }, 400);
    }

    const event = extractWebhookEvent(payload);
    if (!event) {
        return jsonResponse({ error: "missing_event" }, 400);
    }

    // RevenueCat dashboard "Send test event" button delivers TEST events.
    // Acknowledge with 200 + a visible note so the dashboard reports
    // success but no subscription state is modified.
    if (event.type === "TEST") {
        console.log("revenuecat-webhook: TEST event acknowledged", {
            event_id: event.id,
            environment: event.environment ?? "unknown",
        });
        return jsonResponse({ status: "ok", note: "test_event_acknowledged" }, 200);
    }

    // SUBSCRIBER_ALIAS / TRANSFER events restructure RevenueCat's
    // user/subscription mapping. v1 logs them and returns 200 — the
    // next state event for the new app_user_id will write the resolved
    // state through. Phase H+ may revisit if alias-merge correctness
    // becomes load-bearing.
    if (event.type === "SUBSCRIBER_ALIAS" || event.type === "TRANSFER") {
        console.log(`revenuecat-webhook: ${event.type} acknowledged (no state write)`, {
            event_id: event.id,
            app_user_id: event.app_user_id,
        });
        return jsonResponse({ status: "ok", note: "alias_or_transfer_acknowledged" }, 200);
    }

    // app_user_id must be a UUID matching auth.users.id. Anonymous RC
    // IDs ($RCAnonymousID:xxxx) cannot be tied to a Skeinly account —
    // they indicate the auth bridge wasn't called before the purchase
    // flow started. Log + return 200 so RevenueCat doesn't retry; the
    // webhook can't do anything useful with an anonymous ID.
    const appUserId = event.app_user_id;
    if (!appUserId || !RFC_4122_UUID_REGEX.test(appUserId)) {
        console.warn("revenuecat-webhook: non-UUID app_user_id, ignoring", {
            event_id: event.id,
            event_type: event.type,
            app_user_id_prefix: appUserId?.slice(0, 32) ?? null,
        });
        return jsonResponse(
            { status: "ok", note: "anonymous_or_invalid_app_user_id_ignored" },
            200,
        );
    }

    // Map RevenueCat store → our platform enum. Unknown stores (Amazon,
    // RC_BILLING, etc.) aren't supported by Skeinly v1 — log + 200 so
    // RevenueCat doesn't retry.
    const platform = mapStoreToPlatform(event.store);
    if (!platform) {
        console.warn("revenuecat-webhook: unsupported store, ignoring", {
            event_id: event.id,
            store: event.store ?? "unknown",
        });
        return jsonResponse({ status: "ok", note: "unsupported_store_ignored" }, 200);
    }

    const status = mapEventToStatus(event.type, event.cancel_reason);
    if (!status) {
        console.warn("revenuecat-webhook: unmapped event type, ignoring", {
            event_id: event.id,
            event_type: event.type,
        });
        return jsonResponse({ status: "ok", note: "unmapped_event_type_ignored" }, 200);
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!supabaseUrl || !serviceRoleKey) {
        return jsonResponse({ error: "edge_function_misconfigured" }, 500);
    }

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
        auth: { persistSession: false, autoRefreshToken: false },
    });

    const expiresAt =
        event.expiration_at_ms !== undefined && event.expiration_at_ms !== null
            ? new Date(event.expiration_at_ms).toISOString()
            : null;
    const eventTimestamp = new Date(event.event_timestamp_ms).toISOString();
    const environment = mapEnvironment(event.environment);

    const { data: rowId, error: rpcError } = await supabase.rpc(
        "upsert_subscription_from_webhook",
        {
            p_user_id: appUserId,
            p_platform: platform,
            p_product_id: event.product_id,
            p_status: status,
            p_original_transaction_id: event.original_transaction_id ?? null,
            p_expires_at: expiresAt,
            p_event_timestamp: eventTimestamp,
            p_is_in_trial: event.is_in_trial ?? false,
            p_auto_renew_status: event.auto_renew_status ?? true,
            p_latest_receipt: payload, // store the full webhook envelope as audit trail
            p_environment: environment,
        },
    );

    if (rpcError) {
        console.error("revenuecat-webhook: upsert RPC failed", {
            event_id: event.id,
            event_type: event.type,
            app_user_id: appUserId,
            rpc_error: rpcError.message,
        });
        // 500 so RevenueCat retries — transient PG errors should
        // re-deliver; persistent errors will surface in logs after
        // RevenueCat's retry budget exhausts (~24h, ~5 attempts).
        return jsonResponse({ error: "upsert_failed" }, 500);
    }

    console.log("revenuecat-webhook: upsert succeeded", {
        event_id: event.id,
        event_type: event.type,
        environment,
        app_user_id: appUserId,
        platform,
        status,
        row_id: rowId,
    });

    return jsonResponse({ status: "ok", row_id: rowId }, 200);
});

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

function jsonResponse(body: unknown, status: number): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*",
        },
    });
}

/**
 * Constant-time equality check to avoid leaking secret length / byte
 * positions via timing side channel. JavaScript's `===` short-circuits
 * on first divergent character; this iterates the full length.
 */
function constantTimeEqual(a: string, b: string): boolean {
    if (a.length !== b.length) {
        // Length mismatch: do a dummy compare against `a` itself to keep
        // the per-call cost stable. The XOR-self loop is a no-op (always
        // yields 0); we reference `dummy` in the return so the engine
        // cannot fold the loop. Always returns false — length mismatch
        // implies inequality.
        let dummy = 0;
        for (let i = 0; i < a.length; i++) {
            dummy |= a.charCodeAt(i) ^ a.charCodeAt(i);
        }
        return dummy !== 0;
    }
    let mismatch = 0;
    for (let i = 0; i < a.length; i++) {
        mismatch |= a.charCodeAt(i) ^ b.charCodeAt(i);
    }
    return mismatch === 0;
}
