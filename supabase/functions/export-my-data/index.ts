// Pre-Phase-40 A20 Option B (docs/en/ops/data-export-sop.md §Scope
// deferrals) — export-my-data Edge Function.
//
// In-app GDPR Article 20 / CCPA "right to know" data export. Replaces
// the operator-driven SOP for users who can complete the in-app flow
// (the SOP remains the documented fallback). Receives an authenticated
// POST (verify_jwt = true), derives the caller id from the verified JWT
// `sub`, composes the user's full data bundle server-side via query.ts,
// and returns it inline for the client to hand to the OS share sheet.
//
// AUTH MODEL — verify_jwt = true. Identity comes EXCLUSIVELY from the
// JWT `sub` (decoded post-platform-validation, same as
// submit-ugc-report). The request body is ignored entirely; there is no
// caller-supplied user id anywhere. An IDOR is structurally impossible:
// query.ts only ever receives `extractUserIdFromAuthHeader`'s output.
//
// SERVICE ROLE — the bundle needs `auth.users` (account metadata) +
// `storage.objects` (avatar listing), which the user-JWT PostgREST
// client cannot read. We therefore use the service-role client and
// scope EVERY query by the JWT-derived id in code (query.ts). This is
// the SOP's exact per-table WHERE, translated to an explicit predicate;
// the predicate IS the access control.
//
// Required env (all auto-injected by Supabase — NO new secret surface,
// unlike submit-bug-report/submit-ugc-report's GitHub App trio):
//   - SUPABASE_URL                 (auto)
//   - SUPABASE_SERVICE_ROLE_KEY    (auto)
//
// Rate limit: 5 exports / 1-hour sliding window per `auth.uid()`. Lower
// than submit-ugc-report's 10/hr — a full-account export is heavier and
// a user has no legitimate need to pull it more than a few times an
// hour. In-memory per-instance `Map` keyed on the JWT `sub` (same
// pattern + caveats as submit-ugc-report; an instance recycle resets
// the window — acceptable for an abuse cap, not a security control).

import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import {
  type AccountRow,
  buildBundle,
  type DataExportBundle,
  type DataExportPort,
  type Row,
  type StorageObjectRow,
} from "./query.ts";

// ---------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------

const RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000;
const RATE_LIMIT_MAX = 5;

// ---------------------------------------------------------------------
// Envelope types (HTTP 200 + ok flag, same contract as
// submit-ugc-report: non-200 is reserved for Supabase-platform breakage
// so the client distinguishes "function refused" from "function down").
// ---------------------------------------------------------------------

type ErrorCode =
  | "UNAUTHORIZED"
  | "RATE_LIMITED"
  | "CONFIG_MISSING"
  | "EXPORT_FAILED";

interface SuccessEnvelope {
  ok: true;
  bundle: DataExportBundle;
  /** Per-table row counts. Keyed by `public.*` table name PLUS the
   *  synthetic `_avatars` key (storage object count) — NOT every key
   *  is a real table. */
  summary: Record<string, number>;
  total_rows: number;
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
  timestamps: number[];
}

const rateLimitMap = new Map<string, RateWindow>();

/** Test-only — drains the in-memory window so tests start from 0. */
export function _resetRateLimitMapForTests(): void {
  rateLimitMap.clear();
}

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
// JWT decode — no signature verification (verify_jwt = true delegates
// that to Supabase's edge layer; tests synthesize unsigned JWTs).
// Verbatim shape from submit-ugc-report; intentionally NOT shared into
// _shared/ — that consolidation is GA-batched with github_app.ts to
// avoid touching a live beta function mid-closed-beta.
// ---------------------------------------------------------------------

