# `verify-receipt` Edge Function

Phase H IAP receipt validation. Single endpoint handles both iOS (App Store Server API) and Android (Google Play Developer API). Skeleton scaffolding lands in Phase H prep; actual platform-specific verification logic lands in Phase H implementation.

## Endpoint

`POST https://<project-ref>.supabase.co/functions/v1/verify-receipt`

## Request

Headers: `Authorization: Bearer <user JWT>` (validated server-side; mismatched `userId` returns 403).

Body (iOS):
```json
{
  "platform": "ios",
  "userId": "<auth.uid()>",
  "receipt": "<JWS transaction string from Transaction.jsonRepresentation>"
}
```

Body (Android):
```json
{
  "platform": "android",
  "userId": "<auth.uid()>",
  "receipt": {
    "purchaseToken": "<Subscription.purchaseToken>",
    "productId": "knitnote.pro.monthly"
  }
}
```

## Response

Success (200):
```json
{
  "isPro": true,
  "expiresAt": "2026-05-29T12:34:56Z",
  "productId": "knitnote.pro.monthly"
}
```

Failures:
- `400 invalid_json` — request body not parseable
- `400 unsupported_platform` — platform not "ios" / "android"
- `401 unauthorized` — bearer token invalid or missing
- `403 user_id_mismatch` — body.userId doesn't match JWT
- `501 *_verification_not_implemented` — skeleton; Phase H pending
- `502 verification_failed` — upstream Apple/Google API error
- `500 edge_function_misconfigured` — secrets missing

## Side effects

Successful verification writes to `public.subscriptions` via service-role bypass. The row schema + RLS is defined in [supabase/migrations/017_subscriptions.sql](../../migrations/017_subscriptions.sql).

## Webhooks (Phase H+)

The same endpoint accepts Apple App Store Server Notifications V2 and Google Play Real-Time Developer Notifications. The webhook payloads are platform-specific; the function detects them by header (`x-apple-...` / `Authorization` JWT signed by Google) and routes accordingly. **Webhook handling is Phase H+ scope** — alpha1 client-pull verification is sufficient for tester-scale validation.

## Secrets

Registered via `supabase secrets set` per [docs/en/release-secrets.md](../../../docs/en/release-secrets.md#supabase-edge-function-secrets-4-secrets):

- `APP_STORE_CONNECT_API_KEY` — raw `.p8` PEM body for Apple JWS signing
- `APP_STORE_CONNECT_KEY_ID` — 10-char Key ID
- `APP_STORE_CONNECT_ISSUER_ID` — UUID
- `APPLE_TEAM_ID` — 10-char Team ID (also reused for APNs)
- `FIREBASE_SERVICE_ACCOUNT_JSON` (or `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`) — service account JSON for Google Play API OAuth

`SUPABASE_SERVICE_ROLE_KEY` and `SUPABASE_URL` are auto-injected by Supabase into every Edge Function context — no manual registration.

## Local dev

```bash
supabase functions serve verify-receipt --env-file ./supabase/.env.local
```

Test with curl:
```bash
curl -X POST http://localhost:54321/functions/v1/verify-receipt \
  -H "Authorization: Bearer <test-user-jwt>" \
  -H "Content-Type: application/json" \
  -d '{"platform":"ios","userId":"<uuid>","receipt":"<jws>"}'
```

## Deploy

```bash
supabase functions deploy verify-receipt --no-verify-jwt
```

`--no-verify-jwt` is intentional: this function validates the JWT manually (line ~70) so it can read the user-id and reject impersonation in the same code path that handles webhooks (which don't carry user JWTs but do carry signed Apple/Google payloads).
