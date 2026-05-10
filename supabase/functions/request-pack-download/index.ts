// Phase 41.1.5: Symbol pack download mediation (ADR-016 §3.3 post-Path-A pivot).
//
// Replaces the originally-proposed Postgres SECURITY DEFINER RPC. The
// Postgres path was infeasible — current Supabase Postgres exposes no
// `storage.create_signed_url(...)` helper, and the alternatives (`pg_net`
// + Storage REST callback, or `pgjwt` + manual JWT signing with a
// vault-stored secret) introduce extension dependencies + signing-key
// custody surface that exceed what is justified for entitlement gating.
// The Edge Function pattern matches the `revenuecat-webhook` (Phase 39
// prep, 2026-05-08) precedent and Supabase's idiomatic recommendation
// for download mediation.
//
// Flow per ADR-016 §3.3:
//   1. `verify_jwt: true` (deploy flag) — Supabase rejects requests
//      without a valid `Authorization: Bearer <jwt>` BEFORE this body
//      runs. We re-validate inside via `auth.getUser(jwt)` to derive
//      the authenticated user_id.
//   2. Per-user sliding-window rate limiter (10 calls / 60s per
//      user_id, in-memory `Map<user_id, timestamps[]>`). Closes
//      ADR-016 §10 Q6.
//   3. Look up `symbol_packs` row by pack_id via service-role client.
//      404 if not found.
//   4. Branch on tier — pro packs require an active row in
//      `subscriptions` (status IN ('active','in_grace_period') AND
//      (expires_at IS NULL OR expires_at > now())); 403 otherwise.
//   5. Mint a 5-minute signed URL via Storage REST API
//      (`POST /storage/v1/object/sign/symbol-packs/<payload_path>?expiresIn=300`).
//   6. Emit a structured log line for the §7 telemetry roll-up +
//      Sentry / `mcp__supabase__get_logs service=edge-function` triage.
//   7. Return the signed-URL envelope.
//
// Service-role key custody: stored via `supabase secrets set
// SUPABASE_SERVICE_ROLE_KEY=...`; never embedded in client binaries;
// never logged. Deno auto-injects `SUPABASE_URL` and
// `SUPABASE_SERVICE_ROLE_KEY` at runtime — we read both from
// `Deno.env.get(...)` at request time.
//
// Refund-revocation semantics: a `subscriptions.status='refunded'`
// write (via `revenuecat-webhook` Edge Function on a CANCELLATION event
// with `cancel_reason in ('REFUND','REFUNDED_FOR_ISSUE')`) causes the
// *next* invocation by that user to return 403, even if the user's
// local `EntitlementResolver` cache hasn't yet processed the next
// `SubscriptionRepository.refresh`. The 5-minute signed-URL TTL bounds
// residual access through any in-flight URL the user already holds.

import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { checkAndRecordRateLimit, RATE_LIMIT_WINDOW_MS } from "./rate-limit.ts";

// ---------------------------------------------------------------------
// Request / response types
// ---------------------------------------------------------------------

interface RequestPackDownloadRequest {
    /** Stable pack id matching `public.symbol_packs.id`. */
    pack_id: string;
}

interface RequestPackDownloadSuccess {
    /** Storage signed URL with `?token=<jwt>`; 5-min TTL. */
    payload_url: string;
    /** ISO 8601 expiration of the signed URL — `now() + 5 minutes`. */
    payload_url_ttl: string;
    /** Matches `symbol_packs.version` at request time. Bumped on glyph refinement. */
    current_version: number;
    /** Matches `symbol_packs.payload_size` at request time. Bytes. */
    payload_size: number;
}

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

    const jwt = authHeader.slice("Bearer ".length);
    const { data: userData, error: authError } = await supabase.auth.getUser(jwt);
    if (authError || !userData?.user) {
        return jsonResponse({ error: "unauthorized" }, 401);
    }
    const userId = userData.user.id;

    // Rate-limit BEFORE parsing the body — a flooding caller costs us
    // ~nothing this way.
    if (!checkAndRecordRateLimit(userId, Date.now())) {
        return jsonResponse(
            {
                error: "rate_limited",
                retry_after_seconds: Math.ceil(RATE_LIMIT_WINDOW_MS / 1000),
            },
            429,
        );
    }

    let body: RequestPackDownloadRequest;
    try {
        body = await req.json();
    } catch {
        return jsonResponse({ error: "invalid_json" }, 400);
    }

    if (typeof body?.pack_id !== "string" || body.pack_id.length === 0) {
        return jsonResponse({ error: "missing_pack_id" }, 400);
    }

    try {
        return await handleDownloadRequest(
            supabase,
            supabaseUrl,
            serviceRoleKey,
            userId,
            body.pack_id,
        );
    } catch (e) {
        // Stack trace stays in the Edge Function logs for triage but
        // never surfaces to the client — error envelope is closed-set.
        console.error("request-pack-download failed", {
            user_id: userId,
            pack_id: body.pack_id,
            err: e,
        });
        return jsonResponse({ error: "internal_error" }, 500);
    }
});