export function extractUserIdFromAuthHeader(
  authHeader: string | null,
): string | null {
  if (!authHeader || !authHeader.startsWith("Bearer ")) return null;
  const jwt = authHeader.slice("Bearer ".length).trim();
  const parts = jwt.split(".");
  if (parts.length !== 3) return null;
  let payload: unknown;
  try {
    payload = JSON.parse(base64UrlDecode(parts[1]));
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
  // Decode through bytes + TextDecoder so a JWT payload containing any
  // non-ASCII (a future claim, not `sub` which is always a UUID)
  // round-trips as UTF-8 instead of atob's Latin-1 mojibake.
  const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/**
 * Supabase `auth.users.id` is always a v4 UUID. We validate the
 * JWT-derived id against this shape before it reaches any query. This
 * is the defense-in-depth guarantee that the `.or()` / `.eq()` filter
 * strings in SupabasePort cannot be PostgREST-injected: a value
 * matching this regex contains only `[0-9a-f-]` and cannot carry a
 * PostgREST operator token, dot, or parenthesis. A non-UUID `sub` is
 * not a valid Supabase auth subject ⇒ treated as UNAUTHORIZED.
 */
const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// ---------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------

interface RuntimeConfig {
  supabaseUrl: string;
  serviceRoleKey: string;
}

function readConfig(): RuntimeConfig | null {
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !serviceRoleKey) return null;
  return { supabaseUrl, serviceRoleKey };
}

// ---------------------------------------------------------------------
// Port implementation over the service-role supabase-js client.
//
// Every method scopes by the JWT-derived `userId` argument. There is no
// path here that accepts an id from anywhere except query.ts, which in
// turn only ever passes `extractUserIdFromAuthHeader`'s output.
// ---------------------------------------------------------------------

class SupabasePort implements DataExportPort {
  constructor(private readonly admin: SupabaseClient) {}

  async selectOwned(
    table: string,
    column: string,
    userId: string,
  ): Promise<Row[]> {
    const { data, error } = await this.admin.from(table).select("*").eq(
      column,
      userId,
    );
    if (error) throw new Error(`select ${table}: ${error.message}`);
    return (data ?? []) as Row[];
  }

  async selectOwnedEither(
    table: string,
    colA: string,
    colB: string,
    userId: string,
  ): Promise<Row[]> {
    const { data, error } = await this.admin
      .from(table)
      .select("*")
      .or(`${colA}.eq.${userId},${colB}.eq.${userId}`);
    if (error) throw new Error(`select ${table}: ${error.message}`);
    return (data ?? []) as Row[];
  }

  async selectChildrenIn(
    table: string,
    column: string,
    ids: string[],
  ): Promise<Row[]> {
    if (ids.length === 0) return [];
    // Chunk the `.in(...)` predicate: a user with thousands of
    // patterns/projects would otherwise build a URL past PostgREST's
    // ~8 KB limit and fail opaquely. 200 UUIDs/batch keeps each URL
    // well under the limit; results are flattened in id order.
    const CHUNK = 200;
    const rows: Row[] = [];
    for (let i = 0; i < ids.length; i += CHUNK) {
      const slice = ids.slice(i, i + CHUNK);
      const { data, error } = await this.admin.from(table).select("*").in(
        column,
        slice,
      );
      if (error) throw new Error(`select ${table}: ${error.message}`);
      if (data) rows.push(...(data as Row[]));
    }
    return rows;
  }

  async getAccount(userId: string): Promise<AccountRow | null> {
    const { data, error } = await this.admin.auth.admin.getUserById(userId);
    if (error || !data?.user) return null;
    const u = data.user;
    return {
      id: u.id,
      email: u.email ?? null,
      created_at: u.created_at ?? null,
      last_sign_in_at: u.last_sign_in_at ?? null,
      raw_user_meta_data:
        (u.user_metadata as Record<string, unknown> | undefined) ?? null,
    };
  }

  async listAvatars(userId: string): Promise<StorageObjectRow[]> {
    const { data, error } = await this.admin.storage.from("avatars").list(
      userId,
    );
    if (error || !data) return [];
    return data.map((o) => ({
      name: `${userId}/${o.name}`,
      created_at: o.created_at ?? null,
      updated_at: o.updated_at ?? null,
      last_accessed_at: o.last_accessed_at ?? null,
      metadata: (o.metadata as Record<string, unknown> | null) ?? null,
    }));
  }
}

// ---------------------------------------------------------------------
// Handler
// ---------------------------------------------------------------------

/**
 * Process a single export-my-data request. Exported for tests so the
 * fake port can be injected without spawning Deno.serve. `portFactory`
 * defaults to the real service-role client; tests pass a fake.
 */
export async function handleRequest(
  req: Request,
  portFactory: (cfg: RuntimeConfig) => DataExportPort = defaultPortFactory,
): Promise<Response> {
  // 204 No Content MUST NOT carry a body or a Content-Type header
  // (RFC 9110 §15.3.5) — return a bare Response, not jsonResponse().
  if (req.method === "OPTIONS") return new Response(null, { status: 204 });
  if (req.method !== "POST") {
    return jsonResponse({ error: "method_not_allowed" }, 405);
  }

  const config = readConfig();
  if (!config) {
    return envelope({
      ok: false,
      code: "CONFIG_MISSING",
      message: "edge function missing SUPABASE_URL / SERVICE_ROLE_KEY",
    });
  }

  const userId = extractUserIdFromAuthHeader(req.headers.get("Authorization"));
  if (!userId || !UUID_RE.test(userId)) {
    return envelope({
      ok: false,
      code: "UNAUTHORIZED",
      message: "request missing valid Bearer JWT",
    });
  }

  const rate = checkRateLimit(userId);
  if (rate) {
    return envelope({
      ok: false,
      code: "RATE_LIMITED",
      message: `try again in ${rate.retryAfterMinutes} minute(s)`,
    });
  }

  try {
    const port = portFactory(config);
    const { bundle, summary, totalRows } = await buildBundle(
      port,
      userId,
      new Date().toISOString(),
    );
    return envelope({
      ok: true,
      bundle,
      summary,
      total_rows: totalRows,
    });
  } catch (e) {
    // A data-rights export must be all-or-nothing — a partial bundle
    // would silently under-report. Surface a clear failure; the client
    // lets the user retry. The detailed cause (which can carry internal
    // table / column / constraint names from the supabase-js error) is
    // logged server-side ONLY — the client envelope gets a generic
    // message so internal schema detail never reaches the device /
    // crash reporting.
    console.error("export_my_data failed", e);
    return envelope({
      ok: false,
      code: "EXPORT_FAILED",
      message: "export composition failed — please retry or contact support",
    });
  }
}

function defaultPortFactory(cfg: RuntimeConfig): DataExportPort {
  const admin = createClient(cfg.supabaseUrl, cfg.serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  return new SupabasePort(admin);
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

function envelope(payload: SuccessEnvelope | FailureEnvelope): Response {
  return jsonResponse(payload, 200);
}

function jsonResponse(payload: unknown, status: number): Response {
  // All callers pass a JSON object (OPTIONS uses a bare 204 Response,
  // not this helper) so no null-body branch is needed.
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

if (import.meta.main) {
  Deno.serve((req) => handleRequest(req));
}
