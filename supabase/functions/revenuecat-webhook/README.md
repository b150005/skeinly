# `revenuecat-webhook` Edge Function

Receives subscription state-change events from RevenueCat (renewal, cancellation, expiration, refund, ...) and upserts the corresponding row in `public.subscriptions` via the `upsert_subscription_from_webhook` RPC (migration 023). Phase 39 closed-beta prep — full design in [ADR-016](../../../docs/en/adr/016-phase-41-pro-subscription-dynamic-symbols.md).

## Code layout

| File | Role |
|---|---|
| `index.ts` | Deno.serve handler: Bearer auth → event filter → status mapping → RPC call |
| `mapping.ts` | Pure helpers — event-type-to-status mapping, store-to-platform mapping, environment normalization |
| `mapping.test.ts` | Deno tests, 29 total |

## Runs with `verify_jwt = false`

RevenueCat webhook deliveries do not carry a Supabase JWT. Auth is enforced inside the function via constant-time Bearer-token compare against `REVENUECAT_WEBHOOK_SECRET`. Pattern shared with `notify-on-write` and `submit-bug-report`.

## Secrets

`REVENUECAT_WEBHOOK_SECRET` (EF-5) — shared secret matching the value in RevenueCat Dashboard → Integrations → Webhooks → Authorization header. Registry + first-time registration: [docs/en/release-secrets.md → EF-5](../../../docs/en/release-secrets.md#ef-5-revenuecat_webhook_secret). Rotation: [docs/en/ops/secrets-rotation.md](../../../docs/en/ops/secrets-rotation.md).

## Operating this function

- **Deploy / re-deploy**: `git pull && supabase functions deploy revenuecat-webhook`.
- **One-time setup**: configure the webhook URL in RevenueCat Dashboard with the Bearer secret as the Authorization header. Full procedure: [docs/en/release-secrets.md EF-5](../../../docs/en/release-secrets.md#ef-5-revenuecat_webhook_secret).
- **Diagnose webhook delivery failures**: [docs/en/ops/incident-playbook.md → RevenueCat webhook not landing](../../../docs/en/ops/incident-playbook.md#symptom-revenuecat-webhook-not-landing).
- **Onboard a sandbox tester**: [docs/en/ops/beta-testing.md](../../../docs/en/ops/beta-testing.md).

## Local tests

```bash
deno test --allow-net --allow-env supabase/functions/revenuecat-webhook/
```

29 tests cover the event-type-to-status mapping matrix (INITIAL_PURCHASE / RENEWAL / CANCELLATION + cancel_reason variants / EXPIRATION / BILLING_ISSUE / REFUND / etc.), TEST / SUBSCRIBER_ALIAS / TRANSFER filtering, anonymous app_user_id rejection, environment normalization.

## Smoke test

Trigger from the RevenueCat dashboard:

1. https://app.revenuecat.com → Project → Integrations → Webhooks → your webhook → **Send test event**.
2. Expected: dashboard shows "200 OK" delivery.
3. Verify via `mcp__supabase__get_logs service=edge-function` — look for the `revenuecat_webhook_*` event line.

## Notes

- The `last_verified_at` ordering guard inside `upsert_subscription_from_webhook` prevents out-of-order RevenueCat retries from overwriting newer state with older state.
- The `environment` column (migration 024) distinguishes sandbox (closed-beta tester) rows from production rows. Analytics queries filter via `WHERE environment = 'production'`.
- RevenueCat dashboard config is single-webhook (both Production and Sandbox flow through the same endpoint). The function distinguishes via `event.environment`.
