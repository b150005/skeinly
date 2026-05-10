# notify-on-write Edge Function

Phase 24.1 (per [ADR-017](../../../docs/en/adr/017-phase-24-push-notifications.md)) shipped this as a log-only shell. Phase 24.3 (per [ADR-018](../../../docs/en/adr/018-phase-24-3-push-send-paths.md)) replaced the `notify_on_write_skipped_send` log lines with real APNs HTTP/2 + FCM HTTP v1 dispatch + token cleanup on the standard error codes.

## Architecture

```
PR opened / commented / merged / closed
      │
      │ INSERT or UPDATE on
      │   public.suggestions / public.suggestion_comments
      ▼
Supabase Database Webhook
  (Type: Supabase Edge Functions; function: notify-on-write)
  (Auto-populated Authorization header overwritten with custom Bearer secret)
      │
      │ POST <project-url>/functions/v1/notify-on-write
      │ Header: Authorization: Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET>
      ▼
notify-on-write Edge Function (this)
      │ 1. Verify Bearer secret (constant-time compare against env)
      │ 2. Parse Database Webhook payload { type, table, record, old_record? }
      │ 3. Route by (table, type) → mapping.ts pure helpers compute
      │    NotificationDispatch[] per recipient
      │ 4. Resolve display names + pattern title via service-role JOINs
      │ 5. dispatchAll(supabase, dispatches, apnsCreds, fcmSa) — Phase 24.3:
      │    - SELECT device_tokens by recipient_user_id
      │    - Promise.allSettled per token: sendApns / sendFcm
      │    - On delete_token outcome: DELETE FROM device_tokens
      │    - Stats { success, delete_token, transient_error, ... } returned
      │      in the response body for triage.
      ▼
device_tokens (per-user) ← Phase 24.2 client registers here via PushTokenRegistrar
      │
      ▼
APNs (api.push.apple.com) / FCM v1 (fcm.googleapis.com)
```

