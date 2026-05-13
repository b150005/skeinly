# Phase 39 Closed Beta — Sandbox Tester Setup

> Japanese translation: [docs/ja/ops/beta-testing.md](../../ja/ops/beta-testing.md)

## Goal

Closed beta testers (5–10 people including ≥1 round-chart author + ≥1
ja-JP tester per the [Phase 39 rubric](../phase/phase-39-beta-rubric.md))
exercise the **full Pro purchase flow** — Apple StoreKit / Google Play
Billing dialog, real receipt validation, real RevenueCat events,
real `subscriptions` table writes — but **without paying real money**.

This is achieved by routing tester accounts through:
- **Apple Sandbox testers** (App Store Connect)
- **Google Play License testers** (Play Console)

Both produce sandbox receipts that flow through the same RevenueCat
project and the same `revenuecat-webhook` Edge Function, distinguishable
only via `event.environment = "SANDBOX"` in logs.

## Why this over `grant_alpha_pro`

`migration 017` ships a `grant_alpha_pro(uid)` RPC that bypasses the
purchase flow entirely — admin runs it for each tester user_id, sentinel
row inserts into `subscriptions` with `platform = 'alpha-grant'`, Pro
unlocks. **This works for any beta**, but it skips:
- The Apple / Google paywall UX (won't catch UI bugs in the purchase
  dialog)
- RevenueCat receipt validation
- The webhook delivery path
- Subscription renewal / cancellation / refund cycles

Sandbox testing exercises **all of those**. The accelerated time on
sandbox subscriptions (1 month → 5 minutes for monthly, 1 year → 1
hour for annual) lets testers observe renewal events within a single
beta session.

`grant_alpha_pro` remains useful as a fallback for testers who can't
configure sandbox accounts (e.g. enterprise-managed devices) or for
quick smoke tests where exercising the full purchase flow is overkill.
Both can coexist — `subscriptions` rows with `platform = 'alpha-grant'`
and `platform = 'ios' | 'android'` keyed on the same `user_id` both
satisfy `is_pro(uid)`.

## Prerequisites

Before starting, confirm the following are complete (most are one-time
setup that should already be done):

- [ ] Migration 023 (`upsert_subscription_from_webhook` RPC) applied to
      prod. Verify with
      `mcp__supabase__list_migrations` → look for
      `phase_39_revenuecat_webhook_helper`.
- [ ] `revenuecat-webhook` Edge Function deployed.
      `supabase functions deploy revenuecat-webhook`.
