# `notify-on-write` Edge Function

Fan-out of PR-collaboration events to APNs (iOS) + FCM (Android). Triggered by 3 Supabase Database Webhooks on INSERT/UPDATE to `suggestions` and INSERT to `suggestion_comments`. Phase 24.1 / 24.3 / 24.5 — full design in [ADR-017](../../../docs/en/adr/017-phase-24-push-notifications.md) and [ADR-018](../../../docs/en/adr/018-phase-24-3-push-send-paths.md).

## Code layout

| File | Role |
|---|---|
| `index.ts` | Deno.serve handler: Bearer auth → payload parse → dispatch routing |
| `mapping.ts` | Pure helpers — recipient computation, locale-keyed template registry |
| `apns.ts` | APNs HTTP/2 ES256 JWT signing + send + classifier |
| `fcm.ts` | FCM HTTP v1 OAuth exchange + send + classifier |
| `_fakes.ts` | `globalThis.fetch` monkey-patch for tests |
| `mapping.test.ts` / `apns.test.ts` / `fcm.test.ts` / `dispatch.test.ts` | Deno tests, 62 total |

## Runs with `verify_jwt = false`

Database Webhooks do NOT carry a Supabase JWT. Auth is enforced inside the function via constant-time Bearer-token compare against `SKEINLY_DATABASE_WEBHOOK_SECRET`. Pattern shared with `revenuecat-webhook` and `submit-bug-report`.

## Secrets

`SKEINLY_DATABASE_WEBHOOK_SECRET` (EF-6) + `APPLE_APNS_KEY_P8` / `APPLE_APNS_KEY_ID` / `APPLE_TEAM_ID` (EF-1/EF-2) + `FIREBASE_SERVICE_ACCOUNT_JSON` (EF-3). Registry + first-time registration: [docs/en/release-secrets.md](../../../docs/en/release-secrets.md). Rotation: [docs/en/ops/secrets-rotation.md](../../../docs/en/ops/secrets-rotation.md).

If APNs creds are missing, iOS dispatches degrade gracefully (skipped + counted in `send_stats.skipped_no_creds`); same for FCM SA. Half-configured deployments don't crash.

## Operating this function

- **Deploy / re-deploy**: [docs/en/ops/release.md → Edge Functions](../../../docs/en/ops/release.md) or `git pull && supabase functions deploy notify-on-write`.
- **Configure the 3 Database Webhooks**: [docs/en/ops/webhooks.md](../../../docs/en/ops/webhooks.md).
- **Diagnose missing push notifications**: [docs/en/ops/incident-playbook.md → Push notification not landing](../../../docs/en/ops/incident-playbook.md#symptom-push-notification-not-landing).

## Local tests

```bash
deno test --allow-net --allow-env supabase/functions/notify-on-write/
```

62 tests cover template parity (en/ja), locale fallbacks, recipient matrices, JWT signing + caching, classifier matrices, end-to-end dispatch under fake fetch. `--allow-net` is needed for the JSR `@zaubrik/djwt@^3` import; `--allow-env` for `Deno.env.get`.

## Privacy

- Device tokens never logged.
- Webhook secret never echoed in response bodies.
- Null display-name fallback (`Someone` / `誰か`) avoids leaking "this user has no display name" as an inferable signal.
- `device_tokens.locale` is the only PII-adjacent column read; not directly identifying.
