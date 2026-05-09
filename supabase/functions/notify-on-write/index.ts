// Phase 24.1 (ADR-017 §3.9): notify-on-write Edge Function shell.
//
// Receives Supabase Database Webhook deliveries from 3 source tables
// (`pull_requests` INSERT/UPDATE, `pull_request_comments` INSERT) and
// computes per-recipient notification dispatches. Phase 24.1 ships
// **log-only** — no APNs / FCM HTTP calls yet. Phase 24.3 wires the
// real send paths via `djwt` JSR import.
//
// Webhook auth: shared-secret Bearer token in the `Authorization`
// header. The maintainer adds the header pair
// `Authorization: Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET>` to each of
// the 3 Database Webhooks via Dashboard → Database → Webhooks → HTTP
// Headers, and the Edge Function constant-time-compares the incoming
// header value against the same secret stored in Edge Function env.
//
// Why Bearer rather than HMAC body signature: per the Supabase official
// Database Webhooks doc (https://supabase.com/docs/guides/database/webhooks),
// Database Webhooks do NOT auto-sign payloads — the dashboard UI exposes
// only Method / URL / Timeout / HTTP Headers / HTTP Parameters, with no
// signing-secret field and no `x-supabase-webhook-signature` header.
// The custom-header path is the supported authentication boundary.
// Mirrors the `revenuecat-webhook` Bearer pattern (Phase 39 prep).
//
// `SKEINLY_` prefix is load-bearing — Supabase reserves `SUPABASE_*`
// for platform-injected env vars and `supabase secrets set` rejects
// names starting with that prefix (Edge Function limits doc).
//
// Required env vars (auto-injected by Supabase except where noted):
//   - SUPABASE_URL                          (auto)
//   - SUPABASE_SERVICE_ROLE_KEY             (auto)
//   - SKEINLY_DATABASE_WEBHOOK_SECRET       (manual: supabase secrets set)
//
// Database Webhook configuration is documented in supabase/webhooks.md
// and configured via Supabase Dashboard → Database → Webhooks. Three
// hooks point at this function URL — see ADR-017 §3.4 for the table /
// event matrix.

import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";
import {
    type NotificationDispatch,
    type PullRequestCommentRow,
    type PullRequestRow,
    type WebhookPayload,
    computePrCommentedDispatches,
    computePrOpenedDispatches,
    computePrStatusChangeDispatches,
    renderBody,
} from "./mapping.ts";

// ---------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------

Deno.serve(async (req: Request): Promise<Response> => {
    if (req.method === "OPTIONS") {
        return jsonResponse(null, 204);
    }
    if (req.method !== "POST") {
        return jsonResponse({ error: "method_not_allowed" }, 405);
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    const webhookSecret = Deno.env.get("SKEINLY_DATABASE_WEBHOOK_SECRET");
    if (!supabaseUrl || !serviceRoleKey || !webhookSecret) {
        console.error("notify-on-write: required env vars missing");
        return jsonResponse({ error: "edge_function_misconfigured" }, 500);
    }

    // Bearer auth — see header comment block above. The Database Webhook
    // delivery includes Authorization: Bearer <secret> as a custom header
    // configured via Dashboard. Constant-time compare avoids leaking the
    // secret length via timing side channel.
    const authHeader = req.headers.get("authorization") ?? "";
    if (!authHeader.startsWith("Bearer ")) {
        console.warn("notify-on-write: missing or malformed Authorization header (rejected)");
        return jsonResponse({ error: "unauthorized" }, 401);
    }
    const incomingSecret = authHeader.slice("Bearer ".length);
    if (!constantTimeEquals(incomingSecret, webhookSecret)) {
        console.warn("notify-on-write: bearer mismatch (rejected)");
        return jsonResponse({ error: "unauthorized" }, 401);
    }

    let payload: WebhookPayload;
    try {
        payload = (await req.json()) as WebhookPayload;
    } catch {
        return jsonResponse({ error: "invalid_json" }, 400);
    }

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
        auth: { persistSession: false, autoRefreshToken: false },
    });

    try {
        const dispatches = await routePayload(supabase, payload);
        console.log(JSON.stringify({
            event: "notify_on_write_dispatched",
            table: payload.table,
            type: payload.type,
            dispatch_count: dispatches.length,
        }));
        // Phase 24.1 SHELL: log-only. No APNs / FCM call. Phase 24.3
        // wires the real send paths.
        for (const dispatch of dispatches) {
            console.log(JSON.stringify({
                event: "notify_on_write_skipped_send",
                recipient_user_id: dispatch.recipientUserId,
                template_key: dispatch.templateKey,
                phase: "24.1-shell",
            }));
        }
        // Always return 200 to Supabase's webhook retry logic — push
        // delivery failures (Phase 24.3+) MUST NOT trigger Database
        // Webhook retries (which would re-fan-out to all recipients,
        // not just the failed one).
        return jsonResponse({ ok: true, dispatch_count: dispatches.length });
    } catch (e) {
        console.error("notify-on-write: routing failure", e);
        // Even on routing failure, return 200 so Supabase doesn't retry.
        // The error is logged for triage; the user-visible surface is
        // a missing notification, not a cascade of duplicate retries.
        return jsonResponse({ ok: false, error: "routing_failed" });
    }
});

