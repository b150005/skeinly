# Runbook — Secret Rotation

> **Purpose**: rotation procedure per secret. Use when a secret has likely leaked, when a team member with access leaves, or for annual hygiene.
>
> **Audience**: the operator responding to a secret-rotation trigger.
>
> **Source-of-truth pairing**: this runbook gives you the rotation *procedure*; the secret *registry* (what each secret is for, how to register it for the first time, how to verify it landed) lives in [release-secrets.md](../release-secrets.md). They're paired because rotation is logically a re-execution of the registration step.

## When to rotate

- A team member with secret access leaves.
- A laptop containing original credentials (`.p12`, `.p8`, `.jks`, JSON keys) is lost or stolen.
- An automation key is suspected of leak (e.g. a public log accidentally echoed it).
- Annual hygiene (recommended every 12 months for API keys and signing profiles).
- A vendor's security advisory says to.

## Rotation cheatsheet

| Secret | Procedure | Frequency |
|---|---|---|
| `APPLE_DISTRIBUTION_CERT_BASE64` + password | Apple Developer → Certificates → revoke + create new + re-export `.p12` → re-register both | Annual or on incident |
| `APPLE_TEAM_ID` | Cannot change without changing teams. | Never |
| `APP_STORE_CONNECT_API_KEY_*` (3 secrets) | ASC → Team Keys → revoke + generate new + re-register all 3 (base64, id, issuer) | Annual |
| `KEYSTORE_*` (4 secrets) | **Do NOT rotate.** Losing the keystore breaks Play Store updates permanently. Google Play "App Signing by Google Play" key reset is a last-resort recovery, not a routine rotation. | Never |
| `SUPABASE_PUBLISHABLE_KEY` | Supabase Dashboard → Project Settings → API Keys → Generate new Publishable key → re-register + re-build app | On suspected leak |
| `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` (per-Environment) | Re-download from Firebase Console only on package or signing SHA-1 change. | Effectively never |
| `FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64` (per-Environment) | Re-download from Firebase Console only on Bundle ID change. | Effectively never |
| `SENTRY_DSN_*` | Sentry → Settings → Project → Client Keys → revoke + create new → re-register | On suspected leak |
| `SENTRY_AUTH_TOKEN` | Sentry → **Organization Settings** → Auth Tokens → revoke + recreate (must be Organization Token, not User Token) | Annual |
| `POSTHOG_PROJECT_API_KEY` | PostHog → Settings → Project → reset Project API Key | On suspected leak |
| `REVENUECAT_API_KEY_*` | RevenueCat → Project Settings → Apps → Public SDK Key → revoke + issue new | On suspected leak |
| `GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64` (Environment-scoped) | Cloud Console → IAM → Service Accounts → `google-play-publisher@...` → revoke + new key → re-register with `gh secret set --env production` | Annual |
| `APPLE_APNS_KEY_P8` / `APPLE_APNS_KEY_ID` (Edge Function) | Apple Developer → Keys → revoke + generate new + register both via `supabase secrets set` + `supabase functions deploy notify-on-write` | Annual |
| `FIREBASE_SERVICE_ACCOUNT_JSON` (Edge Function) | Firebase Console → Service Accounts → revoke + new key + `supabase secrets set` + `supabase functions deploy notify-on-write` | Annual |
| `REVENUECAT_WEBHOOK_SECRET` (Edge Function) | `openssl rand -hex 32` for new value → update RevenueCat Webhook Authorization header in dashboard → `supabase secrets set` + re-deploy | Annual |
| `SKEINLY_DATABASE_WEBHOOK_SECRET` (Edge Function) | `openssl rand -hex 32` for new value → `supabase secrets set` + re-deploy + update the Authorization HTTP header on each of the 3 Database Webhooks via Supabase Dashboard | Annual |
| `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM` (Edge Function) | GitHub App settings → Generate new private key → download `.pem` → `supabase secrets set` + `supabase functions deploy submit-bug-report` → revoke old key on App settings page | Annual |
| `SKEINLY_BUGREPORT_APP_ID` / `SKEINLY_BUGREPORT_INSTALLATION_ID` | Immutable for the App. Don't rotate. | Never |

## Detailed procedures

### iOS distribution certificate

**Trigger**: cert expired (Apple-issued certs are 1 year), team member with `.p12` left, or annual hygiene.

```bash
# 1. Apple Developer → Certificates, Identifiers & Profiles → Certificates
#    - Find the iOS Distribution cert, click revoke
#    - Click "+" → Apple Distribution → Continue → upload a fresh CSR (generated from Keychain Access)
#    - Download the new .cer

# 2. Import into Keychain Access (double-click .cer)
#    Right-click the new cert → Export → save as .p12
#    Set a strong export password — you'll need it for the secret

# 3. Re-export sigh's stored profile (if used) — sigh auto-renews on fetch
cd iosApp && bundle exec fastlane sigh --force

# 4. Re-register both GitHub Secrets
base64 -i <new>.p12 | pbcopy
gh secret set APPLE_DISTRIBUTION_CERT_BASE64
# paste, Ctrl+D

echo "<the export password>" | pbcopy
gh secret set APPLE_DISTRIBUTION_CERT_PASSWORD
# paste, Ctrl+D

# 5. Verify by triggering a build
make release-ipa-local
```

### App Store Connect API key

**Trigger**: annual hygiene or leak suspicion.

