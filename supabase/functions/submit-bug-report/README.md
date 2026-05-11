# submit-bug-report

Phase 39 W5 (ADR-020) — Bug report proxy Edge Function. Receives bug
reports from beta builds and creates GitHub Issues on `b150005/skeinly`
via a dedicated GitHub App with `Issues: Read & write` permission.

## Endpoint

```
POST https://<project>.supabase.co/functions/v1/submit-bug-report
apikey: <SUPABASE_PUBLISHABLE_KEY>
Content-Type: application/json

{
  "title": "[Beta] tap save crashes on iOS 26.4",
  "body": "## Description\n…",
  "labels": ["feedback"]
}
```

## Response

Success:
```json
{ "ok": true, "issue_number": 123, "html_url": "https://github.com/b150005/skeinly/issues/123" }
```

Failure (200 OK with `ok: false` envelope):
```json
{ "ok": false, "code": "RATE_LIMITED" | "VALIDATION_FAILED" | "GITHUB_AUTH_FAILED" | "GITHUB_API_FAILED" | "CONFIG_MISSING", "message": "..." }
```

## User-attended setup (one-time)

1. Open https://github.com/settings/apps/new
2. **GitHub App name**: `Skeinly Feedback`
3. **Homepage URL**: `https://b150005.github.io/skeinly/`
4. **Webhook → Active**: uncheck (no webhooks needed)
5. **Repository permissions → Issues**: Read & write. All other permissions: No access.
6. **Where can this GitHub App be installed?**: Only on this account
7. Save. The page reloads and shows **App ID** at the top.
8. Scroll to "Private keys" section, click **Generate a private key**. A `.pem` file downloads.
9. Open the App's **Install App** page from the left sidebar.
10. Click **Install** next to your account name. On the install screen, select **Only select repositories** → check `b150005/skeinly`. Confirm.
11. The browser URL after install will be `github.com/settings/installations/<INSTALLATION_ID>`. Note the **Installation ID** number.
12. Register secrets on Supabase:

    ```bash
    supabase secrets set SKEINLY_BUGREPORT_APP_ID=<app-id-from-step-7>
    supabase secrets set SKEINLY_BUGREPORT_INSTALLATION_ID=<install-id-from-step-11>
    supabase secrets set SKEINLY_BUGREPORT_PRIVATE_KEY_PEM="$(cat /path/to/downloaded.pem)"
    ```

    The PEM secret must include the `-----BEGIN ... PRIVATE KEY-----`
    and `-----END ... PRIVATE KEY-----` lines verbatim. Both PKCS#1
    (`BEGIN RSA PRIVATE KEY`) and PKCS#8 (`BEGIN PRIVATE KEY`) are
    accepted.

## Deploy

```bash
git checkout main && git pull
supabase functions deploy submit-bug-report
```

`supabase/config.toml` ships with the function entry under
`[functions.submit-bug-report]`; deploy picks up that config
automatically. JWT verification stays on the Supabase default
(`verify_jwt = true`) — Supabase rejects unauthenticated requests at
the edge before they reach this code.

## Smoke test (after deploy)

```bash
ANON=$(supabase status --output env | grep API_KEY | cut -d= -f2)
curl -i \
  -X POST "https://<project>.supabase.co/functions/v1/submit-bug-report" \
  -H "apikey: ${ANON}" \
  -H "Content-Type: application/json" \
  -d '{"title":"[Beta] smoke test","body":"This is a smoke test from the README."}'
```

Expect HTTP 200 with `{"ok":true,"issue_number":<n>,"html_url":"..."}`.
Visit the returned URL to verify the Issue exists. Close it manually
after verification.

## Tests

```bash
cd supabase/functions/submit-bug-report
deno task test
```

Coverage: JWT signing + caching, installation token exchange, issue
creation (success / 401 / 422 / 5xx / missing fields), input
validation matrix, rate limiting (per-source hash, 5/hour cap).

## Rotation

GitHub App private keys do not auto-expire but Apple/industry best
practice rotates annually. Recovery flow:
1. App settings → Generate new private key → download `.pem`.
2. `supabase secrets set SKEINLY_BUGREPORT_PRIVATE_KEY_PEM="$(cat new.pem)"`
3. `supabase functions deploy submit-bug-report`
4. App settings → revoke the old key.

## Architecture

```
Beta build ──POST──▶ Edge Function ──RS256 JWT──▶ GitHub API
                     ├─ Rate limit (5/hr per source)
                     ├─ Validate title/body length
                     ├─ Sign JWT (cached 9 min)
                     ├─ Exchange for install token (cached 55 min)
                     └─ Create Issue
```

See ADR-020 (`docs/en/adr/020-phase-39-w5-bug-report-proxy.md`) for
full design and agent-team deliberation.
