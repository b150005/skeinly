# ADR-018 — Phase 24.3: APNs + FCM Send Path Implementation

> **Status**: Proposed (2026-05-09)
> **Phase**: 24.3 (sub-slice of Phase 24, follows ADR-017)
> **Supersedes**: none
> **Superseded by**: none
> **Related**: ADR-017 (Phase 24 push notifications — sets the architecture this ADR implements), ADR-014 (PR workflow — primary event source), ADR-013 (collaboration core).
> **Tracking**: Resolves ADR-017 §7 Q1 (APNs sandbox vs production selection) plus four new implementation questions surfaced at the start of Phase 24.3 work. Closes the implementation gap between ADR-017's "log-only `notify-on-write` shell" (Phase 24.1, shipped) and the first end-to-end push delivery (Phase 24.3, this slice).

JA summary: [../../ja/adr/018-phase-24-3-push-send-paths.md](../../ja/adr/018-phase-24-3-push-send-paths.md) (cut alongside this ADR).

## 1. Context

ADR-017 set the architecture for Phase 24 push notifications:

- Two-stack transport (APNs direct for iOS, FCM HTTP v1 for Android).
- Trigger source is Supabase Database Webhooks fanning into the `notify-on-write` Edge Function.
- Recipient computation is pure (`mapping.ts` — Phase 24.1 ships this, exercised by 29 Deno tests).
- Token cleanup on APNs 410 / FCM 404 errors.
- Production-only entitlement on iOS (`aps-environment = production` in `iosApp.entitlements` per Phase 24.2e).

What ADR-017 deliberately left open: the implementation-level choices around how the Edge Function actually constructs the JWTs, how it manages OAuth tokens, how it sequences per-recipient HTTP calls, which exact error codes warrant token deletion vs retry, and how it picks between APNs sandbox and production endpoints. The architectural commitments do not change; this ADR fills in the implementation contract so Phase 24.3 ships with a clear blueprint and Phase 24.4+ can extend it without re-litigating these decisions.

The concrete artifact this ADR designs is the body of `dispatchPush(userId, templateKey, params)` referenced in ADR-017 §3.9 step 3 — currently a `notify_on_write_skipped_send` log line in `supabase/functions/notify-on-write/index.ts` (lines 106–113).

## 2. Decisions (high-level)

1. **APNs JWT signing**: import `djwt` JSR (`@^3`) for ES256. No hand-rolled crypto.
2. **FCM SA OAuth caching**: in-instance memoization with a 5-minute safety margin before token expiry. No LRU; module-scope `let` is sufficient.
3. **Per-recipient send loop**: a simple `for (const dispatch of dispatches) { for (const token of resolveTokens(dispatch.recipientUserId, ...)) { send(token, body) } }`. No batch APIs. `Promise.allSettled` over the inner loop bounds total wall-clock for a multi-token recipient.
4. **Token cleanup mapping**: DELETE on APNs `410 Unregistered`, APNs `400 BadDeviceToken`, APNs `400 DeviceTokenNotForTopic`, FCM `404 UNREGISTERED`, FCM `403 SENDER_ID_MISMATCH`. Everything else log + continue (no DELETE).
5. **APNs environment**: production-only path for Phase 24.3 closed beta. No `device_tokens.environment` column yet. Deferred to Phase 24.4+ if local-debug push iteration becomes necessary.

## 3. Decisions (detailed)

### 3.1 APNs JWT signing — `djwt` JSR

APNs HTTP/2 push provider authentication uses a short-lived JWT in the `:authorization` header, signed with ES256 (ECDSA over P-256 with SHA-256) using the team's `.p8` private key. The JWT carries `{ "alg": "ES256", "kid": <APNS_KEY_ID> }` in the header and `{ "iss": <APNS_TEAM_ID>, "iat": <unix_seconds> }` in the body. Apple specifies a 1-hour validity window (rejects tokens older than ~60 minutes) and rate-limits new tokens to ~once per 20 minutes per team.

Two implementation paths considered:

