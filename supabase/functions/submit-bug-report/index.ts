// Phase 39 W5 (ADR-020) — Bug report proxy Edge Function.
//
// Receives POST /functions/v1/submit-bug-report from beta builds,
// validates input, applies rate limiting, then authenticates as the
// "Skeinly Feedback" GitHub App and creates an Issue on
// b150005/skeinly via the GitHub REST API.
//
// Replaces Phase 39.5's client-side URL prefill flow (ADR-015 §3).
// See ADR-020 for the full design + agent-team deliberation.
//
// Required env vars (manual: `supabase secrets set` after GitHub App
// creation per ADR-020 §6 user-attended steps):
//   - SKEINLY_BUGREPORT_APP_ID
//   - SKEINLY_BUGREPORT_INSTALLATION_ID
//   - SKEINLY_BUGREPORT_PRIVATE_KEY_PEM
//
// Client auth: Supabase publishable key in the `apikey` header. The
// function is published with `verify_jwt = true` in
// supabase/config.toml so Supabase's edge layer admits the request
// after recognising the publishable key. We do not re-verify here.
//
// 2026-05-12 amendment: the original ADR-020 specified
// `Authorization: Bearer <publishable_key>` but the project migrated
// to the new `sb_publishable_*` non-JWT key format (Supabase
// 2025-11-01 transition), and `verify_jwt = true` rejects any
// Authorization header that is not a valid JWT (error
// `UNAUTHORIZED_INVALID_JWT_FORMAT`). The Supabase docs for client-
// invoked Edge Functions place the publishable key in `apikey`
// instead (the legacy/standard channel) — `supabase-js` puts a
// signed-in user's session JWT in `Authorization` and the project's
// publishable key in `apikey`. Our client has no user JWT (the
// proxy acts as the GitHub App on behalf of any user), so we send
// only `apikey`.
//
// The publishable key is trivially obtainable from app bundle
// inspection; abuse prevention is via the rate limit below, not via
// this auth check.
//
// `SKEINLY_` prefix on the secrets is load-bearing — Supabase reserves
// `SUPABASE_*` for platform-injected env vars and `supabase secrets set`
// rejects names starting with that prefix.

import { createIssue, type GithubAppCredentials } from "./github_app.ts";

// ---------------------------------------------------------------------
// Constants — validation limits per ADR-020 §2
// ---------------------------------------------------------------------

const MAX_TITLE_LENGTH = 256;
const MAX_BODY_LENGTH = 65_536;
const MAX_LABELS = 5;
const MAX_LABEL_LENGTH = 50;

/** ADR-020 §2: 5 reports per 1-hour sliding window per request source. */
const RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000;
const RATE_LIMIT_MAX = 5;

// ---------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------

interface SubmitBugReportRequest {
    title: string;
    body: string;
    /** Always populated after `validate()` (defaults to `["feedback"]`).
     *  Caller-supplied `labels` field on the wire is optional; the
     *  validator fills the default before this shape is constructed. */
    labels: readonly string[];
}

type ErrorCode =
    | "RATE_LIMITED"
    | "VALIDATION_FAILED"
    | "GITHUB_AUTH_FAILED"
    | "GITHUB_API_FAILED"
    | "CONFIG_MISSING";

interface SuccessEnvelope {
    ok: true;
    issue_number: number;
    html_url: string;
}

interface FailureEnvelope {
    ok: false;
    code: ErrorCode;
    message: string;
}

// ---------------------------------------------------------------------
// Rate limiter (per-instance in-memory)
// ---------------------------------------------------------------------

interface RateWindow {
    timestamps: number[]; // ms epoch
}

const rateLimitMap = new Map<string, RateWindow>();

/** Test-only — drains the in-memory window so tests start from 0. */
export function _resetRateLimitMapForTests(): void {
    rateLimitMap.clear();
}

/**
 * @returns null if request is allowed; minutes-until-next-allowed if
 *   the source has hit its quota.
 */
