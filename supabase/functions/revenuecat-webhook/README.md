# revenuecat-webhook Edge Function

Phase 39 closed beta prep — receives subscription state-change events
from RevenueCat (renewal, cancellation, expiration, refund, etc.) and
upserts the corresponding row in `public.subscriptions` via the
`upsert_subscription_from_webhook` RPC (migration 023).

## Architecture

```
RevenueCat (Apple/Google receipt validated server-side)
      │
      │ Webhook POST (Authorization: Bearer <REVENUECAT_WEBHOOK_SECRET>)
      ▼
revenuecat-webhook Edge Function
      │ 1. Verify Bearer matches REVENUECAT_WEBHOOK_SECRET
      │ 2. Parse + extract event fields
      │ 3. Map event.type     → subscription.status enum
      │ 4. Map event.store    → platform enum (ios/android)
      │ 5. Map event.environment → environment enum (production/sandbox)
      │ 6. Filter: TEST, SUBSCRIBER_ALIAS, TRANSFER, anonymous app_user_id
      ▼
upsert_subscription_from_webhook(...) RPC (SECURITY DEFINER)
      │ 1. Re-validate platform/product_id/status/environment closed enums
      │ 2. INSERT ... ON CONFLICT DO UPDATE (with last_verified_at ordering guard)
      ▼
public.subscriptions row (carries `environment` for analytics filtering)
      │
      │ Realtime push (Supabase publication)
      ▼
LocalSubscriptionDataSource (KMP shared)  →  PaywallViewModel  →  UI
```

