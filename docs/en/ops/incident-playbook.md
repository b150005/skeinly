# Runbook — Incident Playbook

> **Purpose**: symptom-indexed triage for common production failures. Each entry is a quick "where to look first" recipe — not a deep debugger replacement.
>
> **Audience**: the operator or developer responding to a user-reported failure or a monitoring alert.

## Triage order (always)

1. **Reproduce or pin down the surface.** Is it iOS, Android, both? Beta build only or also TestFlight production? One user or many?
2. **Pull the relevant logs first.** Often the answer is one Edge Function log line away.
3. **Check the secret + deploy state.** Most "it worked yesterday" failures are post-rotation or post-deploy mismatches.
4. **Check the upstream issue tracker if the failure shape is novel.** See [.claude/docs/process/upstream-issue-triage.md](../../../.claude/docs/process/upstream-issue-triage.md) for the full discipline.

## Log sources at a glance

| Source | How to read |
|---|---|
| Edge Function logs (notify-on-write / revenuecat-webhook / request-pack-download / submit-bug-report) | `mcp__supabase__get_logs service=edge-function` |
| Postgres logs | `mcp__supabase__get_logs service=postgres` |
| Auth logs | `mcp__supabase__get_logs service=auth` |
| Storage logs | `mcp__supabase__get_logs service=storage` |
| Sentry iOS | https://sentry.io/organizations/<org>/issues/?project=<skeinly-ios> |
| Sentry Android | https://sentry.io/organizations/<org>/issues/?project=<skeinly-android> |
| PostHog product analytics | https://app.posthog.com/project/<id>/events |
| Play Console pre-launch reports | Play Console → Skeinly → Pre-launch report |
| App Store Connect crash logs | ASC → TestFlight → Builds → Logs |
| RevenueCat events | RevenueCat dashboard → Events |
| GitHub Actions workflow logs | `gh run view <run-id> --log-failed` |

---

## Symptom: Symbol pack download fails

A user reports a Pro pack (or a new free pack) not appearing in the editor, or the sync silently fails.

### First-line check — server side

```sql
-- Does the pack exist in the catalog?
SELECT id, tier, version, payload_path, payload_size, symbol_count
FROM public.symbol_packs WHERE id = '<pack_id>';
```

If empty → the catalog row was never INSERTed or was DELETEd. See [content-publishing.md](content-publishing.md) for the publish flow.

```bash
# Pull the request-pack-download function logs for the last 1h
# (look for the user's id in the body)
```
```
Run mcp__supabase__get_logs with service: edge-function
```
Look for the `pack_download_signed` event line (success path) or the `error` field (failure path). Status code in the log distinguishes 403 (entitlement) / 404 (pack missing) / 429 (rate-limited) / 500 (Storage sign failure).

### Failure mode → action

| Function returned | What it means | Action |
|---|---|---|
| 401 `unauthorized` | Bearer JWT missing or invalid | User isn't authenticated — verify their auth state on the client side. |
| 403 `pro_entitlement_required` | Pro pack + no active subscription | Verify `SELECT * FROM public.subscriptions WHERE user_id = '<uid>' AND status IN ('active','in_grace_period')`. If empty, the webhook never landed — see "RevenueCat webhook not landing" below. |
| 404 `pack_not_found` | No catalog row | Run the seed migration or re-INSERT the row. |
| 429 `rate_limited` | User exceeded 10 req / 60s | Should self-resolve in <1 min. If chronic for one user, suspect a client retry loop. |
| 500 `internal_error` (Storage sign failed) | `payload_path` mismatch or bucket policy regression | Check `payload_path` literally matches the Storage object path (case-sensitive). |
| 500 `edge_function_misconfigured` | `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` absent at runtime | Should never happen — Supabase auto-injects both. Re-deploy the function. |

### First-line check — client side

If the function returns 200 but the user still sees no symbols, check the client-side sync outcome. The SymbolPackSyncManager logs per-pack `PackSyncOutcome` — look for `VersionRegression` (server bumped down), `ParseError` (payload corrupted), or `Failed` (SQLDelight write error).

See [spec/symbol-pack-delivery.md](../spec/symbol-pack-delivery.md) for the full sync state machine.

---