function checkRateLimit(sourceHash: string): { retryAfterMinutes: number } | null {
    const now = Date.now();
    const window = rateLimitMap.get(sourceHash);
    const fresh: number[] = (window?.timestamps ?? []).filter(
        (ts) => now - ts < RATE_LIMIT_WINDOW_MS,
    );

    if (fresh.length >= RATE_LIMIT_MAX) {
        // Compute when the oldest entry in the window falls off.
        const oldest = fresh[0];
        const retryMs = RATE_LIMIT_WINDOW_MS - (now - oldest);
        const retryAfterMinutes = Math.max(1, Math.ceil(retryMs / 60_000));
        rateLimitMap.set(sourceHash, { timestamps: fresh });
        return { retryAfterMinutes };
    }
    fresh.push(now);
    rateLimitMap.set(sourceHash, { timestamps: fresh });
    return null;
}

// ---------------------------------------------------------------------
// Source hashing
// ---------------------------------------------------------------------

/**
 * Stable per-request hash for rate-limit keying. Combines the client
 * IP (from `x-real-ip`, Supabase-edge-set) with a salt derived from
 * the Authorization header's tail (anon JWT signature) to defend
 * against a single tester behind shared NAT — though at 10-tester
 * scale this distinction is academic.
 */
async function computeSourceHash(req: Request): Promise<string> {
    const ip = req.headers.get("x-real-ip") ?? req.headers.get("x-forwarded-for") ?? "unknown";
    // 2026-05-12 amendment: read either `apikey` (new Supabase
    // sb_publishable_* path, Authorization-equivalent for non-JWT
    // project keys) or `authorization` (legacy JWT path). The tail
    // is appended as rate-limit salt; with the project's single
    // publishable key embedded in every build, tester separation
    // is effectively IP-based at the ≤10-tester closed-beta scale,
    // but the auth-tail term is retained so a single tester behind
    // shared NAT does not collide if a future build embeds a
    // user-specific JWT instead.
    const authHeader = req.headers.get("apikey") ?? req.headers.get("authorization") ?? "";
    const authTail = authHeader.slice(-12);
    const input = `${ip}|${authTail}`;
    const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
    return Array.from(new Uint8Array(buf))
        .map((b) => b.toString(16).padStart(2, "0"))
        .join("");
}

// ---------------------------------------------------------------------
// Input validation
// ---------------------------------------------------------------------

function validate(
    body: unknown,
): { ok: true; value: SubmitBugReportRequest } | { ok: false; message: string } {
    if (typeof body !== "object" || body === null) {
        return { ok: false, message: "request body must be a JSON object" };
    }
    const obj = body as Record<string, unknown>;

    if (typeof obj.title !== "string") {
        return { ok: false, message: "title must be a string" };
    }
    if (obj.title.length < 1 || obj.title.length > MAX_TITLE_LENGTH) {
        return { ok: false, message: `title length must be 1..${MAX_TITLE_LENGTH}` };
    }
    if (obj.title.includes("\n") || obj.title.includes("\r")) {
        return { ok: false, message: "title must not contain newline characters" };
    }

    if (typeof obj.body !== "string") {
        return { ok: false, message: "body must be a string" };
    }
    if (obj.body.length < 1 || obj.body.length > MAX_BODY_LENGTH) {
        return { ok: false, message: `body length must be 1..${MAX_BODY_LENGTH}` };
    }

    // 2026-05-12 amendment: scope broadened from beta-only to general
    // users. Default label is `feedback` (neutral; triage applies
    // `bug` / `feature-request` / etc. on the Issue tracker side).
    // The previous "beta-bug" default was a Phase 39 closed-beta
    // artifact that did not survive the GA-readiness review.
    let labels: readonly string[] = ["feedback"];
    if ("labels" in obj && obj.labels !== undefined) {
        if (!Array.isArray(obj.labels)) {
            return { ok: false, message: "labels must be an array of strings" };
        }
        if (obj.labels.length > MAX_LABELS) {
            return { ok: false, message: `labels must contain at most ${MAX_LABELS} entries` };
        }
        for (const label of obj.labels) {
            if (typeof label !== "string") {
                return { ok: false, message: "labels must be strings" };
            }
            if (label.length < 1 || label.length > MAX_LABEL_LENGTH) {
                return { ok: false, message: `label length must be 1..${MAX_LABEL_LENGTH}` };
            }
        }
        labels = obj.labels;
    }

    return { ok: true, value: { title: obj.title, body: obj.body, labels } };
}

