# `request-pack-download` Edge Function

Mediates downloads from the private `symbol-packs` Storage bucket. Verifies user JWT, applies per-user rate-limit, checks Pro entitlement for `tier='pro'` packs, then mints a 5-minute Storage signed URL. Phase 41 — full design in [ADR-016](../../../docs/en/adr/016-phase-41-pro-subscription-dynamic-symbols.md).

## Code layout

| File | Role |
|---|---|
| `index.ts` | Deno.serve handler: JWT verify → rate-limit → pack lookup → entitlement gate → sign |
| `rate-limit.ts` | In-memory sliding window (10 req / 60s per user_id) |
| `rate-limit.test.ts` | Off-by-one + boundary tests for the rate limiter |

## Runs with `verify_jwt = true`

Unlike the other 3 Edge Functions, this one requires a signed-in Supabase Auth user. The user's JWT bounds the entitlement check (Pro packs require an active `subscriptions` row keyed to that user's id).

## Secrets

`SUPABASE_URL` + `SUPABASE_SERVICE_ROLE_KEY` (auto-injected by Supabase at runtime). No additional secrets are registered for this function — the service-role key is used to bypass RLS when checking `subscriptions` and minting Storage signed URLs.

## Operating this function

- **Deploy / re-deploy**: `git pull && supabase functions deploy request-pack-download`.
- **Publish a new pack the function will serve**: [docs/en/ops/content-publishing.md](../../../docs/en/ops/content-publishing.md).
- **Diagnose 403 / 404 / 500**: [docs/en/ops/incident-playbook.md → Symbol pack download fails](../../../docs/en/ops/incident-playbook.md#symptom-symbol-pack-download-fails).

## Local tests

```bash
deno test supabase/functions/request-pack-download/
```

Limited coverage — the rate-limiter is tested in isolation; the full handler flow is covered by KMP-side tests (`SymbolPackSyncManagerTest` + `RemoteSymbolPackDataSourceTest`) against a stub `SymbolPackRemoteOperations`. The Deno tests are NOT currently CI-gated; run them before any change to `rate-limit.ts` or its call site in `index.ts`.

## Smoke test (post-deploy)

```bash
USER_JWT="<user access_token from supabase.auth.signIn>"
curl -s -X POST \
  "https://<project>.supabase.co/functions/v1/request-pack-download" \
  -H "Authorization: Bearer ${USER_JWT}" \
  -H "Content-Type: application/json" \
  -d '{"pack_id":"jis.knit.beginner"}'
```

Expected on success: HTTP 200 with `{payload_url, payload_url_ttl, current_version, payload_size}`. The `payload_url` is then fetchable with a plain `curl` (no auth needed — the URL carries its own `?token=`).

Refund-revocation semantics + rate-limit caveat: see [docs/en/spec/symbol-pack-delivery.md → Pro-tier defense in depth](../../../docs/en/spec/symbol-pack-delivery.md#pro-tier-defense-in-depth).