// ---------------------------------------------------------------------
// Core flow
// ---------------------------------------------------------------------

async function handleDownloadRequest(
    supabase: SupabaseClient,
    supabaseUrl: string,
    serviceRoleKey: string,
    userId: string,
    packId: string,
): Promise<Response> {
    // Step 3: pack lookup.
    const { data: packRow, error: packError } = await supabase
        .from("symbol_packs")
        .select("id, tier, version, payload_path, payload_size")
        .eq("id", packId)
        .maybeSingle();

    if (packError) {
        console.error("symbol_packs lookup failed", {
            user_id: userId,
            pack_id: packId,
            err: packError,
        });
        return jsonResponse({ error: "internal_error" }, 500);
    }
    if (!packRow) {
        // 404 echoes pack_id — accepted disclosure since symbol_packs
        // SELECT policy is open-read (every pack id is world-visible
        // for paywall preview metadata anyway).
        return jsonResponse({ error: "pack_not_found", pack_id: packId }, 404);
    }

    // Step 4: entitlement gate for pro tier.
    if (packRow.tier === "pro") {
        const { data: subRow, error: subError } = await supabase
            .from("subscriptions")
            .select("id, expires_at")
            .eq("user_id", userId)
            .in("status", ["active", "in_grace_period"])
            // Defense-in-depth — Postgres `now() < expires_at OR expires_at IS NULL`
            // is enforced server-side in the migration 017 schema; this is just
            // belt-and-suspenders for the in-grace-period window where Postgres
            // already accepts the row.
            .or("expires_at.is.null,expires_at.gt.now()")
            .limit(1)
            .maybeSingle();

        if (subError) {
            console.error("subscriptions lookup failed", {
                user_id: userId,
                pack_id: packId,
                err: subError,
            });
            return jsonResponse({ error: "internal_error" }, 500);
        }
        if (!subRow) {
            return jsonResponse({ error: "pro_entitlement_required", pack_id: packId }, 403);
        }
    }

    // Step 5: mint the signed URL.
    const signResponse = await fetch(
        `${supabaseUrl}/storage/v1/object/sign/symbol-packs/${packRow.payload_path}`,
        {
            method: "POST",
            headers: {
                Authorization: `Bearer ${serviceRoleKey}`,
                "Content-Type": "application/json",
            },
            body: JSON.stringify({ expiresIn: 300 }),
        },
    );

    if (!signResponse.ok) {
        const text = await signResponse.text().catch(() => "");
        console.error("storage sign failed", {
            user_id: userId,
            pack_id: packId,
            payload_path: packRow.payload_path,
            status: signResponse.status,
            body: text.slice(0, 500),
        });
        return jsonResponse({ error: "internal_error" }, 500);
    }

    const signed: { signedURL?: string } = await signResponse.json();
    if (typeof signed.signedURL !== "string") {
        console.error("storage sign returned no signedURL", {
            user_id: userId,
            pack_id: packId,
            signed,
        });
        return jsonResponse({ error: "internal_error" }, 500);
    }

    // Storage's `signedURL` is path + token only — prefix the project
    // origin so the client can fetch it without further URL mangling.
    const fullUrl = signed.signedURL.startsWith("http")
        ? signed.signedURL
        : `${supabaseUrl}${signed.signedURL.startsWith("/") ? "" : "/"}${signed.signedURL}`;

    const ttlIso = new Date(Date.now() + 5 * 60_000).toISOString();
    const responseBody: RequestPackDownloadSuccess = {
        payload_url: fullUrl,
        payload_url_ttl: ttlIso,
        current_version: packRow.version,
        payload_size: packRow.payload_size,
    };

    // Step 6: structured log for §7 telemetry. Stays single-line so
    // `mcp__supabase__get_logs service=edge-function` rendering is
    // grep-friendly.
    console.log(
        JSON.stringify({
            event: "pack_download_signed",
            user_id: userId,
            pack_id: packId,
            version: packRow.version,
            tier: packRow.tier,
            ts: new Date().toISOString(),
        }),
    );

    return jsonResponse(responseBody);
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