**(A) Hand-rolled via `crypto.subtle`**: Deno's standard library exposes WebCrypto. The implementation is ~80 lines: parse the `.p8` PEM into a `CryptoKey` with `crypto.subtle.importKey("pkcs8", ...)`, base64url-encode the header + payload, sign via `crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, message)`, convert the WebCrypto IEEE-P1363 r||s output to JWS-compact format (which APNs requires verbatim — no DER encoding), assemble the dot-separated token. Real failure modes seen in the wild: forgetting the IEEE-P1363 → DER conversion (some JWT libraries assume DER), wrong padding on base64url, wrong signature byte order. Each is a silent mis-signing where APNs returns 403 InvalidProviderToken with no useful diagnostic.

**(B) `djwt` JSR (`jsr:@djwt/djwt@^3`)**: third-party Deno-native library. Audited (used by Supabase's own examples), supports ES256 + RS256 + HS256 directly, handles the IEEE-P1363 → JWS-compact conversion correctly (verified by reading source on JSR — `signature.ts:42-58`). Single import line, single function call (`create(header, payload, key)`).

**Decision: (B) djwt JSR**. Rationale:
- Phase 24.3's value is delivered push notifications, not crypto plumbing. Hand-rolled ES256 is non-load-bearing complexity.
- Wrong-signature failure mode (403 InvalidProviderToken with no diagnostic detail) is exactly the class of bug that erodes maintainer trust in the push system. Audited library has a much smaller failure surface.
- Same `djwt` module is reused for FCM SA JWT signing (§3.2). One dependency line covers both stacks.
- `revenuecat-webhook` (Phase 39 prep) already imports from JSR for HMAC-SHA256; adding a second JSR import is consistent with existing precedent.

The dependency line in `notify-on-write/index.ts`:

```typescript
import { create as createJwt, getNumericDate } from "jsr:@djwt/djwt@^3";
```

Same SemVer-style import pin as the existing `jsr:@supabase/supabase-js@2` import. Pinned to `^3` (compatible patches/minors permitted; majors gated behind explicit version bump) which matches the project's general approach to third-party-library version tracking.

### 3.2 FCM SA OAuth token caching

FCM HTTP v1 requires a Bearer access token minted from the Firebase service account's JWT. The flow per request:

1. Build a JWT signed with the SA's private key (RS256), audience `https://oauth2.googleapis.com/token`, scope `https://www.googleapis.com/auth/firebase.messaging`.
2. POST to `https://oauth2.googleapis.com/token` with `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=<jwt>`.
3. Receive `{ "access_token": "...", "expires_in": 3599 }`.
4. POST to `https://fcm.googleapis.com/v1/projects/<project-id>/messages:send` with `Authorization: Bearer <access_token>`.

Steps 1–3 are expensive (~50–150ms per cold call): JWT sign + HTTPS round trip to Google. Step 4 is the actual push (~30–80ms). Caching the access token across multiple invocations within the same Edge Function instance is the natural optimization.

**Caching strategy options**:

**(A) No caching — fetch a fresh token per push**: simplest, but adds 50–150ms per push and burns OAuth fetch budget needlessly. Google's documented OAuth token rate-limiting kicks in at thousands of fetches per minute per SA — far above closed-beta scale, but the latency tax is real.

**(B) In-instance memoization with safety margin**: module-scope `let cachedFcmToken: { value: string; expiresAt: number } | null = null;`. On call: if token is null OR `expiresAt - now() < 5 * 60 * 1000` (5-minute safety margin), refresh; otherwise return cached. Edge Function instance lifetime is bounded by Supabase's autoscaler — typically a few minutes of idle before recycle, longer under load — so the cache lifetime is implicit, no manual eviction needed.

**(C) External cache (KV / Redis)**: cross-instance sharing. Over-engineered for closed-beta scale; added infrastructure surface. Rejected.

**Decision: (B) In-instance memoization with 5-minute safety margin**.

Implementation shape (~15 lines):

```typescript
let cachedFcmAccessToken: { value: string; expiresAt: number } | null = null;
const FCM_TOKEN_REFRESH_MARGIN_MS = 5 * 60 * 1000;

async function getFcmAccessToken(saJson: ServiceAccount): Promise<string> {
    const now = Date.now();
    if (cachedFcmAccessToken && cachedFcmAccessToken.expiresAt - now > FCM_TOKEN_REFRESH_MARGIN_MS) {
        return cachedFcmAccessToken.value;
    }
    const fresh = await fetchFcmAccessToken(saJson);
    cachedFcmAccessToken = {
        value: fresh.accessToken,
        expiresAt: now + (fresh.expiresInSeconds * 1000),
    };
    return fresh.accessToken;
}
```

Properties:
- Cold-start path pays one OAuth fetch (~50–150ms). Subsequent invocations within ~55 minutes reuse the cache.
- 5-minute safety margin avoids the boundary case where a token expires mid-flight (between cache hit and FCM POST). Deno's `fetch` to FCM has its own ~10s timeout; 5 minutes is plenty of slack.
- Cache is per-instance — different Edge Function instances each maintain their own. Acceptable since cold-start frequency at closed-beta scale is low (one or two per hour).
- No mutex needed — Deno V8 is single-threaded per instance, so the cache read/write is naturally atomic.

The 5-minute margin number is documented inline so a future reader sees the rationale without reading this ADR.

### 3.3 Per-recipient send loop

ADR-017 §3.9 step 3 sketches the loop shape: SELECT `device_tokens` for the recipient, render the body with `device_tokens.locale`, send. The implementation question is how to sequence the HTTP calls when a single dispatch fans out to multiple tokens (e.g. user has both an iPhone and an Android tablet, or two iPhones).

**Options**:

**(A) Sequential `for` loop with `await` per send**: simplest. Total wall-clock = sum of per-send latency. For a recipient with 3 tokens at 80ms each, ~240ms.

**(B) `Promise.all` across all tokens per recipient**: parallelizes within a recipient. Total wall-clock = max of per-send latency. ~80ms for the same 3-token case. But `Promise.all` short-circuits on the first rejection — a single APNs 503 throws away the in-flight FCM send results.

**(C) `Promise.allSettled` across all tokens per recipient**: parallelizes AND collects all results regardless of individual failure. Each settled result is then processed for token cleanup independently. ~80ms wall-clock + fault-tolerant aggregation.

**(D) Streaming HTTP/2 multiplexing on a kept-alive APNs connection**: APNs HTTP/2 explicitly supports multiplexing on a single connection (one of the original design goals — replacing the legacy binary protocol's connection-per-token). Deno's `fetch` does NOT expose explicit connection control; the runtime's HTTP client may or may not multiplex internally. Behavior unconfirmed.

**Decision: (C) `Promise.allSettled` per-recipient, sequential across recipients**.

Rationale:
- Within a recipient (1–3 tokens typical), parallelizing is a free win on wall-clock and improves the user-perceived push latency.
- `allSettled` over `all` because a transient APNs 5xx on one token must not block the other tokens' deliveries — they may include the same recipient's other devices, which the user actively uses.
- Across recipients, sequential is fine at closed-beta scale (a PR comment fan-out is at most 2 recipients = author + target owner − comment author, and PR-opened/merged/closed are 1 recipient each). Parallelizing across recipients with `Promise.all`/`allSettled` would require careful Edge Function timeout budgeting and surfaces no real benefit at this scale.
- Future scaling (Phase 24+ if multi-collaborator threads land): the inner loop already isolates per-recipient logic, so parallelizing across recipients is a one-line `for` → `Promise.allSettled([...].map(...))` swap when needed.

Implementation shape:

```typescript
for (const dispatch of dispatches) {
    const tokens = await resolveTokens(supabase, dispatch.recipientUserId);
    if (tokens.length === 0) continue;
    const results = await Promise.allSettled(
        tokens.map((tokenRow) => sendOneNotification(saJson, apnsKey, tokenRow, dispatch))
    );
    for (let i = 0; i < results.length; i++) {
        await processResult(supabase, tokens[i], results[i]);
    }
}
```

`processResult` reads the result + handles token cleanup per §3.4. Sequential `processResult` is intentional — it issues DB DELETEs which we don't want N-way racing.

### 3.4 Error code → token cleanup mapping

ADR-017 §3.10 named two error codes (APNs 410 BadDeviceToken/Unregistered, FCM 404 UNREGISTERED). Phase 24.3 implementation needs the full mapping so a stale token doesn't accumulate forever waiting for a code we don't recognize, and so a transient outage doesn't cause us to wipe valid tokens.

**APNs error codes** (per Apple's [Sending Notification Requests to APNs](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns)):

| HTTP | Reason | Action | Why |
|---|---|---|---|
| 200 | (success) | none | delivered |
| 400 | `BadDeviceToken` | **DELETE** | token format invalid for this team; will never succeed |
| 400 | `DeviceTokenNotForTopic` | **DELETE** | token issued for a different bundle id; cannot use |
| 400 | `BadCollapseId` / `BadExpirationDate` / `BadMessageId` / `BadPriority` / `BadTopic` / `DuplicateHeaders` / `IdleTimeout` / `MissingDeviceToken` / `MissingTopic` / `PayloadEmpty` / `TopicDisallowed` | log + continue | Edge Function bug or misconfiguration; do not penalize the token |
| 403 | `BadCertificate` / `BadCertificateEnvironment` / `ExpiredProviderToken` / `Forbidden` / `InvalidProviderToken` / `MissingProviderToken` | log + alert | provider auth broken; fix `.p8` / refresh JWT |
| 404 | `BadPath` / `MethodNotAllowed` | log + continue | Edge Function URL bug |
| 405 | `MethodNotAllowed` | log + continue | Edge Function HTTP-verb bug |
| 410 | `Unregistered` | **DELETE** | token explicitly retired by the device (user uninstalled, OS reset) |
| 413 | `PayloadTooLarge` | log + continue | Edge Function payload-size bug |
| 429 | `TooManyProviderTokenUpdates` / `TooManyRequests` | log + continue | rate limit; will recover on next push |
| 500 | `InternalServerError` | log + continue | APNs internal; transient |
| 503 | `ServiceUnavailable` / `Shutdown` | log + continue | APNs outage; transient |

**FCM v1 error codes** (per [Firebase HTTP v1 reference](https://firebase.google.com/docs/cloud-messaging/send-message)):

| HTTP | error.status / errorCode | Action | Why |
|---|---|---|---|
| 200 | (success) | none | delivered |
| 400 | `INVALID_ARGUMENT` | log + continue | Edge Function payload bug |
| 401 | `UNAUTHENTICATED` | refresh OAuth + retry once | SA token expired or invalid; refresh path |
| 403 | `SENDER_ID_MISMATCH` | **DELETE** | token issued for a different Firebase project; cannot recover |
| 404 | `UNREGISTERED` | **DELETE** | token retired (uninstall, app data clear, OS reset) |
| 429 | `QUOTA_EXCEEDED` | log + continue | rate limit; recovers on next push |
| 500 | `INTERNAL` | log + continue | FCM internal; transient |
| 503 | `UNAVAILABLE` | log + continue | FCM outage; transient |
| 504 | `DEADLINE_EXCEEDED` | log + continue | network or FCM timeout; transient |

**Implementation contract**:

```typescript
type SendOutcome =
    | { kind: "success" }
    | { kind: "delete_token"; reason: string }
    | { kind: "transient_error"; reason: string }
    | { kind: "config_error"; reason: string };

function classifyApnsResponse(httpStatus: number, reason: string | null): SendOutcome { ... }
function classifyFcmResponse(httpStatus: number, errorCode: string | null): SendOutcome { ... }
```

Each `classify*` function is exhaustive over the table above. Unknown reason strings (e.g. a future APNs reason code Apple introduces) fall through to `transient_error` — fail-safe, never delete a token on an unknown signal.

The `delete_token` arm executes `DELETE FROM device_tokens WHERE token = $1` (token is unique even across user_ids per migration 025's `UNIQUE (user_id, platform, token)` — we delete by token only, the row's user_id is informational). One DB round trip per failed token; bounded.

The `config_error` arm logs at `console.error` level so Supabase's log search surfaces it visibly. Phase 24.3 does NOT wire Sentry — Edge Function logs are searchable via Supabase Dashboard, and adding Sentry SDK is a separate concern (deferred to a hypothetical future "Edge Function observability" slice if real triage volume warrants it).

Token deletion is logged at `console.log` level with the reason for operator visibility:

```typescript
console.log(JSON.stringify({
    event: "device_token_deleted",
    platform: tokenRow.platform,
    reason: outcome.reason,
    user_id_prefix: tokenRow.user_id.substring(0, 8),  // PII-light identifier for triage
}));
```

The user_id prefix is the same shape as `revenuecat-webhook` uses — enough for triage cross-reference, not enough to enumerate users by reading logs.

### 3.5 APNs server URL — production only for Phase 24.3

ADR-017 §7 Q1 named two APNs hosts:
- `https://api.sandbox.push.apple.com` (development entitlement; debug builds with development provisioning profile)
- `https://api.push.apple.com` (production entitlement; TestFlight + App Store + distribution provisioning profile)

The same `.p8` works for both. The Edge Function must pick one per token.

**Phase 24.3 closed-beta context**:
- iOS clients ship via TestFlight (Distribution Provisioning Profile + `aps-environment = production` per Phase 24.2e's `iosApp.entitlements`).
- Local debug builds on the maintainer's Mac DO NOT receive push (the maintainer's pre-Phase-24.3 acceptance: per ADR-017 §3.2 entitlement decision, "TestFlight uses prod APNs; on-device debug push test is unsupported by design").
- Closed-beta testers (5–10) all install via TestFlight or App Store — every token in `device_tokens` was registered by an APNs-production-entitled binary.

Given those constraints, three options:

**(A) Production-only**: Edge Function hardcodes `api.push.apple.com`. Any token that came from a development-entitled binary (which shouldn't exist in `device_tokens` at closed-beta scale, but defense-in-depth) would receive `BadDeviceToken` and get deleted via §3.4 — the deletion is harmless because the token can't ever receive a push from us anyway.

**(B) Try-prod, fall-back-to-sandbox on BadDeviceToken**: defensive against a misconfigured client. Adds one extra round trip per debug-build token. Wastes ~80ms per stale token but never misses a real device.

**(C) `device_tokens.environment` column (matches Phase 39 `subscriptions.environment`)**: client declares its environment at upsert time. Adds a migration, an enum, a CHECK constraint, and a client-side branch. Cleanest but most surface area.

**Decision: (A) Production-only for Phase 24.3**.

Rationale:
- Closed-beta scope: every tester installs via TestFlight. No legitimate token in `device_tokens` is sandbox-registered.
- The only failure mode is a malformed token (e.g. someone sideloads a development-entitlement build and it somehow reaches the upsert path). That falls into the §3.4 `BadDeviceToken → DELETE` arm, which is a harmless no-op outcome (the dev-entitled binary couldn't receive the push anyway).
- The migration cost of (C) is real (column + CHECK + client wiring + tests) and the value is zero at closed-beta scale.
- Phase 24.4+ revisits if local-debug push iteration becomes a maintainer ergonomics need (e.g. Skeinly contributors want to iterate on push UX without TestFlight roundtrips). At that point (C) is the natural pivot.

The hardcoded URL lives as a module constant near the top of the APNs client:

```typescript
const APNS_HOST = "https://api.push.apple.com";
// Phase 24.3 closed beta: production-only path. ADR-018 §3.5 documents
// the rationale + the Phase 24.4+ pivot to a `device_tokens.environment`
// column if local-debug push iteration becomes a need.
```

### 3.6 Test surface

ADR-017 §6 budgeted "~20 Deno tests for happy + token-invalid + 5xx-retry-tolerated paths". Phase 24.3 expands modestly:

- **JWT signing** (3 tests): APNs ES256 produces parseable header/body/signature triple; FCM SA RS256 same; both round-trip through `verify` (defense-in-depth — a sign-then-verify test catches IEEE-P1363 vs DER errors that visual inspection misses).
- **OAuth caching** (4 tests): cold call fetches; warm call within margin returns cached; warm call within 5 minutes of expiry refreshes; refresh on cold-cache after process restart.
- **APNs response classifier** (8 tests): one per success / `BadDeviceToken` / `Unregistered` / `DeviceTokenNotForTopic` / `InvalidProviderToken` / `TooManyRequests` / unknown 4xx reason / unknown 5xx.
- **FCM response classifier** (6 tests): success / `UNREGISTERED` / `SENDER_ID_MISMATCH` / `UNAUTHENTICATED` / `INTERNAL` / unknown.
- **End-to-end dispatch** (5 tests): single recipient single token success; single recipient two tokens with one DELETE-warranting failure (verify other token still delivered, failed token deleted from DB, response 200 to Supabase); fan-out to two recipients; PR-comment dispatch with realistic mapping payload; locale resolution from `device_tokens.locale`.

Total: ~26 new Deno tests. Existing 29 mapping tests + 39 revenuecat-webhook tests = 68 → ~94 total Deno tests post-24.3.

**Test fakes**: HTTP fakes for the APNs and FCM endpoints via `globalThis.fetch` stubbing (same pattern `revenuecat-webhook/mapping.test.ts` doesn't use, but Phase 24.3 introduces). New `notify-on-write/_fakes.ts` (underscore prefix to mark "test-only, not production code") exporting:

```typescript
export interface FetchFake {
    setApnsResponse(token: string, response: { status: number; reason?: string }): void;
    setFcmResponse(token: string, response: { status: number; errorCode?: string }): void;
    setOAuthResponse(response: { status: number; body?: unknown }): void;
    install(): void;   // monkey-patches globalThis.fetch
    restore(): void;   // restores original fetch
}
```

Each test arranges the fakes via `setApnsResponse` calls, runs the production code through the dispatch path, asserts on the resulting DB calls and the HTTP response shape. The production code path itself is unchanged — no test-only branches in `index.ts`.

`Deno.test` lifecycle hooks (`Deno.test.beforeEach` / `afterEach`) ensure fakes are torn down between tests so cross-test pollution can't surface.

### 3.7 No Sentry / observability layer

ADR-017 §3.9 step 3.e originally said "Other failures → log + Sentry breadcrumb." Phase 24.3 deliberately ships without a Sentry SDK in the Edge Function. Reasons:

- Supabase Dashboard log search is searchable by JSON field (the `console.log(JSON.stringify({event: ...}))` pattern is the codebase precedent — see `revenuecat-webhook/index.ts` and `notify-on-write/index.ts` Phase 24.1 shell).
- Closed-beta scale (5–10 testers, ~handful of PR events / day) generates trivial log volume; manual triage via `supabase functions logs notify-on-function` is tractable.
- Adding Sentry's Deno SDK adds a JSR import, a DSN secret, and per-invocation overhead for an observability gain whose value isn't yet measured.
- The structured `console.log` events (`device_token_deleted`, `notify_on_write_dispatched`, etc.) are already greppable; Sentry's value-add is grouping + alerting, neither needed at this scale.

Phase 24+ revisits if real triage volume or alerting needs surface. Until then, structured `console.log` is the observability layer.

## 4. Privacy + security recap

This ADR does not change the privacy/security commitments from ADR-017 §5. Quick recap of what's preserved:

- Edge Function does NOT log token values. Only `user_id_prefix` (first 8 chars) and `platform` for triage.
- Token deletion is server-side only, no client-visible side effect beyond "next push goes to remaining tokens".
- APNs `.p8` (EF-1) and Firebase SA JSON (EF-3) live as Edge Function secrets only.
- `device_tokens` RLS is unchanged; Edge Function uses service-role key (already present in the Phase 24.1 shell).
- No new client-side surface introduced.

## 5. Sub-slice plan

This ADR scopes the implementation contract for Phase 24.3 only. Phase 24.3 ships in one commit (no further sub-slicing) following the existing Phase 24.1 / 24.2e precedent of "the whole slice in one commit + commonTest / Deno test delta".

**Phase 24.3 scope (single commit)**:
- `notify-on-write/index.ts` — replace the `notify_on_write_skipped_send` log lines with real `dispatchPush` invocations per §3.3 + §3.4.
- New `notify-on-write/apns.ts` — JWT signing (§3.1) + APNs HTTP client + response classifier (§3.4).
- New `notify-on-write/fcm.ts` — SA OAuth caching (§3.2) + FCM HTTP v1 client + response classifier (§3.4).
- New `notify-on-write/_fakes.ts` — test-only HTTP fakes (§3.6).
- Test file additions: `apns.test.ts`, `fcm.test.ts`, `dispatch.test.ts` (~26 new tests).
- `notify-on-write/README.md` — replace the Phase 24.1 SHELL section with the Phase 24.3 end-to-end behavior; document smoke test recipes for both APNs and FCM endpoints.

No migration in Phase 24.3 (the `device_tokens.environment` column would land in 24.4+ if needed).

No commonTest delta (data-layer + Edge-Function-only). No i18n keys (push body strings live in Edge Function's mapping.ts already).

User-side actions (NOT autonomously executable):
- After 24.3 deploys, send a smoke test event via the README's curl recipe and verify a push lands on a TestFlight device.
- Confirm a deliberately-malformed token gets deleted from `device_tokens` after one push attempt.

## 6. Open questions deferred to Phase 24.4+

- **Q1 (sandbox vs prod) revisit**: if local-debug push iteration becomes a maintainer ergonomics need, pivot to `device_tokens.environment` per §3.5 option (C). Adds a migration NNN, a CHECK constraint, and a client-side branch in `PushTokenRegistrar.ios.kt` to declare `environment = isDebugBuild ? "sandbox" : "production"`.
- **Q2 (Sentry instrumentation)**: revisit if real triage volume emerges. Phase 24.3 ships with structured `console.log` only.
- **Q3 (per-event throttling / coalescence)**: Phase 24.4+ if testers complain about per-comment notification spam on active PR threads. ADR-017 §4 already names this as a deferral.
- **Q4 (Notification Service Extension for richer iOS content)**: Phase 24.4+ if a real product need surfaces.

## 7. Alternatives considered (cross-cutting)

Beyond the per-decision alternatives listed in §3.1–§3.5:

**A1. Use a higher-level "send push" library that wraps APNs + FCM**: e.g. node-pushnotifications or apn-http2. Rejected because (a) most are Node-targeted, not Deno, and the ones that work in Deno are abandoned; (b) the abstraction would hide the response classification logic that we explicitly need to read for token cleanup; (c) adding a third-party push library doubles supply-chain audit scope vs. just `djwt`.

**A2. Send notifications via a dedicated push-fanout service (e.g. OneSignal, Pusher)**: rejected. Adds vendor dependency for a feature that's a few hundred lines of HTTP code. Vendor lock-in. Out of scope.

**A3. Migrate to APNs HTTP/3 (QUIC)**: APNs supports HTTP/3 in beta. Not yet GA. Not yet supported by Deno's runtime fetch. Defer until both stabilize.

**A4. Pre-compute notification bodies at write time and store them on `pull_requests` / `pull_request_comments` rows**: avoids the per-recipient locale lookup at send time. Rejected — locale is per-recipient, not per-event; pre-computing would require N rows per event (one per locale), or denormalizing the locale lookup into the row, both of which violate the existing `mapping.ts` purity.

## 8. Consequences

**Positive**:
- Phase 24.3 delivers the first end-to-end push for closed-beta testers — single biggest Phase 39 launch unlock for collaboration UX.
- The classifier-based error handling pattern (§3.4) generalizes to Phase 24.4+ events without revision.
- `djwt` JSR import unifies APNs + FCM signing under one library; future ADR amendments don't have to re-litigate the crypto choice.
- In-instance OAuth caching reduces FCM-path latency by ~50–150ms post-warm-up at zero infrastructure cost.

**Negative**:
- Two new Edge Function modules (`apns.ts` + `fcm.ts`) — bounded, ~150 lines each.
- HTTP fakes test infrastructure is new for the Skeinly Deno test suite. Pattern is well-trodden (the same shape used in any Node-style HTTP test suite) but adds a new file type to the test directory.
- Production-only APNs path (§3.5) means a future "test push from local debug build" workflow is structurally unsupported until Phase 24.4+ revisit.
- `device_token_deleted` log lines accumulate visibility surface in Supabase Dashboard logs. At closed-beta scale this is fine; if a deployment misconfigures the entitlement environment all tokens get deleted on first push attempt and the log volume spikes (loud failure mode, intentional).

**Tracking**:
- Phase 24.3 completes ADR-017 §6's sub-slice plan up through 24.3.
- Resolves ADR-017 Q1.
- HARD-GATE for Phase 39 closed-beta tester invites: progresses from "Phase 24.2e ✅, Phase 24.3 ⏳" to "Phase 24.3 ✅" after smoke test passes.

## 9. References

- ADR-017 (Phase 24 push notifications — architectural framing this implementation slots into)
- [Apple — Sending notification requests to APNs](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns)
- [Apple — Establishing a token-based connection to APNs](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns)
- [Firebase — Send messages with the FCM HTTP v1 API](https://firebase.google.com/docs/cloud-messaging/send-message)
- [Firebase — HTTP v1 errors](https://firebase.google.com/docs/cloud-messaging/send-message#admin)
- [djwt JSR](https://jsr.io/@djwt/djwt)
- [Supabase — Edge Function limits](https://supabase.com/docs/guides/functions/limits)
- `supabase/functions/notify-on-write/index.ts` (Phase 24.1 shell — the integration point this slice extends)
- `supabase/functions/notify-on-write/mapping.ts` (Phase 24.1 — recipient + body computation, unchanged in 24.3)
- `supabase/functions/revenuecat-webhook/index.ts` (Phase 39 prep — Bearer-auth + JSON-log precedent)
