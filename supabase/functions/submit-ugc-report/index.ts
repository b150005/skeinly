// Pre-alpha A1/A5 (ADR-021) — submit-ugc-report Edge Function.
//
// Receives POST /functions/v1/submit-ugc-report from an authenticated
// caller (verify_jwt = true), validates input, applies a per-user rate
// limit, INSERTs into public.ugc_reports as the authenticated user
// (RLS-enforced reporter_id = auth.uid()), then mirrors a GitHub Issue
// to b150005/skeinly with label `ugc-report` via the Skeinly Feedback
// GitHub App (re-using the credentials registered for submit-bug-report
// per ADR-020 §D2).
//
// Distinct from submit-bug-report because (per ADR-021 §A1):
//   1. verify_jwt = true here — reporter identity required so operator
//      can investigate patterns of false reporting. submit-bug-report
//      runs with verify_jwt = false (anonymous bug reports allowed).
//      Supabase's function-level verify_jwt is binary; can't toggle
//      per-payload. Two distinct functions is the right boundary.
//   2. Issues are tagged `ugc-report` vs submit-bug-report's `feedback`
//      to keep operator triage queries clean.
//
// Required env (all auto-injected by Supabase except SKEINLY_BUGREPORT_*):
//   - SUPABASE_URL                          (auto)
//   - SUPABASE_ANON_KEY                     (auto)
//   - SUPABASE_SERVICE_ROLE_KEY             (auto)
//   - SKEINLY_BUGREPORT_APP_ID              (manual: supabase secrets set; reused EF-7)
//   - SKEINLY_BUGREPORT_INSTALLATION_ID     (manual: reused EF-7)
//   - SKEINLY_BUGREPORT_PRIVATE_KEY_PEM     (manual: reused EF-7)
//
// Rate limit: 10 reports / 1-hour sliding window per `auth.uid()`.
// In-memory `Map` keyed on the JWT `sub` claim. Reasoning: per-user is
// the right granularity for false-report abuse prevention — IP-keyed
// rate limit would penalize NAT-shared testers, and we already require
// auth so the user_id is a stable de-anonymized handle.

import { createClient } from "@supabase/supabase-js";
import { createIssue, type GithubAppCredentials } from "./github_app.ts";
import {
  buildIssueBody,
  buildIssueTitle,
  type SubmitUgcReportInput,
  validateInput,
} from "./mapping.ts";

// ---------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------

/** ADR-021 §D3: 10 reports per 1-hour sliding window per authenticated
 *  user. Looser than submit-bug-report's 5/hr because legitimate UGC
 *  reports during a moderation event can naturally cluster (a tester
 *  who finds one bad pattern often finds three). */
const RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000;
const RATE_LIMIT_MAX = 10;

/** GitHub Issue label applied to every report. Operator-side triage
 *  filter (`is:open label:ugc-report`) hinges on this constant. */
const ISSUE_LABEL = "ugc-report";

// ---------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------

type ErrorCode =
  | "UNAUTHORIZED"
  | "RATE_LIMITED"
  | "VALIDATION_FAILED"
  | "DB_INSERT_FAILED"
  | "CONFIG_MISSING";

interface SuccessEnvelope {
  ok: true;
  report_id: string;
  /** Null when Issue POST failed — DB row is canonical and the
   *  operator monitors `WHERE state = 'open' AND github_issue_url
   *  IS NULL` to catch this case. */
  github_issue_url: string | null;
}

interface FailureEnvelope {
  ok: false;
  code: ErrorCode;
  message: string;
}

// ---------------------------------------------------------------------
// Rate limiter (per-instance in-memory, keyed on auth.uid())
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
 *   the user has hit their quota.
 */
function checkRateLimit(userId: string): { retryAfterMinutes: number } | null {
  const now = Date.now();
  const window = rateLimitMap.get(userId);
  const fresh: number[] = (window?.timestamps ?? []).filter(
    (ts) => now - ts < RATE_LIMIT_WINDOW_MS,
  );

  if (fresh.length >= RATE_LIMIT_MAX) {
    const oldest = fresh[0];
    const retryMs = RATE_LIMIT_WINDOW_MS - (now - oldest);
    const retryAfterMinutes = Math.max(1, Math.ceil(retryMs / 60_000));
    rateLimitMap.set(userId, { timestamps: fresh });
    return { retryAfterMinutes };
  }
  fresh.push(now);
  rateLimitMap.set(userId, { timestamps: fresh });
  return null;
}

// ---------------------------------------------------------------------
// JWT decode (no signature verification — Supabase platform validated
// before the request reached the function, per verify_jwt = true).
// ---------------------------------------------------------------------

/**
 * Decode the user_id (JWT `sub` claim) from the Authorization header.
 * Returns null when:
 *   - Authorization header missing or not Bearer-prefixed
 *   - JWT does not have 3 base64url segments
 *   - payload is not valid JSON, or `sub` is not a non-empty string
 *
 * We deliberately do NOT verify the signature here — the function's
 * verify_jwt = true config delegates that to Supabase's edge layer.
 * Test paths can synthesize fake JWTs with any signature; production
 * paths can only reach this code with a Supabase-validated JWT.
 */