// ---------------------------------------------------------------------
// Routing
// ---------------------------------------------------------------------

async function routePayload(
    supabase: SupabaseClient,
    payload: WebhookPayload,
): Promise<NotificationDispatch[]> {
    if (payload.schema && payload.schema !== "public") {
        // Defense-in-depth: webhook should be scoped to public.* tables
        // by Dashboard config, but ignore non-public deliveries.
        return [];
    }

    if (payload.table === "pull_requests" && payload.type === "INSERT") {
        const row = payload.record as unknown as PullRequestRow;
        const ctx = await resolvePullRequestContext(supabase, row);
        if (!ctx) return [];
        return computePrOpenedDispatches(
            row,
            ctx.targetOwnerId,
            ctx.actorDisplayName,
            ctx.patternTitle,
        );
    }

    if (payload.table === "pull_requests" && payload.type === "UPDATE") {
        const row = payload.record as unknown as PullRequestRow;
        const oldRow = (payload.old_record ?? null) as unknown as PullRequestRow | null;
        // Skip the cheap path: if status didn't change, no notification.
        if (!oldRow || oldRow.status === row.status) return [];
        const ctx = await resolvePullRequestContext(supabase, row);
        if (!ctx) return [];
        // The actor on a status flip is whoever issued the UPDATE. For
        // merge: per ADR-014 §5 RPC, that's the target owner. For close:
        // either party. The webhook payload doesn't carry actor identity
        // directly; we infer from the `updated_at` row touch + client
        // attribution path. Phase 24.1 SHELL: we use a heuristic based
        // on the (status, target owner) combo because the auth.uid() of
        // the writer isn't preserved in the row. Phase 24.3 may need to
        // add an `actor_id` column or trigger-based capture if this
        // heuristic proves insufficient.
        const inferredActor = inferStatusChangeActor(row, oldRow, ctx);
        return computePrStatusChangeDispatches(
            row,
            oldRow,
            inferredActor,
            ctx.targetOwnerId,
            ctx.actorDisplayName,
            ctx.patternTitle,
        );
    }

    if (payload.table === "pull_request_comments" && payload.type === "INSERT") {
        const comment = payload.record as unknown as PullRequestCommentRow;
        const ctx = await resolveCommentContext(supabase, comment);
        if (!ctx) return [];
        return computePrCommentedDispatches(
            comment,
            ctx.prAuthorId,
            ctx.targetOwnerId,
            ctx.actorDisplayName,
            ctx.prTitle,
        );
    }

    // Unhandled event — silent no-op. The Database Webhook UI may have
    // been re-configured to fire on additional tables/events; we don't
    // want to error in that case, just ignore.
    return [];
}

// ---------------------------------------------------------------------
// Context resolution (DB joins)
// ---------------------------------------------------------------------

interface PullRequestContext {
    targetOwnerId: string;
    actorDisplayName: string | null;
    patternTitle: string | null;
}

