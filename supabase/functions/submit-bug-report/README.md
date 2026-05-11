# `submit-bug-report` Edge Function

Server-side proxy that creates GitHub Issues from Skeinly users' in-app feedback (bug reports, feature requests, general feedback). Phase 39 W5 — full design in [ADR-020](../../../docs/en/adr/020-phase-39-w5-bug-report-proxy.md).

## Code layout

| File | Role |
|---|---|
| `index.ts` | Deno.serve handler: validate → rate-limit → dispatch |
| `github_app.ts` | JWT signing, installation token exchange, issue create |
| `_fakes.ts` | `globalThis.fetch` monkey-patch for tests |
| `index.test.ts` / `github_app.test.ts` | Deno tests, 32 total |

## Runs with `verify_jwt = false`

Unauthenticated-client pattern (ADR-020 §Q4). Bug reporting must work for users who are not signed in to Supabase Auth — the sign-in flow itself can be the bug. Client sends `apikey: <publishable_key>` defensively but the function does not gate on it. Real auth happens downstream at the GitHub API call via the Skeinly Feedback GitHub App's three secrets.

## Secrets

`SKEINLY_BUGREPORT_APP_ID` / `SKEINLY_BUGREPORT_INSTALLATION_ID` / `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM` (EF-7). Registry + first-time registration: [docs/en/release-secrets.md](../../../docs/en/release-secrets.md). Rotation: [docs/en/ops/secrets-rotation.md](../../../docs/en/ops/secrets-rotation.md). The `SKEINLY_` prefix is load-bearing — Supabase reserves `SUPABASE_*`.

## Operating this function

- **Deploy / re-deploy**: `git pull && supabase functions deploy submit-bug-report`. Picks up `verify_jwt = false` from `supabase/config.toml` automatically.
- **One-time setup**: Create the Skeinly Feedback GitHub App + the `feedback` Issue label on `b150005/skeinly`. Full procedure: ADR-020 §6.
- **Diagnose 401 / 422**: [docs/en/ops/incident-playbook.md → Bug report submission returns 401](../../../docs/en/ops/incident-playbook.md#symptom-bug-report-submission-returns-401).

## Local tests

```bash
deno test --allow-net --allow-env supabase/functions/submit-bug-report/
```

32 tests cover JWT minting + caching, installation token exchange, issue creation, validation matrix (title length / newlines / body cap / labels), rate limit (5/hour per source-hash), error-envelope mapping.

## Smoke test (post-deploy)

```bash
PUB=<sb_publishable_test_key>
curl -i -X POST \
  "https://<project>.supabase.co/functions/v1/submit-bug-report" \
  -H "apikey: ${PUB}" \
  -H "Content-Type: application/json" \
  -d '{"title":"smoke test","body":"This is a smoke test from the README."}'
```

Expected: HTTP 200 with `{"ok":true,"issue_number":<n>,"html_url":"..."}`. Open the URL and verify the Issue exists; close it manually.

## Privacy

- The Issue body is the same payload the user previewed in the in-app reporter — no additional data added server-side.
- Transit metadata (IP, request timestamp) visible to Supabase but not persisted by the function.
- The Issue is created by the App, not by the reporter's personal GitHub account.
- Full disclosure: [docs/en/privacy-policy.md](../../../docs/en/privacy-policy.md).