## Symptom: Push notification not landing

A user expects a push (PR opened, comment, merged, closed) but their device never beeps.

### Step 1: did the trigger event fire?

```sql
-- For PR open / merged / closed:
SELECT id, status, author_id, target_pattern_id, created_at, updated_at
FROM public.suggestions
WHERE id = '<suggestion_id>'
ORDER BY updated_at DESC;

-- For comment:
SELECT id, pull_request_id, author_id, created_at
FROM public.suggestion_comments
WHERE id = '<comment_id>';
```

If the row exists, the DB write happened. The Database Webhook should have fired.

### Step 2: did notify-on-write run?

```
Run mcp__supabase__get_logs with service: edge-function
```
Look for the `notify_on_write_dispatched` event line keyed to the trigger event.

If absent:
- The Database Webhook was never configured. See [webhooks.md](webhooks.md).
- The webhook's Bearer secret rotated and the function rejects with `unauthorized`. See [secrets-rotation.md](secrets-rotation.md) → `SKEINLY_DATABASE_WEBHOOK_SECRET`.

### Step 3: is the recipient's device token registered?

```sql
SELECT user_id, platform, locale, updated_at
FROM public.device_tokens
WHERE user_id = '<recipient_uid>';
```

If empty → the user never registered for push. Most common cause: the user denied the permission prompt, or the prompt was never triggered (the trigger paths are PR-list-with-incoming + PR-detail-opened + first-PR-comment-posted; see [ADR-017 §3.6](../adr/017-phase-24-push-notifications.md)).

### Step 4: did APNs / FCM return success?

In `notify-on-write` logs, look for `send_stats.success` (per-dispatch counter) vs `send_stats.delete_token` (the token was invalidated and removed) vs `send_stats.transient_error` (APNs / FCM 5xx — will be retried on the next event for the same user).

Common token-invalidation causes:
- User uninstalled and reinstalled (FCM 404 UNREGISTERED).
- iOS user reset Settings → General → Transfer or Reset iPhone (APNs 410 Unregistered).
- Bundle ID changed without re-registering (FCM 403 SENDER_ID_MISMATCH).

### Step 5: are the APNs / FCM credentials still valid?

If the function logs show `config_error` instead of per-event token outcomes, the function is failing to authenticate against APNs or FCM:
- APNs: `APPLE_APNS_KEY_P8`, `APPLE_APNS_KEY_ID`, `APPLE_TEAM_ID`. Rotate via [secrets-rotation.md](secrets-rotation.md).
- FCM: `FIREBASE_SERVICE_ACCOUNT_JSON`. Same.

---

## Symptom: RevenueCat webhook not landing

A user completes a purchase but their `subscriptions` row never appears.

```
Run mcp__supabase__get_logs with service: edge-function
```
Look for the `revenuecat_webhook_*` event line for the event id. RevenueCat's dashboard → Events shows the event id and whether the webhook delivery was acknowledged.

### Failure modes

| Symptom | Likely cause | Action |
|---|---|---|
| RevenueCat shows "Webhook delivery failed: 401" | `REVENUECAT_WEBHOOK_SECRET` mismatch between Supabase + RevenueCat dashboard | Rotate per [secrets-rotation.md](secrets-rotation.md); update both sides. |
| RevenueCat shows "Webhook delivery succeeded" but `subscriptions` empty | App user id mapping issue | Check that `Purchases.logIn(skeinly_user_id)` is being called after auth. The webhook's `app_user_id` field must match a row in `auth.users(id)`. |
| Subscriptions row exists but with old status | Out-of-order webhook delivery | Verify `last_verified_at` is monotonic. The `upsert_subscription_from_webhook` RPC guards against this — newer state should win. |

---

## Symptom: Bug report submission returns 401

```bash
# Smoke test the function directly:
PUB=<sb_publishable_test_key>
curl -i -X POST "https://<project>.supabase.co/functions/v1/submit-bug-report" \
  -H "apikey: ${PUB}" \
  -H "Content-Type: application/json" \
  -d '{"title":"smoke test","body":"test"}'
```

Expected: HTTP 200 with `{"ok":true,"issue_number":<n>,"html_url":"..."}`.