> **Why Bearer rather than HMAC body signature**: per the [Supabase Database Webhooks doc](https://supabase.com/docs/guides/database/webhooks), Database Webhooks do NOT auto-sign payloads — the dashboard UI exposes only Method / URL or Edge Function / Timeout / HTTP Headers / HTTP Parameters, with no signing-secret field and no `x-supabase-webhook-signature` header. The Authorization header path is the supported authentication boundary. Mirrors the `revenuecat-webhook` Bearer pattern.

> **Why `Supabase Edge Functions` type rather than `HTTP Request` type**: both types map to the same `supabase_functions.http_request()` Postgres trigger function (the trigger passes user-supplied HTTP Headers through to `net.http_post` unchanged), so the Edge Function code path is identical. The Edge Functions type is chosen for maintainability — function selection by dropdown eliminates URL typo risk and survives function rename, and the Dashboard webhook detail row clearly shows "→ Edge Function: notify-on-write" instead of an opaque URL string. The Dashboard pre-populates the `Authorization` header with `Bearer <project anon key>` when this type is selected; that value is overwritten with our project-internal `SKEINLY_DATABASE_WEBHOOK_SECRET` because the anon key is public (embedded in the Skeinly mobile app) and would let any app user POST hand-crafted payloads directly to this Edge Function URL. See [supabase/webhooks.md](../../webhooks.md) "Why we override the auto-populated Authorization" for the full rationale.

## Required secrets (release-secrets.md)

- `SUPABASE_URL` — auto-injected
- `SUPABASE_SERVICE_ROLE_KEY` — auto-injected
- `SKEINLY_DATABASE_WEBHOOK_SECRET` — manual: `supabase secrets set` (release-secrets EF-6, Phase 24.1). The `SKEINLY_` prefix is load-bearing: Supabase reserves `SUPABASE_*` for platform-injected env vars and `supabase secrets set` rejects any name starting with that prefix.
- `APPLE_APNS_KEY_P8` (EF-1) — APNs `.p8` PEM body. Required for iOS dispatch (Phase 24.3+).
- `APPLE_APNS_KEY_ID` (EF-2) — 10-char APNs Key ID.
- `APPLE_TEAM_ID` — 10-char Apple Developer Team ID.
- `FIREBASE_SERVICE_ACCOUNT_JSON` (EF-3) — Firebase Admin SA JSON. Required for Android dispatch (Phase 24.3+).

If APNs creds are missing, iOS dispatches gracefully degrade to `config_error` (skipped, logged, no DELETE). Same for missing FCM SA → Android dispatches skip. Half-configured deployments don't crash; the Edge Function's response body's `send_stats.skipped_no_creds` counter surfaces the omission.

## Deployment

```bash
# CRITICAL: pull latest main BEFORE deploying so the deployed function
# matches the source that the project's docs / tests / commits describe.
git checkout main && git pull origin main

# Then deploy.
supabase functions deploy notify-on-write
```

> **JWT verification disabled (load-bearing)**: like `revenuecat-webhook`, this function is invoked by Supabase's Database Webhook system which does NOT carry a Supabase JWT. The deploy picks up `[functions.notify-on-write] verify_jwt = false` from `supabase/config.toml` automatically. Auth is enforced inside the function via constant-time Bearer-token compare against `SKEINLY_DATABASE_WEBHOOK_SECRET`.

> **Stale-source pitfall (2026-05-09 incident)**: `supabase functions deploy` packages whatever source is on the local filesystem at the moment of invocation. If the local repo is checked out to an older commit, the deployed function will mismatch the source the rest of the project (docs, ADR-017, tests) describes — and any subsequent secret-name change / auth-shape change in main will silently fail at runtime (typically `HTTP 401 unauthorized` because the deployed function is checking against the old auth contract). The fix on incident was to redeploy via `mcp__supabase__deploy_edge_function` MCP tool with the worktree's current files; the structural prevention is the `git pull` step above. After deploy, verify by comparing the function's `ezbr_sha256` (visible via `mcp__supabase__list_edge_functions`) — a redeploy of the same source produces the same hash, a redeploy of a different source produces a different hash.

## Database Webhook configuration (post-deploy)

Configure the 3 webhooks via Supabase Dashboard → Database → Webhooks. Full step-by-step in [supabase/webhooks.md](../../webhooks.md).

## Local unit tests

`mapping.ts` is pure (no fetch, no DB, no env vars). The Phase 24.3 send-path modules (`apns.ts` / `fcm.ts`) are exercised via `globalThis.fetch` monkey-patching from `_fakes.ts`. The whole suite runs offline:

```bash
deno test --allow-net --allow-env supabase/functions/notify-on-write/
```

Coverage (62 tests total):
- 29 `mapping.test.ts` — template parity, locale fallbacks, recipient matrices (Phase 24.1 baseline, unchanged in 24.3).
- 15 `apns.test.ts` — JWT signing + caching + ES256 round-trip + classifier matrix (200/410/400/403/429/503/unknown) + end-to-end sendApns under fake fetch.
- 13 `fcm.test.ts` — OAuth caching + classifier matrix (UNREGISTERED/SENDER_ID_MISMATCH/UNAUTHENTICATED retry sentinel/etc.) + end-to-end sendFcm.
- 5 `dispatch.test.ts` — integration: dispatchAll with fake Supabase + fake fetch covering single-success, mixed success/delete, two-recipient fan-out (verifies OAuth fetched once across pushes), missing-creds skip, ghost-recipient (zero tokens) skip.

`--allow-net` is needed because Deno requires it to import the JSR `@zaubrik/djwt@^3` module on first run (cached after that). `--allow-env` is needed because `index.ts` reads `Deno.env.get(...)` at startup-flow code paths exercised in dispatch tests.

## Phase 24.3 send-path contract (per ADR-018)

This slice:
- Replaces the Phase 24.1 `notify_on_write_skipped_send` log lines with real APNs HTTP/2 + FCM HTTP v1 POSTs.
- Mints APNs ES256 JWTs in-instance via `djwt` JSR, cached for 50 min with a 5-min refresh margin.
- Mints FCM SA RS256 JWT → exchanges for OAuth access token → caches in-instance with the same 5-min margin.
- Per-recipient `Promise.allSettled` over device_tokens; sequential across recipients.
- DELETEs device_tokens rows on APNs `410 Unregistered` / `400 BadDeviceToken` / `400 DeviceTokenNotForTopic` / FCM `404 UNREGISTERED` / `403 SENDER_ID_MISMATCH`. Unknown reasons fall through to `transient_error` (fail-safe — never delete on unknown signal).
- Production APNs only (`api.push.apple.com`); ADR-018 §3.5 documents the rationale + the Phase 24.4+ pivot to `device_tokens.environment` if local-debug push iteration becomes a need.

Out of scope (deferred to Phase 24.4+):
- PR-opened / PR-merged / PR-closed dispatch (this slice ships PR-comment end-to-end; the Edge Function already routes the other event types via `mapping.ts`'s computePr*Dispatches but the call surfaces ship in 24.4 alongside their own E2E validation).
- Deep-link routing payload (Phase 24.5 — `data.route` field).
- iOS sandbox APNs server (Phase 24.4+ if local-debug push iteration becomes a need).
- Sentry / external observability — structured `console.log` is the observability layer at closed-beta scale.

## Smoke test (post-deploy)

After `supabase functions deploy notify-on-write` and Dashboard webhook wiring:

```bash
WEBHOOK_URL="https://<project-ref>.supabase.co/functions/v1/notify-on-write"
SECRET="<SKEINLY_DATABASE_WEBHOOK_SECRET value>"
BODY='{"type":"INSERT","table":"suggestions","schema":"public","record":{"id":"00000000-0000-0000-0000-000000000001","author_id":"00000000-0000-0000-0000-000000000002","target_pattern_id":"00000000-0000-0000-0000-000000000003","status":"open"}}'

curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "Authorization: Bearer $SECRET" \
  -H "Content-Type: application/json" \
  -d "$BODY"
```

Expected: `HTTP 200` with `{"ok":true,"dispatch_count":N,"send_stats":{"success":...,"delete_token":...,"transient_error":...,"config_error":...,"skipped_no_creds":...}}`. Pull function logs via `mcp__supabase__get_logs service=edge-function` and look for `notify_on_write_dispatched` (high-level summary) + per-token `device_token_deleted` (token cleanup events) + `notify_on_write_transient_error` + `notify_on_write_config_error` (per-token outcomes) structured log lines. Phase 24.1's `notify_on_write_skipped_send` event is gone — Phase 24.3 events take its place.

Bad-secret path:

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "Authorization: Bearer bogus" \
  -H "Content-Type: application/json" \
  -d "$BODY"
```

Expected: `HTTP 401` with `{"error":"unauthorized"}`.

Missing-header path:

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "Content-Type: application/json" \
  -d "$BODY"
```

Expected: `HTTP 401` with `{"error":"unauthorized"}` (the function rejects before payload parse).

## Privacy (per ADR-017 §3.11)

- Edge Function never logs device tokens (Phase 24.3 enforces). Phase 24.1 has no token reads at all.
- Webhook secret never echoed in response bodies.
- Display name fallback (`Someone` / `誰か`) avoids leaking "this user has no display name" as an inferable signal — the body looks identical for null-display-name vs explicit-display-name "Someone".
- `device_tokens.locale` is the only PII-adjacent column read by this function (Phase 24.3+); locale is not directly identifying.
