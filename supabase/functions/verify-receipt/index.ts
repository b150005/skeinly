// Phase H: Server-side IAP receipt validation (alpha1 monetization)
//
// Single Edge Function that handles BOTH platforms:
//   - iOS: App Store Server API JWS verification
//     https://developer.apple.com/documentation/appstoreserverapi
//   - Android: Google Play Developer API
//     https://developers.google.com/android-publisher
//
// Apple App Store Server Notifications V2 + Google Play Real-Time
// Developer Notifications also POST to this endpoint with platform-
// specific webhook payloads, so renewal / cancellation / refund
// state transitions update the `subscriptions` row without a client
// round-trip.
//
// Secrets required (registered via `supabase secrets set` per
// docs/{en,ja}/release-secrets.md "Supabase Edge Function Secrets"):
//   - APP_STORE_CONNECT_API_KEY (raw .p8 PEM body)
//   - APP_STORE_CONNECT_KEY_ID
//   - APP_STORE_CONNECT_ISSUER_ID
//   - APPLE_TEAM_ID
//   - GOOGLE_PLAY_SERVICE_ACCOUNT_JSON (raw JSON)
//
// Database writes use the SUPABASE_SERVICE_ROLE_KEY (auto-injected
// into every Edge Function context) so RLS does not block the upsert.

import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";

// ---------------------------------------------------------------------
// Request / response types
// ---------------------------------------------------------------------

interface VerifyReceiptRequest {
    /** Platform that produced the receipt. */
    platform: "ios" | "android";
    /** User ID owning the purchase. Validated against the JWT bearer token. */
    userId: string;
    /**
     * Raw receipt payload:
     *   - iOS:   JWS string from `Transaction.jsonRepresentation`
     *   - Android: { purchaseToken: string, productId: string }
     */
    receipt: string | { purchaseToken: string; productId: string };
}

interface VerifyReceiptResponse {
    /** True if the user has at least one active or grace-period subscription. */
    isPro: boolean;
    /** ISO 8601 expiration of the active subscription, or null if perpetual / inactive. */
    expiresAt: string | null;
    /** Product ID of the active subscription, or null if none. */
    productId: string | null;
}

// ---------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------

Deno.serve(async (req: Request) => {
    // CORS pre-flight — Edge Functions run from the app's network, but
    // the dashboard / dev tooling occasionally probes via browser.
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

    // Bearer token validation — the client sends its Supabase JWT as
    // `Authorization: Bearer <token>` so we can derive the userId
    // server-side and reject mismatched receipts (defense against
    // a malicious client claiming a different user's userId).
    const authHeader = req.headers.get("authorization");
    if (!authHeader?.startsWith("Bearer ")) {
        return jsonResponse({ error: "unauthorized" }, 401);
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!supabaseUrl || !serviceRoleKey) {
        return jsonResponse({ error: "edge_function_misconfigured" }, 500);
    }

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
        auth: { persistSession: false, autoRefreshToken: false },
    });

    // Verify the JWT and get the authenticated userId.
    const jwt = authHeader.slice("Bearer ".length);
    const { data: userData, error: authError } = await supabase.auth.getUser(jwt);
    if (authError || !userData?.user) {
        return jsonResponse({ error: "unauthorized" }, 401);
    }
    const authenticatedUserId = userData.user.id;

    let body: VerifyReceiptRequest;
    try {
        body = await req.json();
    } catch {
        return jsonResponse({ error: "invalid_json" }, 400);
    }

    if (body.userId !== authenticatedUserId) {
        // Client tried to validate a receipt for a different user.
        // Reject loudly — this is an attack signal worth surfacing in
        // logs / Sentry once Phase F1 lands the Edge Function Sentry SDK.
        return jsonResponse({ error: "user_id_mismatch" }, 403);
    }

    try {
        if (body.platform === "ios") {
            return await verifyAppleReceipt(supabase, authenticatedUserId, body.receipt as string);
        } else if (body.platform === "android") {
            return await verifyGoogleReceipt(
                supabase,
                authenticatedUserId,
                body.receipt as { purchaseToken: string; productId: string },
            );
        } else {
            return jsonResponse({ error: "unsupported_platform" }, 400);
        }
    } catch (e) {
        console.error("verify-receipt failed", e);
        return jsonResponse({ error: "verification_failed" }, 502);
    }
});