Common 401 sources:
- `verify_jwt = true` left on by mistake in `supabase/config.toml`. The function MUST run with `verify_jwt = false` (ADR-020 §Q4). Fix and `supabase functions deploy submit-bug-report`.
- Client sending `Authorization: Bearer <publishable>` instead of `apikey: <publishable>`. Verify against [spec/symbol-pack-delivery.md](../spec/symbol-pack-delivery.md) for the canonical header shape, and ADR-020 for the auth-model decision.

Common 422 from GitHub:
- The `feedback` Issue label was never created on `b150005/skeinly`. Create via Issues → Labels → New.
- The GitHub App's installation has been removed from the repository. Re-install per ADR-020 §6 user-attended steps.

---

## Symptom: Authentication fails / "session expired" loop

```
Run mcp__supabase__get_logs with service: auth
```

Common modes:

| Symptom | Cause | Action |
|---|---|---|
| All logins fail with 400 | `SUPABASE_PUBLISHABLE_KEY` rotated but the app still ships the old key | Re-build and re-deploy the app with the new key. |
| Specific OAuth provider fails | OAuth callback URL drift after Universal Links setup | Verify the callback URL in Supabase Auth → Providers matches the deep link Universal Link spec. |
| "Session expired" infinite loop on cold start | Refresh token revoked + offline retry hangs | Verify `EncryptedSharedPreferences` (Android) / Keychain (iOS) is readable; on iOS, Keychain access can fail after a backup-restore. |

---

## Symptom: Release CI failure

Pull the run:

```bash
gh run view <run-id> --log-failed
```

| Failed job | Likely cause |
|---|---|
| `build-android` job, `:androidApp:bundleRelease` step | Keystore secret mismatch (`KEYSTORE_BASE64` + 3 password secrets). See [secrets-rotation.md](secrets-rotation.md) → KEYSTORE. |
| `build-android` job, `:androidApp:publishBundle` step | Play Publisher SA expired or permission revoked. See [secrets-rotation.md](secrets-rotation.md) → `GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64`. |
| `build-ios` job, fastlane "No provisioning profile found" | Cert + profile chain broken. Re-export `.p12` and re-register. |
| `build-ios` job, "upload_to_testflight 401" | App Store Connect API key rotated. See [secrets-rotation.md](secrets-rotation.md) → `APP_STORE_CONNECT_API_KEY_*`. |
| `verifyIosBetaFlag` step | `version.properties` major != `iosApp/project.yml` IS_BETA YES/NO state. Fix one or the other to match. |
| `verifyI18nKeys` step | Missing key in one of the 5 i18n sources. Re-run locally with `make i18n-verify` for the diff. |

---

## Symptom: A failure looks novel and search shows other people hitting it

Apply the **upstream issue triage** discipline:

1. Search the upstream library's issue tracker (GitHub Issues for OSS libs; vendor support page for closed-source).
2. If an open issue describes the same symptom, treat it as a known limitation — document under CLAUDE.md `## CI Known Limitations` with the upstream link + a `Re-check by` date.
3. If no upstream issue matches, file one (or check our wishlist of canonical reports) before patching around the symptom.

The full discipline is in [.claude/docs/process/upstream-issue-triage.md](../../../.claude/docs/process/upstream-issue-triage.md). Don't apply workarounds before doing the upstream check — "Known Limitations" lists accumulate dead entries when the discipline slips.

---

## Escalation

If first-line triage doesn't converge on a fix within ~30 min:

1. Capture the failing user's id, timestamp window, platform, and the relevant log snippets.
2. Open a GitHub Issue on `b150005/skeinly` with the `incident` label (create the label if missing).
3. If user data integrity is at risk (e.g. subscription billing wrong), prioritize over feature work.

## Cross-references

- [spec/symbol-pack-delivery.md](../spec/symbol-pack-delivery.md)
- [spec/suggestion-flow.md](../spec/suggestion-flow.md)
- ADR-017 (push), ADR-018 (push send paths), ADR-016 (Pro / packs), ADR-020 (bug reports)
- [secrets-rotation.md](secrets-rotation.md)
- [webhooks.md](webhooks.md)
- [.claude/docs/process/upstream-issue-triage.md](../../../.claude/docs/process/upstream-issue-triage.md)