**Environment column** (added in migration 024): every row carries
`environment IN ('production', 'sandbox')`. Sandbox rows from beta
testers and production rows from real users coexist in the same table;
analytics queries filter via `WHERE environment = 'production'` to
exclude sandbox dev-noise post-Phase 40 GA. RevenueCat dashboard
configuration stays single-webhook (`Both Production and Sandbox`) per
[release-secrets.md EF-5](../../../docs/en/release-secrets.md#ef-5-revenuecat_webhook_secret).

## Required secrets

- `REVENUECAT_WEBHOOK_SECRET` — shared secret matching RevenueCat
  Dashboard's `Authorization` header value. Registered via:

  ```bash
  supabase secrets set REVENUECAT_WEBHOOK_SECRET="$(openssl rand -hex 32)"
  ```

  Auto-injected by Supabase: `SUPABASE_URL` + `SUPABASE_SERVICE_ROLE_KEY`.

See [docs/{en,ja}/release-secrets.md](../../../docs/en/release-secrets.md)
EF-5 for the full setup steps.

## Deployment

```bash
supabase functions deploy revenuecat-webhook
```

> **JWT verification disabled (load-bearing)**: this function is configured
> with `verify_jwt = false` in `supabase/config.toml`'s
> `[functions.revenuecat-webhook]` block. RevenueCat webhook deliveries do
> not carry a Supabase JWT, so the gateway's default JWT verification
> would 401-reject every request before reaching our handler with
> `UNAUTHORIZED_INVALID_JWT_FORMAT` ("Auth header is not 'Bearer
> {token}'"). Auth is enforced inside the function via constant-time
> comparison against `REVENUECAT_WEBHOOK_SECRET` (see
> [`index.ts`](./index.ts) `Bearer` validation block).
>
> If `supabase functions deploy` ignores the config.toml flag (older CLI
> versions), pass `--no-verify-jwt` explicitly:
>
> ```bash
> supabase functions deploy revenuecat-webhook --no-verify-jwt
> ```

After deploy, configure the Webhook URL in RevenueCat Dashboard:

1. https://app.revenuecat.com → Project (`Skeinly`) → **Integrations** →
   **Webhooks** → **Add Webhook**
2. **Webhook URL**: `https://<project-ref>.supabase.co/functions/v1/revenuecat-webhook`
3. **Authorization header**: paste **`Bearer <REVENUECAT_WEBHOOK_SECRET>`**
   (the literal word `Bearer`, a single space, then the hex secret value).
   The dashboard sends this value verbatim as the `Authorization` HTTP
   header; our handler strips the `Bearer ` prefix and compares the
   remainder against the Supabase Edge Function secret. Putting just the
   hex value (without the `Bearer ` prefix) makes our handler 401-reject
   with `error: "unauthorized"`.
4. **Environment filter**: leave unfiltered (sandbox + production both
   flow through; the function distinguishes via `event.environment` and
   writes the value into `subscriptions.environment` for analytics-side
   filtering)
5. **Save**

## Smoke tests (post-deploy)

After `supabase functions deploy revenuecat-webhook` and Dashboard
wiring, run these `curl` checks against the deployed function URL:

### 1. TEST event acknowledgement

```bash
WEBHOOK_URL="https://<project-ref>.supabase.co/functions/v1/revenuecat-webhook"
SECRET="<REVENUECAT_WEBHOOK_SECRET value>"

curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "Authorization: Bearer $SECRET" \
  -H "Content-Type: application/json" \
  -d '{
    "api_version": "1.0",
    "event": {
      "id": "smoke-test-1",
      "type": "TEST",
      "event_timestamp_ms": 1700000000000
    }
  }'
```

Expected: `HTTP 200` with body
`{"status":"ok","note":"test_event_acknowledged"}`.

The RevenueCat dashboard's "Send test event" button delivers the same
event shape; clicking it after configuring the Webhook URL is the
canonical end-to-end smoke test.

### 2. Bad Authorization

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "Authorization: Bearer wrong-secret" \
  -H "Content-Type: application/json" \
  -d '{"event":{"id":"x","type":"TEST","event_timestamp_ms":1700000000000}}'
```

Expected: `HTTP 401` with body `{"error":"unauthorized"}`.

### 3. Missing Authorization

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "Content-Type: application/json" \
  -d '{"event":{"id":"x","type":"TEST","event_timestamp_ms":1700000000000}}'
```

Expected: `HTTP 401`.

### 4. Anonymous `app_user_id` (gracefully ignored)

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "Authorization: Bearer $SECRET" \
  -H "Content-Type: application/json" \
  -d '{
    "event": {
      "id": "smoke-test-anon",
      "type": "INITIAL_PURCHASE",
      "event_timestamp_ms": 1700000000000,
      "app_user_id": "$RCAnonymousID:abc123",
      "product_id": "skeinly.pro.monthly",
      "store": "APP_STORE"
    }
  }'
```

Expected: `HTTP 200` with body
`{"status":"ok","note":"anonymous_or_invalid_app_user_id_ignored"}`.

The webhook deliberately returns 200 for anonymous IDs so RevenueCat
doesn't retry — there's nothing the function can do without a real
Skeinly user UUID. The Phase 39 closed beta sandbox setup instructions
ensure tester accounts authenticate (Skeinly auth → RevenueCat `logIn`
via `RevenueCatAuthBridge`) BEFORE entering the paywall.

## Local unit tests

The mapping helpers (`mapping.ts`) are pure and have a Deno test suite
that runs without Supabase or network:

```bash
deno test supabase/functions/revenuecat-webhook/
```

Coverage: 11 events × {happy path, edge case} = 21 tests across:

- `extractWebhookEvent` field validation (missing event, missing
  required fields, TEST/ALIAS/TRANSFER skip product_id)
- `mapStoreToPlatform` (APP_STORE / MAC_APP_STORE → ios, PLAY_STORE →
  android, others → null)
- `mapEventToStatus` (all 11 event types + cancel_reason variants)

## End-to-end validation (sandbox tester)

Once the Edge Function is deployed and a tester is added to:

- Apple Sandbox testers (App Store Connect → Users and Access)
- Play License testers (Play Console → Setup → License testing)

The full purchase flow is exercised by:

1. Tester signs in to Skeinly app (via Supabase auth) — `RevenueCatAuthBridge`
   fires `Purchases.logIn(userId)`.
2. Tester opens paywall, taps "Subscribe Monthly".
3. Apple StoreKit / Google Play Billing dialog appears with sandbox
   product info; tester confirms (no money charged).
4. RevenueCat receipt validation succeeds → fires `INITIAL_PURCHASE`
   webhook event with `event.environment = "SANDBOX"` and the tester's
   Skeinly user UUID as `app_user_id`.
5. Edge Function inserts row in `public.subscriptions` with
   `platform = 'ios' | 'android'`, `status = 'active'`,
   `expires_at = <accelerated>` (~5min for monthly sandbox).
6. Realtime push → client `LocalSubscriptionDataSource` cache updates
   → paywall closes, Pro features unlock.
7. After ~5 min sandbox accelerated time, RevenueCat fires `RENEWAL`
   or `EXPIRATION` depending on tester's auto-renew choice.

## Logging

All branches log to stdout via `console.log` / `console.warn` /
`console.error`. View live logs:

```bash
supabase functions logs revenuecat-webhook --follow
```

Sentry breadcrumbs (Phase F1) capture stdout from Edge Function context
once Sentry is wired into the function — currently logs are stdout-only,
view via the Supabase dashboard or `supabase functions logs`.