// ---------------------------------------------------------------------
// Apple App Store Server API verification (Phase H)
// ---------------------------------------------------------------------

async function verifyAppleReceipt(
    supabase: SupabaseClient,
    userId: string,
    jwsTransaction: string,
): Promise<Response> {
    // TODO Phase H: implement App Store Server API JWS verification.
    // 1. Decode the JWS payload (header + claims + signature).
    // 2. Verify the signature against Apple's root CA chain
    //    (https://www.apple.com/certificateauthority/).
    // 3. Extract `originalTransactionId`, `productId`, `expiresDate`,
    //    `inTrial`, `revocationDate` (if refunded), `autoRenewStatus`.
    // 4. Optionally call /v1/subscriptions/{transactionId} for the
    //    canonical renewal info — fresh for the most recent state.
    // 5. Map App Store status → our enum:
    //      autoRenewStatus=true,  not expired, not revoked  → 'active'
    //      autoRenewStatus=true,  expired but in grace      → 'in_grace_period'
    //      autoRenewStatus=true,  expired billing retry     → 'in_billing_retry'
    //      autoRenewStatus=false, not expired               → 'canceled' (active until expires)
    //      autoRenewStatus=false, expired                   → 'expired'
    //      revocationDate set                               → 'refunded'
    // 6. Upsert into public.subscriptions with the resolved row.
    // 7. Return is_pro / expires_at to the client.
    //
    // Reference SDK candidate: jsr:@apple/app-store-server-library
    // (Apple-published; under evaluation. Falling back to manual JWS
    // decode + JWT signing if the JSR registry copy is stale.)

    // Skeleton stub — returns a placeholder response so the contract
    // is testable end-to-end before Phase H lands the real validator.
    return jsonResponse(
        {
            error: "apple_verification_not_implemented",
            note: "Phase H pending — see supabase/functions/verify-receipt/index.ts TODO",
        },
        501,
    );
}

// ---------------------------------------------------------------------
// Google Play Developer API verification (Phase H)
// ---------------------------------------------------------------------

async function verifyGoogleReceipt(
    supabase: SupabaseClient,
    userId: string,
    receipt: { purchaseToken: string; productId: string },
): Promise<Response> {
    // TODO Phase H: implement Google Play Developer API verification.
    // 1. Mint OAuth 2.0 access token from FIREBASE_SERVICE_ACCOUNT_JSON
    //    (or GOOGLE_PLAY_SERVICE_ACCOUNT_JSON if separate service account).
    //    Scope: https://www.googleapis.com/auth/androidpublisher
    // 2. GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{packageName}/purchases/subscriptionsv2/tokens/{token}
    //    where packageName = "io.github.b150005.knitnote"
    // 3. Extract from `lineItems[0]`: productId, expiryTime,
    //    autoRenewingPlan.autoRenewEnabled, plus top-level
    //    subscriptionState (SUBSCRIPTION_STATE_ACTIVE / IN_GRACE_PERIOD
    //    / ON_HOLD / PAUSED / EXPIRED / CANCELED).
    // 4. Map Google subscriptionState → our enum:
    //      ACTIVE          → 'active'
    //      IN_GRACE_PERIOD → 'in_grace_period'
    //      ON_HOLD         → 'in_billing_retry'
    //      CANCELED        → 'canceled' (active until expires) or 'expired'
    //      EXPIRED         → 'expired'
    //      PAUSED          → 'canceled'
    //    Plus voidedPurchases API for refund detection → 'refunded'.
    // 5. Upsert into public.subscriptions with the resolved row.
    // 6. Return is_pro / expires_at to the client.

    return jsonResponse(
        {
            error: "google_verification_not_implemented",
            note: "Phase H pending — see supabase/functions/verify-receipt/index.ts TODO",
        },
        501,
    );
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*",
        },
    });
}