// ---------------------------------------------------------------------
// Credential lookup
// ---------------------------------------------------------------------

function readCredentials(): GithubAppCredentials | null {
    const appId = Deno.env.get("SKEINLY_BUGREPORT_APP_ID");
    const installationId = Deno.env.get("SKEINLY_BUGREPORT_INSTALLATION_ID");
    const privateKeyPem = Deno.env.get("SKEINLY_BUGREPORT_PRIVATE_KEY_PEM");
    if (!appId || !installationId || !privateKeyPem) {
        return null;
    }
    return { appId, installationId, privateKeyPem };
}

// ---------------------------------------------------------------------
// Handler
// ---------------------------------------------------------------------

/**
 * Process a single submit-bug-report request. Exported for tests so
 * fakes can drive it without spawning a Deno.serve listener.
 */
export async function handleRequest(req: Request): Promise<Response> {
    if (req.method === "OPTIONS") {
        return jsonResponse(null, 204);
    }
    if (req.method !== "POST") {
        return jsonResponse({ error: "method_not_allowed" }, 405);
    }

    const creds = readCredentials();
    if (!creds) {
        return envelope({
            ok: false,
            code: "CONFIG_MISSING",
            message: "edge function missing GitHub App secrets",
        });
    }

    const sourceHash = await computeSourceHash(req);
    const rate = checkRateLimit(sourceHash);
    if (rate) {
        return envelope({
            ok: false,
            code: "RATE_LIMITED",
            message: `try again in ${rate.retryAfterMinutes} minute(s)`,
        });
    }

    let parsed: unknown;
    try {
        parsed = await req.json();
    } catch {
        return envelope({
            ok: false,
            code: "VALIDATION_FAILED",
            message: "request body is not valid JSON",
        });
    }
    const validation = validate(parsed);
    if (!validation.ok) {
        return envelope({
            ok: false,
            code: "VALIDATION_FAILED",
            message: validation.message,
        });
    }

    const result = await createIssue(creds, validation.value);
    switch (result.kind) {
        case "success":
            return envelope({
                ok: true,
                issue_number: result.issueNumber,
                html_url: result.htmlUrl,
            });
        case "auth_failed":
            return envelope({
                ok: false,
                code: "GITHUB_AUTH_FAILED",
                message: result.message,
            });
        case "validation_failed":
            return envelope({
                ok: false,
                code: "VALIDATION_FAILED",
                message: result.message,
            });
        case "api_failed":
            return envelope({
                ok: false,
                code: "GITHUB_API_FAILED",
                message: result.message,
            });
    }
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

function envelope(payload: SuccessEnvelope | FailureEnvelope): Response {
    // Application-level errors always return HTTP 200 with `ok: false`
    // in the body. Non-200 is reserved for platform-level problems
    // (Supabase edge unreachable, function deploy missing) so clients
    // can distinguish "the proxy refused" from "the proxy is broken".
    return jsonResponse(payload, 200);
}

function jsonResponse(payload: unknown, status: number): Response {
    return new Response(payload === null ? null : JSON.stringify(payload), {
        status,
        headers: { "Content-Type": "application/json" },
    });
}

// Only start the listener when run directly (not when imported by
// tests). `import.meta.main` is true exactly when this file is the
// entry point of `deno run`.
if (import.meta.main) {
    Deno.serve(handleRequest);
}
