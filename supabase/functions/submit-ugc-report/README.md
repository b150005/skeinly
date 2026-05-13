# submit-ugc-report Edge Function

Pre-alpha A1/A5 — UGC report submission proxy (ADR-021 §D3).

## What it does

POST /functions/v1/submit-ugc-report from an authenticated mobile client:

1. Validates the JWT (Supabase platform — `verify_jwt = true` in
   `supabase/config.toml`).
2. Decodes `sub` claim → `reporter_id`.
3. Validates the request body (`target_type`, `target_id`, `reason`,
   `reason_category`).
4. Applies a per-user rate limit (10 reports / hour per `auth.uid()`).
5. INSERTs a row into `public.ugc_reports` via a JWT-forwarded supabase-js
   client (RLS-enforced `reporter_id = auth.uid()` per migration 031).
6. Mirrors a GitHub Issue to `b150005/skeinly` with label `ugc-report` via the
   **Skeinly Feedback** GitHub App (reuses EF-7 secrets from ADR-020).
7. UPDATEs `public.ugc_reports.github_issue_url` with the Issue's html_url
   (best-effort; failure leaves the DB row canonical).

## Distinct from submit-bug-report

|                   | submit-bug-report    | submit-ugc-report                                |
| ----------------- | -------------------- | ------------------------------------------------ |
| Auth              | `verify_jwt = false` | `verify_jwt = true`                              |
| Reporter identity | Anonymous OK         | UUID captured (false-report abuse investigation) |
| Rate limit        | 5/hr per source-hash | 10/hr per auth.uid()                             |
| GitHub label      | `feedback`           | `ugc-report`                                     |
| DB write          | None                 | INSERT INTO ugc_reports                          |

Two functions because Supabase's `verify_jwt` is binary at the function
boundary. See ADR-021 §A1 for the rejection of the "merge them" alternative.

## Required env

All env are read at request time. Returns `CONFIG_MISSING` if any are absent.

| Env                                 | Source                          | Notes                                              |
| ----------------------------------- | ------------------------------- | -------------------------------------------------- |
| `SUPABASE_URL`                      | auto                            | Supabase platform-injected                         |
| `SUPABASE_ANON_KEY`                 | auto                            | Supabase platform-injected                         |
| `SUPABASE_SERVICE_ROLE_KEY`         | auto                            | Used only for the github_issue_url backfill UPDATE |
| `SKEINLY_BUGREPORT_APP_ID`          | manual (`supabase secrets set`) | Reused EF-7                                        |
| `SKEINLY_BUGREPORT_INSTALLATION_ID` | manual                          | Reused EF-7                                        |
| `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM` | manual                          | Reused EF-7                                        |

## Request / response envelope

### Request

```http
POST /functions/v1/submit-ugc-report
Authorization: Bearer <user-jwt>
Content-Type: application/json

{
  "target_type": "pattern" | "comment" | "suggestion" | "suggestion_comment",
  "target_id": "<UUID>",
  "reason": "<1..2000 chars>",
  "reason_category": "spam" | "harassment" | "sexual" | "violence" | "hate" | "ip" | "other"
}
```

### Success

```json
{
  "ok": true,
  "report_id": "<UUID>",
  "github_issue_url": "https://github.com/b150005/skeinly/issues/123"
}
```

`github_issue_url` is `null` when the Issue POST failed — the DB row is
canonical; operator monitors `WHERE state = 'open' AND github_issue_url IS NULL`
per `docs/en/ops/ugc-moderation-sop.md`.

### Failure

```json
{
  "ok": false,
  "code": "UNAUTHORIZED" | "RATE_LIMITED" | "VALIDATION_FAILED" | "DB_INSERT_FAILED" | "CONFIG_MISSING",
  "message": "<human-readable detail>"
}
```

All application-level failures return HTTP 200 with `ok: false` in the body.
Non-200 is reserved for Supabase platform problems.

## Deploy

```bash
# From repo root after pulling the latest commit:
git pull origin main
supabase functions deploy submit-ugc-report
```

`verify_jwt = true` is picked up from `supabase/config.toml` automatically.

## Smoke test

```bash
# Replace <USER_JWT> with a real authenticated session token (e.g.
# from the mobile app's Authorization header, captured via Charles
# Proxy or a TestFlight build's diagnostic bundle).
curl -i \
    -H "Authorization: Bearer <USER_JWT>" \
    -H "Content-Type: application/json" \
    -d '{
        "target_type": "pattern",
        "target_id": "00000000-0000-0000-0000-000000000000",
        "reason": "Test report from operator smoke test — please dismiss.",
        "reason_category": "other"
    }' \
    https://<PROJECT_REF>.supabase.co/functions/v1/submit-ugc-report
```

Expected: HTTP 200, `ok: true`, a fresh row in `public.ugc_reports`, and a fresh
GitHub Issue tagged `ugc-report` on `b150005/skeinly`. Don't forget to UPDATE
the row state to `dismissed` and close the GitHub Issue afterward.

## Tests

```bash
cd supabase/functions/submit-ugc-report
deno task test
```

Coverage: ~30 cases across `index.test.ts` + `mapping.test.ts`. Fakes
monkey-patch `globalThis.fetch` so neither GitHub nor Supabase is hit during
tests.

## File overview

| File              | Purpose                                                                                                                                                                             |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `index.ts`        | HTTP handler — JWT decode, validation, rate limit, INSERT, Issue POST, github_issue_url backfill                                                                                    |
| `mapping.ts`      | Pure helpers — closed-enum guards, UUID format check, reason redaction, Issue title/body templating                                                                                 |
| `github_app.ts`   | GitHub App JWT signing + installation token exchange + Issue POST. **Duplicated from submit-bug-report/** — Tech Debt: refactor to `supabase/functions/_shared/` at pre-Phase-40 GA |
| `_fakes.ts`       | Test fakes — fetch monkey-patch, RSA-2048 PEM synthesis, fake Bearer JWT synthesis                                                                                                  |
| `index.test.ts`   | Handler tests                                                                                                                                                                       |
| `mapping.test.ts` | Pure-helper tests                                                                                                                                                                   |
| `deno.json`       | Deno runtime config + `test` task                                                                                                                                                   |
