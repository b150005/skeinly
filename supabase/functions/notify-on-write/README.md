# notify-on-write Edge Function

Phase 24.1 (per [ADR-017](../../../docs/en/adr/017-phase-24-push-notifications.md)) — receives Supabase Database Webhook deliveries from collaboration tables and computes per-recipient push notification dispatches. **Phase 24.1 ships log-only**; Phase 24.3 wires the actual APNs / FCM HTTP send paths.

## Architecture

```
PR opened / commented / merged / closed
      │
      │ INSERT or UPDATE on
      │   public.pull_requests / public.pull_request_comments
      ▼
Supabase Database Webhook (HMAC-SHA256 signed delivery)
      │
      │ POST <project-url>/functions/v1/notify-on-write
      │ Header: x-supabase-webhook-signature: <base64 HMAC>
      ▼
notify-on-write Edge Function (this)
      │ 1. Verify HMAC against SUPABASE_DATABASE_WEBHOOK_SECRET (constant-time)
      │ 2. Parse Database Webhook payload { type, table, record, old_record? }
      │ 3. Route by (table, type) → mapping.ts pure helpers compute
      │    NotificationDispatch[] per recipient
      │ 4. Resolve display names + pattern title via service-role JOINs
      │ 5. Phase 24.1: log dispatches; do NOT send to APNs / FCM
      │    Phase 24.3: invoke APNs (ios) / FCM HTTP v1 (android) per row
      │    in device_tokens
      ▼
device_tokens (per-user) ← Phase 24.2 client registers here via PushTokenRegistrar
      │
      ▼
APNs / FCM v1 ← Phase 24.3
```

## Required secrets (release-secrets.md)

- `SUPABASE_URL` — auto-injected
- `SUPABASE_SERVICE_ROLE_KEY` — auto-injected
- `SUPABASE_DATABASE_WEBHOOK_SECRET` — manual: `supabase secrets set` (release-secrets EF-6, Phase 24.1)

Phase 24.3 will additionally consume:
- `APPLE_APNS_KEY_P8` (EF-1 — already registered)
- `APPLE_APNS_KEY_ID` (EF-2 — already registered)
- `APPLE_TEAM_ID` (reused — already registered)
- `FIREBASE_SERVICE_ACCOUNT_JSON` (EF-3 — already registered)

## Deployment

```bash
supabase functions deploy notify-on-write
```

> **JWT verification disabled (load-bearing)**: like `revenuecat-webhook`, this function is invoked by Supabase's Database Webhook system which does NOT carry a Supabase JWT. The function must be deployed with `--no-verify-jwt` (or via `[functions.notify-on-write] verify_jwt = false` in `supabase/config.toml`). Auth is enforced inside the function via constant-time HMAC compare against `SUPABASE_DATABASE_WEBHOOK_SECRET`.

## Database Webhook configuration (post-deploy)

Configure the 3 webhooks via Supabase Dashboard → Database → Webhooks. Full step-by-step in [supabase/webhooks.md](../../webhooks.md).

## Local unit tests

`mapping.ts` is pure — no fetch, no DB, no env vars. Test suite runs offline:

```bash
deno test supabase/functions/notify-on-write/
```

Coverage: 29 tests — template parity (EN/JA), `renderBody` locale resolution + null fallbacks, `computePrOpenedDispatches` / `computePrCommentedDispatches` / `computePrStatusChangeDispatches` exhaustive recipient matrix.

## Phase 24.1 SHELL contract

This slice intentionally:
- Verifies the webhook signature (so 24.2/24.3 can build atop a secured shell).
- Parses the payload and routes to `mapping.ts` recipient computation.
- Emits structured `console.log` lines per dispatch (`event: notify_on_write_skipped_send`).
- Returns `200 { ok: true, dispatch_count: N }` for downstream observability.

This slice intentionally does NOT:
- Send any APNs request (Phase 24.3).
- Send any FCM request (Phase 24.3).
- Read from `device_tokens` (Phase 24.3 — meaningless until Phase 24.2 client wiring populates the table).
- Cleanup invalid tokens via 410/404 handling (Phase 24.3 — no send path to fail).

## Smoke test (post-deploy, Phase 24.1)

After `supabase functions deploy notify-on-write` and Dashboard webhook wiring:

```bash
WEBHOOK_URL="https://<project-ref>.supabase.co/functions/v1/notify-on-write"
SECRET="<SUPABASE_DATABASE_WEBHOOK_SECRET value>"
BODY='{"type":"INSERT","table":"pull_requests","schema":"public","record":{"id":"00000000-0000-0000-0000-000000000001","author_id":"00000000-0000-0000-0000-000000000002","target_pattern_id":"00000000-0000-0000-0000-000000000003","status":"open"}}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64)

curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "x-supabase-webhook-signature: $SIG" \
  -H "Content-Type: application/json" \
  -d "$BODY"
```

Expected: `HTTP 200` with `{"ok":true,"dispatch_count":N}`. Pull function logs via `mcp__supabase__get_logs service=edge-function` and look for `notify_on_write_dispatched` + `notify_on_write_skipped_send` structured log lines.

Bad-signature path:

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "x-supabase-webhook-signature: bogus" \
  -H "Content-Type: application/json" \
  -d "$BODY"
```

Expected: `HTTP 401` with `{"error":"unauthorized"}`.

## Privacy (per ADR-017 §3.11)

- Edge Function never logs device tokens (Phase 24.3 enforces). Phase 24.1 has no token reads at all.
- Webhook secret never echoed in response bodies.
- Display name fallback (`Someone` / `誰か`) avoids leaking "this user has no display name" as an inferable signal — the body looks identical for null-display-name vs explicit-display-name "Someone".
- `device_tokens.locale` is the only PII-adjacent column read by this function (Phase 24.3+); locale is not directly identifying.
