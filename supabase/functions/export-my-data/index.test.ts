// Pre-Phase-40 A20 Option B — index.ts (handler) test suite.
//
// Coverage: HTTP method handling, CONFIG_MISSING env path, JWT
// extraction + UNAUTHORIZED, per-auth.uid() rate limiting, the
// EXPORT_FAILED wrap on a port throw, the happy-path envelope shape,
// and the IDOR invariant that the port is built/queried only for the
// JWT-derived id (a body-supplied id is ignored).

import { assert, assertEquals } from "@std/assert";
import { fakeBearerForUser } from "./_fakes.ts";
import {
  _resetRateLimitMapForTests,
  extractUserIdFromAuthHeader,
  handleRequest,
} from "./index.ts";
import type {
  AccountRow,
  DataExportPort,
  Row,
  StorageObjectRow,
} from "./query.ts";

const USER_A = "11111111-1111-1111-1111-111111111111";
const USER_B = "22222222-2222-2222-2222-222222222222";

function setEnv(): void {
  Deno.env.set("SUPABASE_URL", "https://test.supabase.invalid");
  Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "service-test-key");
}
function clearEnv(): void {
  Deno.env.delete("SUPABASE_URL");
  Deno.env.delete("SUPABASE_SERVICE_ROLE_KEY");
}

function req(opts?: {
  method?: string;
  authHeader?: string | null;
  body?: unknown;
  userId?: string;
}): Request {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  const auth = opts?.authHeader !== undefined
    ? opts.authHeader
    : fakeBearerForUser(opts?.userId ?? USER_A);
  if (auth !== null) headers.Authorization = auth;
  const method = opts?.method ?? "POST";
  // GET/HEAD Requests cannot carry a body (WHATWG fetch spec).
  const hasBody = method !== "GET" && method !== "HEAD";
  return new Request("https://test.invalid/functions/v1/export-my-data", {
    method,
    headers,
    body: hasBody ? JSON.stringify(opts?.body ?? {}) : undefined,
  });
}

/** A port that records which id it was queried for. */
class RecordingPort implements DataExportPort {
  queriedIds = new Set<string>();
  constructor(private readonly seed: { patterns?: Row[] } = {}) {}
  selectOwned(table: string, _c: string, userId: string): Promise<Row[]> {
    this.queriedIds.add(userId);
    if (table === "patterns") return Promise.resolve(this.seed.patterns ?? []);
    return Promise.resolve([]);
  }
  selectOwnedEither(
    _t: string,
    _a: string,
    _b: string,
    userId: string,
  ): Promise<Row[]> {
    this.queriedIds.add(userId);
    return Promise.resolve([]);
  }
  selectChildrenIn(_t: string, _c: string, _ids: string[]): Promise<Row[]> {
    return Promise.resolve([]);
  }
  getAccount(userId: string): Promise<AccountRow | null> {
    this.queriedIds.add(userId);
    return Promise.resolve(null);
  }
  listAvatars(userId: string): Promise<StorageObjectRow[]> {
    this.queriedIds.add(userId);
    return Promise.resolve([]);
  }
}

class ThrowingPort implements DataExportPort {
  selectOwned(): Promise<Row[]> {
    return Promise.reject(new Error("db exploded"));
  }
  selectOwnedEither(): Promise<Row[]> {
    return Promise.resolve([]);
  }
  selectChildrenIn(): Promise<Row[]> {
    return Promise.resolve([]);
  }
  getAccount(): Promise<AccountRow | null> {
    return Promise.resolve(null);
  }
  listAvatars(): Promise<StorageObjectRow[]> {
    return Promise.resolve([]);
  }
}

/** Fails ONLY in the LAST sequential step (listAvatars), after every
 *  parallel + child query has already resolved. Locks the
 *  all-or-nothing guarantee: a failure that surfaces late must still
 *  produce EXPORT_FAILED, never a partial bundle. */
class LateThrowingPort implements DataExportPort {
  selectOwned(): Promise<Row[]> {
    return Promise.resolve([]);
  }
  selectOwnedEither(): Promise<Row[]> {
    return Promise.resolve([]);
  }
  selectChildrenIn(): Promise<Row[]> {
    return Promise.resolve([]);
  }
  getAccount(): Promise<AccountRow | null> {
    return Promise.resolve(null);
  }
  listAvatars(): Promise<StorageObjectRow[]> {
    return Promise.reject(new Error("storage list failed"));
  }
}

// ---------------------------------------------------------------------

Deno.test("OPTIONS preflight returns 204", async () => {
  _resetRateLimitMapForTests();
  setEnv();
  const res = await handleRequest(req({ method: "OPTIONS" }));
  assertEquals(res.status, 204);
  clearEnv();
});

Deno.test("non-POST returns 405", async () => {
  _resetRateLimitMapForTests();
  setEnv();
  const res = await handleRequest(req({ method: "GET" }));
  assertEquals(res.status, 405);
  clearEnv();
});