- [ ] `REVENUECAT_WEBHOOK_SECRET` registered as Supabase Edge Function
      secret (per [release-secrets.md EF-4](../release-secrets.md#ef-4-revenuecat_webhook_secret)).
- [ ] RevenueCat Dashboard → Webhooks → Add Webhook configured with the
      Edge Function URL + the same secret value (per same EF-4 section).
      "Send test event" returns green.
- [ ] RevenueCat Dashboard → Project Settings → Apps → Skeinly iOS +
      Skeinly Android both have valid Public SDK Keys (`appl_...` /
      `goog_...`) configured per [vendor-setup.md A0d-2 / A0d-3](../vendor-setup.md).
- [ ] iOS app built with `REVENUECAT_API_KEY_IOS` GitHub secret wired
      (TestFlight build for closed beta).
- [ ] Android app built with `REVENUECAT_API_KEY_ANDROID` GitHub secret
      wired (Internal Testing track upload via `gradle-play-publisher`).
- [ ] `RevenueCatAuthBridge` is in production code (commit `e1088d1` or
      later) so `Purchases.logIn(userId)` fires after Skeinly auth.
- [ ] **Supabase Auth Site URL** points at the production
      email-confirmation landing page (NOT the dev default
      `http://localhost:3000`). See "Supabase Auth URL configuration"
      below.

## Supabase Auth URL configuration

When the Supabase Dashboard has **Confirm email** enabled (the prod
default), Supabase sends a confirmation link to every new sign-up. The
link routes through Supabase's `/auth/v1/verify` endpoint and then
redirects the user's browser to `${Site URL}?code=...`. If Site URL is
left at the dev default `http://localhost:3000` the redirect lands on a
URL the browser can't resolve and testers see a "this site can't be
reached" error — even though the email itself was confirmed
successfully at Supabase's backend before the redirect fired.

One-time setup:

1. Open the Supabase Dashboard for the Skeinly project →
   **Authentication → URL Configuration**.
2. Set **Site URL** to: `https://b150005.github.io/skeinly/email-confirmed/`
3. Under **Redirect URLs**, add (if not already allow-listed):
   - `https://b150005.github.io/skeinly/email-confirmed/`
   - `https://b150005.github.io/skeinly/ja/email-confirmed/`
4. Click **Save**.

The landing page lives at [`docs/public/email-confirmed/index.html`](../../public/email-confirmed/index.html)
(EN) + [`docs/public/ja/email-confirmed/index.html`](../../public/ja/email-confirmed/index.html)
(JA), served by GitHub Pages from the repo's `main` branch. Modifying
the page only requires editing the HTML and pushing — no Supabase
Dashboard update needed unless the URL itself changes.

**Forward-compat note (Phase 40 GA)**: Universal Links / App Links
would let the email-confirmation link open the Skeinly app directly +
exchange the PKCE `code` for an authenticated session in-app, removing
the manual "go back to the app and sign in" step. Wiring is in place
([CLAUDE.md A16](../../../.claude/CLAUDE.md) Tech Debt — `AppDelegate.application(_:continue:restorationHandler:)`
+ AASA template at `docs/well-known/`) but the AASA file needs to land
at a **root domain** path (`https://<host>/.well-known/apple-app-site-association`)
that the current Project Pages site at `b150005.github.io/skeinly/`
cannot serve. Deferred until a custom domain or User Pages repo lands.

## TestFlight tester group structure

Before per-tester setup, decide which TestFlight group each tester belongs to. Two groups recommended for Phase 39 (Agent Team decision 2026-05-13):

| Group | TestFlight type | Members | Build policy | Feedback channel |
|---|---|---|---|---|
| **Skeinly Core** (JA: 「Skeinly コアチーム」) | Internal Testing (max 100) — members must be ASC team members | Operator + 1–2 close-friend collaborators (≤3 total) | Auto-deliver every build (unstable OK) — fast iteration | Direct Slack / LINE channel; immediate feedback |
| **Skeinly Closed Beta** (JA: 「Skeinly クローズドベータ」) | External Testing (max 10,000) — email invite, no ASC team membership required | General testers (3–8) including ≥1 round-chart author + ≥1 ja-JP tester per the [Phase 39 rubric](../phase/phase-39-beta-rubric.md) | Tagged-stable builds only; first build needs Apple Beta App Review (24–48h) | Email + GitHub Issue ([beta-bug.yml template](../../../.github/ISSUE_TEMPLATE/beta-bug.yml)) |

### Default recommendation: External-only (skip Internal Group)

For most Phase 39 closed-beta operators, **only Skeinly Closed Beta (External) is needed**. Reasons:

- The operator (ASC Account Holder) is automatically an Internal Tester by virtue of being on the team — they can self-test TestFlight builds on their own device without creating an Internal Group at all.
- Adding close-friend testers to Internal requires granting ASC team membership (sales data, crash reports, full app visibility) — over-share for typical "test my app" relationships.
- External Group covers all closed-beta testers including close friends with the only cost being Apple Beta App Review (24–48h on the FIRST build only; subsequent builds in the same train ship immediately).

**Create Skeinly Core (Internal) only if** you have a co-developer / co-founder you'd already grant ASC team access to anyway AND want fast unstable-build iteration with them.

### Create groups in ASC → TestFlight:
- Internal Group (skip unless needed per above): TestFlight → **内部テスト → グループを作成** → Group name `Skeinly Core` → "自動配信を有効にする" ON → Save.
- External Group (required): TestFlight → **外部テスト → グループを作成** → Group name `Skeinly Closed Beta` → email invite each tester individually.

Members of Skeinly Core (if created) must first be added as ASC team members (ASC → Users and Access → Users → +). Skeinly Closed Beta members do NOT need to be ASC team members.

## Per-tester setup (repeat for each beta tester)

Collect the tester's:
- Email address (the one they'll use for App Store / Google Play sign-in
  during sandbox purchases — does NOT have to be the same as their
  Skeinly login email; it's the platform sandbox account). For Phase 39,
  the operator typically uses Gmail plus-subaddressing on a centralized
  inbox like `skeinly.app+<cohort>-<locale>-<label>@gmail.com` so all
  sandbox account communications route to one mailbox — see
  [iap-setup-app-store-connect.md](iap-setup-app-store-connect.md) Step 9
  for the full email pattern.
- Which TestFlight group they belong to (Skeinly Core vs Skeinly Closed Beta — see "TestFlight tester group structure" above)
- Whether they need iOS, Android, or both

### iOS — Apple Sandbox tester

1. [App Store Connect](https://appstoreconnect.apple.com) → **Users and
   Access** → **Sandbox** tab → **Testers** → **+** (add new sandbox
   tester).
2. Fill in:
   - **First Name** / **Last Name** (any value — tester-facing only)
   - **Email**: a fresh email **not** already linked to any Apple ID.
     Recommended: `tester-name+skeinly-sandbox@gmail.com` (Gmail's `+`
     trick) or a disposable mailbox. If the tester wants their normal
     Apple ID to remain a real-purchase identity, they need a separate
     sandbox-only Apple ID — App Store Connect's sandbox tester emails
     **cannot overlap with any real Apple ID anywhere in the world**.
   - **Password**: tester chooses (they sign in with these creds in
     iOS Settings during testing)
   - **Date of Birth**: any date 18+
   - **App Store Territory**: matches the storefront the tester will
     use (e.g. Japan for ja-JP testers — pricing is shown in the
     correct local currency)
3. Save. Apple sends an account-confirmation email; the tester must
   click the link.
4. **Tester device setup** (one-time on their iPhone / iPad):
   - Settings → App Store → Sandbox Account → Sign in with the sandbox
     credentials from step 2.
   - **DO NOT** sign out of their real Apple ID for normal app use —
     the sandbox account is a separate slot dedicated to in-app
     purchase testing.
5. **Test purchase flow** (after tester installs Skeinly TestFlight build
   + signs in to Skeinly with their normal Skeinly account):
   - Open paywall in Skeinly.
   - Tap "Subscribe Monthly" (or Annual).
   - Apple StoreKit dialog appears with `[Sandbox]` watermark in the
     pricing area.
   - Tap "Subscribe", confirm with the sandbox Apple ID password.
   - **No money charged**. Pro unlocks.
6. **Renewal observation** (5 minutes for monthly):
   - Wait ~5 minutes. RevenueCat fires `RENEWAL` webhook.
   - Verify in Supabase Dashboard → Database → `subscriptions` table:
     row for the tester's `user_id` should have `last_verified_at`
     bumped, `expires_at` shifted forward.

### Android — Google Play License tester

1. [Play Console](https://play.google.com/console) → **Setup** →
   **License testing** → **License testers** → add the tester's Google
   account email.
2. **Account preferences** → set **License test response** to
   `RESPOND_NORMALLY` (so receipt validation flows through RevenueCat
   normally; `LICENSED` / `NOT_LICENSED` are for non-purchase license
   testing, not in-app subscriptions).
3. Save.
4. **Tester device setup**:
   - The tester signs in to their Android device with the **same
     Google account** added to License testers in step 1.
   - That account must also be added to the Skeinly Internal Testing
     track (Play Console → Testing → Internal testing → Testers →
     Email list). Otherwise they can't install the test build.
5. **Test purchase flow**:
   - Tester installs Skeinly Internal Testing build (via Play Store
     internal testing link sent in their email after step 4).
   - Sign in to Skeinly with their Skeinly account.
   - Open paywall → tap "Subscribe Monthly".
   - Google Play Billing dialog appears with `(test)` annotation in
     the pricing area + a yellow banner "This is a test purchase. You
     won't be charged."
   - Tap "Subscribe".
   - **No money charged**. Pro unlocks.
6. **Renewal observation** (5 minutes for monthly):
   - Same as iOS — `subscriptions.expires_at` shifts forward via
     RevenueCat `RENEWAL` event.

## Smoke checks during the beta

Run these against the prod Supabase project periodically (or whenever a
tester reports "Pro didn't unlock after I subscribed"):

### Confirm webhook events landed for a specific tester

```sql
-- Replace <tester_user_id> with the actual auth.users.id UUID
SELECT
  id,
  user_id,
  platform,
  product_id,
  status,
  environment,    -- 'sandbox' for beta-tester rows, 'production' for real users
  expires_at,
  last_verified_at,
  is_in_trial,
  auto_renew_status
FROM public.subscriptions
WHERE user_id = '<tester_user_id>'
ORDER BY last_verified_at DESC;
```

### Filter analytics by environment (production-only metrics)

The `environment` column (migration 024) lets analytics queries exclude
sandbox dev-noise:

```sql
-- Total active production Pro subscriptions (excludes sandbox testers)
SELECT COUNT(*) FROM public.subscriptions
WHERE environment = 'production'
  AND status IN ('active', 'in_grace_period')
  AND (expires_at IS NULL OR expires_at > NOW());

-- Sandbox tester activity during closed beta
SELECT user_id, platform, product_id, status, last_verified_at
FROM public.subscriptions
WHERE environment = 'sandbox'
ORDER BY last_verified_at DESC
LIMIT 50;
```

Powered by `idx_subscriptions_active_production` (migration 024) so
the production-active aggregate stays fast even at full GA scale.

If no rows for the tester → webhook didn't fire OR didn't reach the
function OR the event lacked a usable `app_user_id`. Check Edge
Function logs.

### Inspect Edge Function logs for a tester's events

```bash
supabase functions logs revenuecat-webhook --follow
```

Search for the tester's user UUID in log output — every successful
upsert logs `app_user_id` + `event_type` + `status`. Failures log
`rpc_error`.

Or via mcp:
```text
mcp__supabase__get_logs service=edge-function
```

### Confirm Apple sandbox + Play license-test env settings persist

iOS: tester's Settings → App Store → Sandbox Account should still show
the sandbox tester email (it can silently sign out after long
inactivity).

Android: Play Console → Setup → License testing → confirm tester email
still in the list (rare but accidental removal happens).

## Common issues

### "Pro doesn't unlock after subscribing in sandbox"

Most common causes (debug in this order):

1. **Tester signed in to Skeinly as a different account than the one
   that made the purchase**. The `Purchases.logIn(userId)` ties
   purchase → Skeinly account; if tester signs in as user-A, makes
   purchase, then logs out and signs in as user-B, only user-A has Pro.
   Fix: confirm tester is signed in to the same Skeinly account
   throughout.
2. **`RevenueCatAuthBridge` not started yet**. The bridge fires after
   Application init + Koin DI; if the user lands directly on the
   paywall via deep link before bootstrap completes, `app_user_id`
   may be anonymous. Check Edge Function logs for
   `anonymous_or_invalid_app_user_id_ignored` — that's this case.
   Fix: tester force-quits + reopens app, signs in, waits 1 second,
   then enters paywall.
3. **Webhook didn't fire**. RevenueCat dashboard → Webhooks → click
   the webhook → **Logs** tab — see if delivery attempts show.
   Possible causes: webhook URL stale (wrong project-ref), Authorization
   header mismatch, RevenueCat-side retry exhausted (unlikely for
   sandbox).
4. **RPC failed**. Edge Function logs show `upsert RPC failed` with
   error message. Most likely a CHECK constraint violation
   (mis-mapped status enum). File a bug, paste the log.

### "Sandbox test purchase succeeded but real purchase fails after Phase 40 GA"

Different code paths only by `event.environment`. If sandbox works in
beta but production fails post-GA, suspect:
- Production-only RevenueCat config (different project? Production-
  only API key wiring?) — Skeinly is single-project so this should
  not happen.
- App Store Connect / Play Console product ID mismatch between sandbox
  and production. RevenueCat catches this and surfaces an "Unconfigured
  product" error on the dashboard.

### "Tester is in App Store Connect Sandbox testers but Apple won't let them sign in on iOS"

Apple sandbox tester accounts must be **age 18+** at time of creation.
Fix: edit the tester record → Date of Birth → set to a year ≥ 2007.
Some Apple regions also require additional billing-address fields —
fill placeholder values; sandbox doesn't validate them.

## Exit criteria

Beta closes (per [phase-39-beta-rubric.md](phase-39-beta-rubric.md))
when:
- ≥5 testers complete Phase 32+34+38 happy paths
- Zero CRITICAL issues
- ≤3 HIGH issues
- ≥1 round-chart-author signal for Phase 35.2 priority

The sandbox-driven Pro purchase flow is **not** in the explicit exit
criteria, but real testers exercising it will surface bugs in:
- Paywall UX (tap targets, accessibility, copy)
- RevenueCat SDK error handling (network blips during purchase)
- Subscription state UI (toast on success, gating on expiry)

Bugs found in the purchase flow during beta should be filed with
the same beta-bug template, severity tagged appropriately. Phase 39.5
ships the bug-report submission UX so this is unblocked.

## Phase 40 GA cutover (post-beta)

When Phase 39 closes and Phase 40 GA launches:

- App Store Connect: tester accounts can stay in Sandbox testers (no
  cleanup needed; production users use their real Apple IDs and don't
  conflict).
- Play Console: License testers list can stay populated (same
  reasoning).
- RevenueCat dashboard: Webhook stays the same URL + secret. No
  reconfiguration needed.
- The `subscriptions` table will start receiving production
  `event.environment = "PRODUCTION"` events alongside any remaining
  sandbox events from continued internal testing. Both write to the
  same table; the `environment` column (migration 024, shipped with
  Phase 39 prep) marks each row so analytics queries can filter out
  sandbox dev-noise via `WHERE environment = 'production'`. The
  `idx_subscriptions_active_production` partial index keeps these
  filtered queries fast at GA scale.