```bash
# 1. ASC → Users and Access → Integrations → Team Keys
#    - Revoke the existing key
#    - "+" → name "Skeinly CI" → role Admin → Generate
#    - Download the .p8 (one-time-only — back up to encrypted storage immediately)
#    - Note the Key ID (10 chars) and Issuer ID (UUID)

# 2. Re-register all 3
base64 -i AuthKey_<KEY_ID>.p8 | pbcopy
gh secret set APP_STORE_CONNECT_API_KEY_BASE64
# paste, Ctrl+D

gh secret set APP_STORE_CONNECT_API_KEY_ID
# paste 10-char key id, Ctrl+D

gh secret set APP_STORE_CONNECT_ISSUER_ID
# paste UUID, Ctrl+D
```

### Supabase Edge Function secrets

`supabase secrets set` is the only registration path for the 7 Edge Function secrets:

```bash
# Example: APNs key rotation
# 1. Generate new key on Apple Developer
# 2. supabase secrets set APPLE_APNS_KEY_P8="$(cat new.p8)"
# 3. supabase secrets set APPLE_APNS_KEY_ID="ABC1234567"
# 4. Re-deploy the consuming function
supabase functions deploy notify-on-write
# 5. Smoke test (see supabase/functions/notify-on-write/README.md)
```

> **The `SKEINLY_` prefix on the bug-report and database-webhook secrets is load-bearing**: Supabase reserves `SUPABASE_*` for platform-injected env vars and `supabase secrets set` rejects names with that prefix. Don't rename to `SUPABASE_BUGREPORT_*` even though it would group nicely.

### Database Webhook Bearer secret

This one's tricky because the secret is in two places that must agree.

```bash
# 1. Generate a new value
NEW_SECRET=$(openssl rand -hex 32)

# 2. Register on Supabase Edge Function side
supabase secrets set SKEINLY_DATABASE_WEBHOOK_SECRET="${NEW_SECRET}"

# 3. Deploy the consuming function
supabase functions deploy notify-on-write

# 4. Update the Authorization HTTP header on EACH of the 3 webhooks
#    via Supabase Dashboard → Database → Webhooks → click each row → edit
#    HTTP Headers → replace the Authorization Bearer value
#    (per supabase/functions/notify-on-write/README.md the 3 webhooks are on
#    suggestions INSERT, suggestions UPDATE, suggestion_comments INSERT)

# 5. Smoke test by triggering one PR-related event
#    (open a Suggestion on a test pattern). Verify the function logs show
#    success, not 401 unauthorized.
```

A mismatch between steps 2 and 4 causes the function to reject every delivery with 401 — the symptom is "no pushes for anyone, all the time". See [incident-playbook.md → Push notification not landing](incident-playbook.md#symptom-push-notification-not-landing).

### GitHub App private key (Skeinly Feedback)

**Trigger**: annual hygiene or PEM leak suspicion.

```bash
# 1. GitHub → Settings → Developer settings → GitHub Apps → Skeinly Feedback
#    → Private keys → "Generate a private key" — download the .pem
#    Don't revoke the old key yet — we'll do it after the new key is verified.

# 2. Register
supabase secrets set SKEINLY_BUGREPORT_PRIVATE_KEY_PEM="$(cat new.pem)"

# 3. Deploy
supabase functions deploy submit-bug-report

# 4. Smoke test — submit a bug report from a beta build, verify the Issue lands

# 5. Once verified, revoke the OLD key on the GitHub App settings page
#    (it shows multiple keys; delete the one with the older "added" date)
```

`SKEINLY_BUGREPORT_APP_ID` and `SKEINLY_BUGREPORT_INSTALLATION_ID` are immutable for the App — they never need rotation.

## Verification after rotation

After every rotation:

1. **Trigger the consuming surface** to confirm the new secret works:
   - iOS / Android cert: `make release-ipa-local` / `make release-aab-local` locally; or push a release tag.
   - ASC API key: a release tag push (the iOS `build-ios` job exercises it).
   - Play Publisher SA: same.
   - Supabase Edge Function secrets: function smoke test per `supabase/functions/<name>/README.md`.
   - Database Webhook secret: trigger any PR-related DB write, verify `notify-on-write` logs success.
   - RevenueCat webhook secret: RevenueCat Dashboard → Webhooks → "Send test event" → expect 200.

2. **Confirm the old credential no longer works** (this is the proof-of-revocation step):
   - Apple cert: the old `.p12` should now fail `security cms -D` ASC validation.
   - APNs `.p8`: the old key listed in Apple Developer → Keys should be "Revoked".
   - Bearer-style secrets (RevenueCat / Database Webhook): test with the OLD secret value, expect HTTP 401.

3. **Update the rotation date** in a private operations log (if you maintain one). Annual cadence is easier to honor with a calendar reminder than from memory.

## Security incident response

If a secret has confirmed leaked (e.g. found in a public log, accidentally pushed to a public repo branch):

1. **Revoke first, register second.** Cut off the leaked credential's access before generating the replacement, even if it means a brief outage. Revocation is the same path as rotation step 1 above.
2. **Audit blast radius**:
   - iOS distribution cert leak → check ASC for unauthorized TestFlight uploads since the leak window.
   - APNs `.p8` leak → check APNs delivery logs (Apple doesn't expose this directly; assume worst-case).
   - Service-role / GitHub App PEM leak → check Postgres / Issue creation logs for unauthorized writes.
3. **Document the incident** in CLAUDE.md → "Tech Debt Backlog" → "Security incidents" (create if missing), with date + scope + remediation.

## Cross-reference

- [release-secrets.md](../release-secrets.md) — full secret registry; first-time registration steps; bulk verification.
- [incident-playbook.md](incident-playbook.md) — symptom-indexed failures, several of which trace to rotation-required secrets.
- [.claude/CLAUDE.md](../../../.claude/CLAUDE.md) → "Tech Debt Backlog" — track deferred rotation work.