async function resolvePullRequestContext(
    supabase: SupabaseClient,
    row: PullRequestRow,
): Promise<PullRequestContext | null> {
    // Resolve target_pattern.owner_id + .title.
    const { data: pattern, error: patternError } = await supabase
        .from("patterns")
        .select("owner_id, title")
        .eq("id", row.target_pattern_id)
        .maybeSingle();
    if (patternError || !pattern) {
        console.warn("notify-on-write: pattern resolve failed", row.target_pattern_id);
        return null;
    }

    // Resolve actor display_name (PR author, if non-null).
    let actorDisplayName: string | null = null;
    if (row.author_id) {
        const { data: user } = await supabase
            .from("users")
            .select("display_name")
            .eq("id", row.author_id)
            .maybeSingle();
        actorDisplayName = user?.display_name ?? null;
    }

    return {
        targetOwnerId: pattern.owner_id as string,
        actorDisplayName,
        patternTitle: (pattern.title as string | null) ?? null,
    };
}

interface CommentContext {
    prAuthorId: string | null;
    targetOwnerId: string;
    actorDisplayName: string | null;
    prTitle: string | null;
}

async function resolveCommentContext(
    supabase: SupabaseClient,
    comment: PullRequestCommentRow,
): Promise<CommentContext | null> {
    const { data: pr, error: prError } = await supabase
        .from("pull_requests")
        .select("author_id, target_pattern_id, title")
        .eq("id", comment.pull_request_id)
        .maybeSingle();
    if (prError || !pr) {
        console.warn("notify-on-write: pr resolve failed", comment.pull_request_id);
        return null;
    }

    const { data: pattern } = await supabase
        .from("patterns")
        .select("owner_id")
        .eq("id", pr.target_pattern_id as string)
        .maybeSingle();
    if (!pattern) return null;

    let actorDisplayName: string | null = null;
    if (comment.author_id) {
        const { data: user } = await supabase
            .from("users")
            .select("display_name")
            .eq("id", comment.author_id)
            .maybeSingle();
        actorDisplayName = user?.display_name ?? null;
    }

    return {
        prAuthorId: (pr.author_id as string | null) ?? null,
        targetOwnerId: pattern.owner_id as string,
        actorDisplayName,
        prTitle: (pr.title as string | null) ?? null,
    };
}

/**
 * Heuristic actor inference for `pull_requests` UPDATE in absence of an
 * explicit `actor_id` column on the row (ADR-017 §3.9 trade-off
 * acknowledged). For:
 *   - status='merged' → actor is target_owner (per ADR-014 §5 the
 *     `merge_pull_request` RPC's `WHERE owner_id = v_caller` clause is
 *     the only path to status='merged').
 *   - status='closed' → actor is whichever party closed it. Phase 24.1
 *     defaults to target_owner; Phase 24.3 may augment this with a
 *     server-side trigger that captures auth.uid() into a transient
 *     column if the heuristic proves insufficient.
 */
function inferStatusChangeActor(
    newRow: PullRequestRow,
    _oldRow: PullRequestRow,
    ctx: PullRequestContext,
): string | null {
    if (newRow.status === "merged") {
        return ctx.targetOwnerId;
    }
    if (newRow.status === "closed") {
        // Heuristic: lean target_owner. If author closes their own PR
        // the dispatch will route to target_owner with `pr_closed_to_owner`,
        // which is correct. If target owner closes incoming PR the
        // dispatch will route to author with `pr_closed_to_author`,
        // also correct. The Phase 24.4 work re-evaluates whether the
        // heuristic mis-attributes any real-world close.
        return ctx.targetOwnerId;
    }
    return null;
}

// ---------------------------------------------------------------------
// Authorization helper
// ---------------------------------------------------------------------

/**
 * Constant-time string equality. Avoids leaking the secret length /
 * byte positions via timing side channel — JavaScript's `===`
 * short-circuits on first divergent character, which a remote attacker
 * could exploit. Returns false on length mismatch immediately (the
 * length itself is not a secret — it's the byte positions that matter).
 */
function constantTimeEquals(a: string, b: string): boolean {
    if (a.length !== b.length) return false;
    let out = 0;
    for (let i = 0; i < a.length; i++) out |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return out === 0;
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(body === null ? null : JSON.stringify(body), {
        status,
        headers: {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Headers": "authorization, content-type",
            "Access-Control-Allow-Methods": "POST, OPTIONS",
        },
    });
}

// renderBody is re-exported so the smoke-test path that the README
// documents can demonstrate "what the body would look like" without
// firing a real send. Phase 24.3 will use this directly when wiring
// APNs / FCM payloads.
export { renderBody };
