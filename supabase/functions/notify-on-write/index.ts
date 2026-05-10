// Phase 24.3 (ADR-018 §3.3, §3.4): notify-on-write Edge Function with
// real APNs + FCM dispatch.
//
// Phase 24.1 shipped this as log-only shell; Phase 24.3 replaces the
// `notify_on_write_skipped_send` log lines with actual HTTP/2 POSTs
// against APNs (`api.push.apple.com`) and FCM HTTP v1
// (`fcm.googleapis.com/v1/projects/<project>/messages:send`).
//
// Token cleanup: ADR-018 §3.4 classifies APNs / FCM error codes into
// {success | delete_token | transient_error | config_error}. The
// `delete_token` arm runs `DELETE FROM device_tokens WHERE token = $1`
// to drain retired tokens (uninstall, OS reset, etc.).
//
// Receives Supabase Database Webhook deliveries from 3 source tables
// (`pull_requests` INSERT/UPDATE, `pull_request_comments` INSERT) and
// computes per-recipient notification dispatches.
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
    type SupportedLocale,
    type WebhookPayload,
    computePrCommentedDispatches,
    computePrOpenedDispatches,
    computePrStatusChangeDispatches,
    renderBody,
} from "./mapping.ts";
import {
    type ApnsCredentials,
    type SendOutcome,
    sendApns,
} from "./apns.ts";
import { type ServiceAccount, sendFcm } from "./fcm.ts";

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
        const apnsCreds = loadApnsCredentialsOrNull();
        const fcmSa = loadFcmServiceAccountOrNull();
        const sendStats = await dispatchAll(supabase, dispatches, apnsCreds, fcmSa);
        // Always return 200 to Supabase's webhook retry logic — push
        // delivery failures MUST NOT trigger Database Webhook retries
        // (which would re-fan-out to all recipients, not just the
        // failed one). Stats are returned in the body for triage.
        return jsonResponse({
            ok: true,
            dispatch_count: dispatches.length,
            send_stats: sendStats,
        });
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

    if (payload.table === "suggestions" && payload.type === "INSERT") {
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

    if (payload.table === "suggestions" && payload.type === "UPDATE") {
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

    if (payload.table === "suggestion_comments" && payload.type === "INSERT") {
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
        .from("suggestions")
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
    if (newRow.status === "applied") {
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
// firing a real send. Phase 24.3 also consumes this directly inside
// `dispatchAll` below.
export { renderBody };

// ---------------------------------------------------------------------
// Dispatch (Phase 24.3 — ADR-018 §3.3, §3.4)
// ---------------------------------------------------------------------

interface DeviceTokenRow {
    id: string;
    user_id: string;
    platform: "ios" | "android";
    token: string;
    locale: string;
}

export interface SendStats {
    success: number;
    delete_token: number;
    transient_error: number;
    config_error: number;
    skipped_no_creds: number;
}

/**
 * Phase 24.3 dispatch: for each dispatch's recipient, resolve their
 * device tokens, fan out per token via Promise.allSettled, then
 * sequentially process each result for token cleanup. ADR-018 §3.3.
 */
export async function dispatchAll(
    supabase: SupabaseClient,
    dispatches: NotificationDispatch[],
    apnsCreds: ApnsCredentials | null,
    fcmSa: ServiceAccount | null,
): Promise<SendStats> {
    const stats: SendStats = {
        success: 0,
        delete_token: 0,
        transient_error: 0,
        config_error: 0,
        skipped_no_creds: 0,
    };
    for (const dispatch of dispatches) {
        const tokens = await resolveTokens(supabase, dispatch.recipientUserId);
        if (tokens.length === 0) {
            console.log(JSON.stringify({
                event: "notify_on_write_no_tokens",
                recipient_user_id_prefix: dispatch.recipientUserId.substring(0, 8),
                template_key: dispatch.templateKey,
            }));
            continue;
        }
        const results = await Promise.allSettled(
            tokens.map((tokenRow) => sendOne(tokenRow, dispatch, apnsCreds, fcmSa)),
        );
        for (let i = 0; i < results.length; i++) {
            const tokenRow = tokens[i];
            const result = results[i];
            if (result.status === "fulfilled") {
                await processOutcome(supabase, tokenRow, result.value, stats);
            } else {
                // `Promise.allSettled` swallows the error inside the
                // outer await, but `sendOne` itself wraps fetch errors
                // into transient_error outcomes — a true rejection is
                // unexpected. Surface as transient + log.
                console.error(JSON.stringify({
                    event: "notify_on_write_unexpected_rejection",
                    platform: tokenRow.platform,
                    user_id_prefix: tokenRow.user_id.substring(0, 8),
                    error: stringifyError(result.reason),
                }));
                stats.transient_error += 1;
            }
        }
    }
    return stats;
}

async function resolveTokens(
    supabase: SupabaseClient,
    recipientUserId: string,
): Promise<DeviceTokenRow[]> {
    const { data, error } = await supabase
        .from("device_tokens")
        .select("id, user_id, platform, token, locale")
        .eq("user_id", recipientUserId);
    if (error) {
        console.error("notify-on-write: device_tokens select failed", error.message);
        return [];
    }
    return (data ?? []) as DeviceTokenRow[];
}

async function sendOne(
    tokenRow: DeviceTokenRow,
    dispatch: NotificationDispatch,
    apnsCreds: ApnsCredentials | null,
    fcmSa: ServiceAccount | null,
): Promise<SendOutcome> {
    const locale = normalizeLocale(tokenRow.locale);
    const body = renderBody(locale, dispatch.templateKey, dispatch.params);

    if (tokenRow.platform === "ios") {
        if (!apnsCreds) {
            return { kind: "config_error", reason: "apns_credentials_missing" };
        }
        return await sendApns(apnsCreds, {
            deviceToken: tokenRow.token,
            body,
            templateKey: dispatch.templateKey,
            route: dispatch.route,
        });
    }
    if (tokenRow.platform === "android") {
        if (!fcmSa) {
            return { kind: "config_error", reason: "fcm_service_account_missing" };
        }
        return await sendFcm(fcmSa, {
            deviceToken: tokenRow.token,
            body,
            templateKey: dispatch.templateKey,
            route: dispatch.route,
        });
    }
    return {
        kind: "config_error",
        reason: `unknown_platform: ${String(tokenRow.platform)}`,
    };
}

async function processOutcome(
    supabase: SupabaseClient,
    tokenRow: DeviceTokenRow,
    outcome: SendOutcome,
    stats: SendStats,
): Promise<void> {
    if (outcome.kind === "success") {
        stats.success += 1;
        return;
    }
    if (outcome.kind === "delete_token") {
        // ADR-018 §3.4 — DELETE WHERE token = $1; the row's user_id is
        // informational. Token alone is the canonical identifier even
        // across user_ids (per migration 025 unique constraint
        // semantics + per-device token issuance).
        const { error } = await supabase
            .from("device_tokens")
            .delete()
            .eq("token", tokenRow.token);
        if (error) {
            console.error(JSON.stringify({
                event: "device_token_delete_failed",
                platform: tokenRow.platform,
                user_id_prefix: tokenRow.user_id.substring(0, 8),
                reason: outcome.reason,
                error: error.message,
            }));
            stats.transient_error += 1;
            return;
        }
        console.log(JSON.stringify({
            event: "device_token_deleted",
            platform: tokenRow.platform,
            user_id_prefix: tokenRow.user_id.substring(0, 8),
            reason: outcome.reason,
        }));
        stats.delete_token += 1;
        return;
    }
    if (outcome.kind === "config_error") {
        if (outcome.reason === "apns_credentials_missing"
            || outcome.reason === "fcm_service_account_missing") {
            stats.skipped_no_creds += 1;
        } else {
            stats.config_error += 1;
        }
        console.error(JSON.stringify({
            event: "notify_on_write_config_error",
            platform: tokenRow.platform,
            user_id_prefix: tokenRow.user_id.substring(0, 8),
            reason: outcome.reason,
        }));
        return;
    }
    // transient_error
    stats.transient_error += 1;
    console.log(JSON.stringify({
        event: "notify_on_write_transient_error",
        platform: tokenRow.platform,
        user_id_prefix: tokenRow.user_id.substring(0, 8),
        reason: outcome.reason,
    }));
}

function loadApnsCredentialsOrNull(): ApnsCredentials | null {
    const keyP8 = Deno.env.get("APPLE_APNS_KEY_P8");
    const keyId = Deno.env.get("APPLE_APNS_KEY_ID");
    const teamId = Deno.env.get("APPLE_TEAM_ID");
    if (!keyP8 || !keyId || !teamId) {
        return null;
    }
    return { keyP8Pem: keyP8, keyId, teamId };
}

function loadFcmServiceAccountOrNull(): ServiceAccount | null {
    const raw = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON");
    if (!raw) return null;
    try {
        const parsed = JSON.parse(raw) as ServiceAccount;
        if (!parsed.client_email || !parsed.private_key || !parsed.project_id) {
            console.error("notify-on-write: FIREBASE_SERVICE_ACCOUNT_JSON malformed");
            return null;
        }
        return parsed;
    } catch (e) {
        console.error("notify-on-write: FIREBASE_SERVICE_ACCOUNT_JSON parse failed", stringifyError(e));
        return null;
    }
}

/** Defensive locale normalization. The `device_tokens.locale` CHECK
 * constraint pins the value to "en-US" or "ja-JP", but defense in
 * depth matters when reading user-supplied data. Unknown locales fall
 * back to en-US (the same fallback `renderBody` does internally —
 * mirrored here so the type contract on `SupportedLocale` is honored
 * before the call). */
function normalizeLocale(raw: string | null | undefined): SupportedLocale {
    if (raw === "en-US" || raw === "ja-JP") return raw;
    return "en-US";
}

function stringifyError(e: unknown): string {
    if (e instanceof Error) return e.message;
    return String(e);
}