export function extractUserIdFromAuthHeader(
  authHeader: string | null,
): string | null {
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return null;
  }
  const jwt = authHeader.slice("Bearer ".length).trim();
  const parts = jwt.split(".");
  if (parts.length !== 3) return null;

  let payload: unknown;
  try {
    const json = base64UrlDecode(parts[1]);
    payload = JSON.parse(json);
  } catch {
    return null;
  }
  if (typeof payload !== "object" || payload === null) return null;
  const sub = (payload as Record<string, unknown>).sub;
  if (typeof sub !== "string" || sub.length === 0) return null;
  return sub;
}

function base64UrlDecode(s: string): string {
  const pad = (4 - (s.length % 4)) % 4;
  const padded = s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat(pad);
  return atob(padded);
}

// ---------------------------------------------------------------------
// Config lookup
// ---------------------------------------------------------------------

interface RuntimeConfig {
  supabaseUrl: string;
  anonKey: string;
  serviceRoleKey: string;
  github: GithubAppCredentials;
}

function readConfig(): RuntimeConfig | null {
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const appId = Deno.env.get("SKEINLY_BUGREPORT_APP_ID");
  const installationId = Deno.env.get("SKEINLY_BUGREPORT_INSTALLATION_ID");
  const privateKeyPem = Deno.env.get("SKEINLY_BUGREPORT_PRIVATE_KEY_PEM");
  if (
    !supabaseUrl ||
    !anonKey ||
    !serviceRoleKey ||
    !appId ||
    !installationId ||
    !privateKeyPem
  ) {
    return null;
  }
  return {
    supabaseUrl,
    anonKey,
    serviceRoleKey,
    github: { appId, installationId, privateKeyPem },
  };
}

// ---------------------------------------------------------------------
// Handler
// ---------------------------------------------------------------------

/**
 * Process a single submit-ugc-report request. Exported for tests so
 * fakes can drive it without spawning a Deno.serve listener.
 */
export async function handleRequest(req: Request): Promise<Response> {
  if (req.method === "OPTIONS") {
    return jsonResponse(null, 204);
  }
  if (req.method !== "POST") {
    return jsonResponse({ error: "method_not_allowed" }, 405);
  }

  const config = readConfig();
  if (!config) {
    return envelope({
      ok: false,
      code: "CONFIG_MISSING",
      message: "edge function missing one or more required secrets",
    });
  }

  const authHeader = req.headers.get("Authorization");
  const reporterId = extractUserIdFromAuthHeader(authHeader);
  if (!reporterId) {
    return envelope({
      ok: false,
      code: "UNAUTHORIZED",
      message: "request missing valid Bearer JWT",
    });
  }

  const rate = checkRateLimit(reporterId);
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
  const validation = validateInput(parsed);
  if (!validation.ok) {
    return envelope({
      ok: false,
      code: "VALIDATION_FAILED",
      message: validation.message,
    });
  }
  const input: SubmitUgcReportInput = validation.value;

  // INSERT via user-JWT client so RLS policy "Users can file own
  // reports" enforces reporter_id = auth.uid() natively (defense in
  // depth vs the JWT-decoded reporter_id we also write explicitly).
  // authHeader is non-null by virtue of reporterId being non-null.
  const userClient = createClient(config.supabaseUrl, config.anonKey, {
    global: { headers: { Authorization: authHeader as string } },
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const insertResult = await userClient
    .from("ugc_reports")
    .insert({
      reporter_id: reporterId,
      target_type: input.target_type,
      target_id: input.target_id,
      reason: input.reason,
      reason_category: input.reason_category,
    })
    .select("id")
    .single();

  if (insertResult.error || !insertResult.data) {
    return envelope({
      ok: false,
      code: "DB_INSERT_FAILED",
      message: insertResult.error?.message ??
        "ugc_reports insert returned no row",
    });
  }
  const reportId = insertResult.data.id as string;

  // Best-effort GitHub Issue mirror. Failure leaves the DB row in
  // place with github_issue_url = NULL — operator monitors that
  // condition in their triage SOP.
  let htmlUrl: string | null = null;
  const issueResult = await createIssue(config.github, {
    title: buildIssueTitle(input),
    body: buildIssueBody({ reporterId, reportId, input }),
    labels: [ISSUE_LABEL],
  });

  if (issueResult.kind === "success") {
    htmlUrl = issueResult.htmlUrl;
    const adminClient = createClient(
      config.supabaseUrl,
      config.serviceRoleKey,
      {
        auth: { persistSession: false, autoRefreshToken: false },
      },
    );
    // Ignore UPDATE error — DB row is canonical. The htmlUrl back-
    // link is operator convenience; missing it does not invalidate
    // the report. Worst case the operator does a manual JOIN by id.
    await adminClient
      .from("ugc_reports")
      .update({ github_issue_url: htmlUrl })
      .eq("id", reportId);
  } else {
    console.error(
      `submit_ugc_report ${reportId}: GitHub Issue creation failed kind=${issueResult.kind}`,
      issueResult,
    );
  }

  return envelope({
    ok: true,
    report_id: reportId,
    github_issue_url: htmlUrl,
  });
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

function envelope(payload: SuccessEnvelope | FailureEnvelope): Response {
  // Application-level errors return HTTP 200 with `ok: false` in the
  // body (same shape as submit-bug-report per ADR-020 §2). Non-200 is
  // reserved for platform-level problems so clients can distinguish
  // "the proxy refused" from "the proxy is broken".
  return jsonResponse(payload, 200);
}

function jsonResponse(payload: unknown, status: number): Response {
  return new Response(payload === null ? null : JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

if (import.meta.main) {
  Deno.serve(handleRequest);
}