Deno.test("missing env → CONFIG_MISSING envelope at HTTP 200", async () => {
  _resetRateLimitMapForTests();
  clearEnv();
  const res = await handleRequest(req());
  assertEquals(res.status, 200);
  const body = await res.json();
  assertEquals(body.ok, false);
  assertEquals(body.code, "CONFIG_MISSING");
});

Deno.test("missing/invalid Bearer → UNAUTHORIZED", async () => {
  _resetRateLimitMapForTests();
  setEnv();
  const noAuth = await handleRequest(req({ authHeader: null }));
  assertEquals((await noAuth.json()).code, "UNAUTHORIZED");
  const garbage = await handleRequest(req({ authHeader: "Bearer not.a.jwt" }));
  assertEquals((await garbage.json()).code, "UNAUTHORIZED");
  clearEnv();
});

Deno.test("happy path returns ok envelope with bundle + summary + total_rows", async () => {
  _resetRateLimitMapForTests();
  setEnv();
  const port = new RecordingPort({ patterns: [{ id: "p1" }, { id: "p2" }] });
  const res = await handleRequest(req(), () => port);
  assertEquals(res.status, 200);
  const body = await res.json();
  assertEquals(body.ok, true);
  assertEquals(body.bundle.user_id, USER_A);
  assertEquals(body.summary.patterns, 2);
  assertEquals(typeof body.total_rows, "number");
  assertEquals(body.total_rows, 2);
});

Deno.test("port is queried ONLY for the JWT-derived id; a body-supplied id is ignored (IDOR invariant)", async () => {
  _resetRateLimitMapForTests();
  setEnv();
  const port = new RecordingPort();
  // Attacker tries to smuggle USER_B via the body while authed as USER_A.
  await handleRequest(
    req({ userId: USER_A, body: { user_id: USER_B, target_id: USER_B } }),
    () => port,
  );
  assert(port.queriedIds.has(USER_A), "expected the caller id to be queried");
  assert(
    !port.queriedIds.has(USER_B),
    "SECURITY: a body-supplied id reached the port",
  );
  assertEquals(port.queriedIds.size, 1);
  clearEnv();
});

Deno.test("port throw is wrapped as EXPORT_FAILED (no partial bundle)", async () => {
  _resetRateLimitMapForTests();
  setEnv();
  const res = await handleRequest(req(), () => new ThrowingPort());
  assertEquals(res.status, 200);
  const body = await res.json();
  assertEquals(body.ok, false);
  assertEquals(body.code, "EXPORT_FAILED");
  assert(typeof body.message === "string" && body.message.length > 0);
  clearEnv();
});

Deno.test("a failure in the LAST step (listAvatars) still yields EXPORT_FAILED, not a partial bundle", async () => {
  _resetRateLimitMapForTests();
  setEnv();
  const res = await handleRequest(req(), () => new LateThrowingPort());
  assertEquals(res.status, 200);
  const body = await res.json();
  assertEquals(body.ok, false);
  assertEquals(body.code, "EXPORT_FAILED");
  assertEquals(body.bundle, undefined, "no partial bundle may escape");
  clearEnv();
});

Deno.test("rate limit: 6th export within the window is RATE_LIMITED, isolated per user", async () => {
  _resetRateLimitMapForTests();
  setEnv();
  const port = new RecordingPort();
  for (let i = 0; i < 5; i++) {
    const ok = await handleRequest(req({ userId: USER_A }), () => port);
    assertEquals((await ok.json()).ok, true);
  }
  const sixth = await handleRequest(req({ userId: USER_A }), () => port);
  assertEquals((await sixth.json()).code, "RATE_LIMITED");
  // A different user is unaffected (per-auth.uid() window).
  const other = await handleRequest(req({ userId: USER_B }), () => port);
  assertEquals((await other.json()).ok, true);
  clearEnv();
});

Deno.test("a non-UUID JWT sub is rejected as UNAUTHORIZED (PostgREST-injection defense)", async () => {
  _resetRateLimitMapForTests();
  setEnv();
  const port = new RecordingPort();
  // A syntactically valid JWT carrying a sub that is NOT a UUID — e.g.
  // an attacker-crafted token whose sub contains a PostgREST operator
  // token. Must never reach the port.
  const res = await handleRequest(
    req({ userId: "evil.or(owner_id.not.is.null)" }),
    () => port,
  );
  const body = await res.json();
  assertEquals(body.ok, false);
  assertEquals(body.code, "UNAUTHORIZED");
  assertEquals(
    port.queriedIds.size,
    0,
    "a non-UUID sub must never reach the port",
  );
  clearEnv();
});

Deno.test("extractUserIdFromAuthHeader decodes sub, rejects malformed", () => {
  assertEquals(extractUserIdFromAuthHeader(fakeBearerForUser(USER_A)), USER_A);
  assertEquals(extractUserIdFromAuthHeader(null), null);
  assertEquals(extractUserIdFromAuthHeader("Token abc"), null);
  assertEquals(extractUserIdFromAuthHeader("Bearer a.b"), null);
});
