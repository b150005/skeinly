# Pre-Alpha Release Checklist

Tracker for the closed-alpha launch readiness audit. Every item below maps to a TODO in the active development session and progresses through Open → In Progress → Confirmed / Action Required.

Created 2026-05-12 after the user listed pre-launch concerns (Kill Switch, security audit, deep link verification, privacy compliance, accessibility, store policies, crash/ANR, etc.) and explicitly requested no-cost-cutting pursuit of the best possible v1.0 outcome.

> **Discipline**: Every item below must be Confirmed (with code/config citation) OR have a follow-up Action Required line with owner + target slice. No item is closed by hand-wave.

---

## 1. App Store Review Guidelines Compliance (Apple)

**Source**: https://developer.apple.com/app-store/review/guidelines/ — fetched 2026-05-12 via docs-researcher agent.
**Subscription disclosure source**: https://developer.apple.com/app-store/subscriptions/ — fetched 2026-05-12.

### 1.1 Action Required (must fix before submission)

#### A1. Guideline 1.2 — UGC moderation (Discovery + Suggestion)

Skeinly's Discovery feed and Suggestion / comment system are UGC under Apple's definition. Apple requires **all four** of the following:

| Requirement | Status | Action |
|---|---|---|
| Filter / moderation mechanism for objectionable posts | Missing | Add keyword block-list or manual review queue for user-submitted text (pattern names, descriptions, suggestion comments). |
| Mechanism to report offensive content | Missing | Add **Report** button on Discovery pattern cards + Suggestion comments. Wire to internal triage queue (GitHub Issues label `ugc-report` or dedicated Supabase table). |
| Ability to block abusive users | Missing | Add **Block user** feature in user profile / interaction surfaces. Blocked users' content must be hidden from Discovery + cannot send new Suggestions to the blocker. |
| Published contact information | Partial | Privacy Policy + ToS hosted at `docs/public/`. **Confirm** a visible in-app contact path (Settings → Help & Support email link OR Help Center URL). |

**Owner**: ui-ux-designer + architect (data model for `reports` / `user_blocks` tables) + technical-writer (contact email).

---

#### A2. Guideline 2.1(a) — Demo account for App Review

Apple Review needs a working demo credential with **pre-populated data demonstrating all features**:
- Discovery feed with sample patterns
- At least one active Project
- Active Suggestion flow (open + comments)
- F1 Pro subscription state (or paywall demonstrating the Pro upgrade path)
- Live Supabase backend during review

**Action**:
- Create permanent `demo@skeinly.example` (or equivalent) credential.
- Pre-populate seed data via a `seed_demo_account` SQL migration or Edge Function that runs on-demand.
- Document in App Store Connect Review Notes: credential + features to exercise + IAP sandbox note.

**Owner**: devops-engineer + product-manager.

---

#### A3. Guideline 3.1.2(c) — Paywall pre-purchase disclosure

The paywall screen (before StoreKit system sheet) MUST display:
1. What content / features are included (F1 Pro feature list).
2. Price AND billing period (price must be **the most prominent** pricing element).
3. Annual total amount for the yearly plan (e.g., "¥1,200/year (¥100/month equivalent)").
4. **Restore Purchases** button for existing subscribers.
5. Links to Terms of Service and Privacy Policy.

**Action**: Audit `SubscriptionPaywallScreen` (or equivalent — locate the actual Compose + SwiftUI files) against this 5-item checklist. Locate any missing items and add.

**Owner**: ui-ux-designer + implementer.

---

#### A4. Guideline 5.2 — Intellectual property (DMCA + JIS symbols)

Two risks:

1. **JIS stitch symbol catalog provenance**: ADR-009 uses JIS-standard symbols. Verify the SVG/bitmap renderings in `symbol_packs` are **original artwork** (or licensed), not photocopied from JIS publication PDFs. If sourced from JIS PDFs, redraw or document license.

2. **User-shared pattern copyright**: Users may upload patterns derived from copyrighted commercial knitting patterns (e.g., Drops Design, Lion Brand). Skeinly's ToS MUST include:
   - DMCA-compliant notice-and-takedown procedure (17 U.S.C. § 512).
   - Designated DMCA agent contact email.
   - Counter-notification process.

**Action**:
1. Audit `docs/public/terms-of-service/index.html` (EN + JA) for DMCA safe-harbor language. Add if missing.
2. Audit symbol catalog provenance (knitter agent + technical-writer review).

**Owner**: technical-writer (ToS) + knitter agent (catalog audit).

---

### 1.2 Needs Verification (confirm before submission)

#### V1. Guideline 5.1.1(i) — Privacy Policy linked from within app

Privacy Policy hosted at `docs/public/privacy-policy/index.html` (EN + JA). Confirm both:
- URL entered in App Store Connect metadata field.
- Link accessible from within the app (Settings screen — confirm existing entry point).

#### V2. Guideline 5.1.2(i) — ATT (App Tracking Transparency)

PostHog + Sentry are likely NOT "tracking" under Apple's ATT definition (no cross-app advertising; opt-in; for app improvement only). **Confirm with PostHog DPA** that PostHog does not use Skeinly data for ad-network sharing. If confirmed, no ATT prompt is required — but document the decision rationale.

#### V3. Guideline 2.5.1 — Private API binary scan

The K/N `performSelector(NSSelectorFromString("registerForRemoteNotifications"))` workaround (Phase 24.2e) accesses a public Obj-C method. Run binary analysis (`nm` + Instruments) on the iOS build to confirm no `_Private`-prefixed symbols are imported. Document scan result.

#### V4. Guideline 2.3.1(a) — Review Notes content

App Store Connect Review Notes MUST disclose:
- PostHog + Sentry SDKs with opt-in consent behavior (no data collection until opt-in).
- In-app bug report → GitHub Issues proxy (server-side, scope-limited GitHub App).
- `BuildFlags.isBeta` gate behavior (which features are visible only in beta builds vs GA).
- Push notification deferred-prompt pattern.

#### V5. Schedule 2 — Apple Developer Program License Agreement subscription disclosure

Fetch and read https://developer.apple.com/support/terms/apple-developer-program-license-agreement/#S2 to confirm all required subscription disclosure strings are present in the paywall. (Not fetched by the 2026-05-12 audit — separate action item.)

#### V6. Age Rating

Set 4+ (no objectionable content) in App Store Connect. Document UGC scope (knitting pattern data only — symbol grids, stitch counts, yarn metadata; no freeform media uploads) in Review Notes.

---

### 1.3 Confirmed Compliant (no action needed)

| Guideline | Status |
|---|---|
| 2.5.1 — Public APIs only | Confirmed (subject to V3 binary scan) |
| 2.5.4 — Background modes | Confirmed — only standard push notification handling |
| 3.1.1 — IAP requirement | Confirmed — F1 Pro is StoreKit-only |
| 3.1.2(a) — Subscription permissible use | Confirmed — ongoing value, ≥7-day period |
| 4.5.4 — Push opt-in / opt-out | Confirmed — Phase 24.2 deferred-prompt pattern |
| 5.1.1(ii) — Analytics consent | Confirmed — Phase 39.4 opt-in screen |
| 5.1.1(iii) — Data minimization | Confirmed — no contacts/photos/location/mic/camera |
| 5.1.1(v) — Account deletion | Confirmed — Settings → Delete + cascade `delete_own_account` RPC |
| 5.1.1(ix) — Non-regulated field | Confirmed |
| 5.1.2(ii) — Purpose limitation | Confirmed |
| 5.1.3 — Health and Medical | Not applicable |
| 5.1.5 — Location Services | Not applicable |
| 5.6 — Developer conduct | Confirmed |

### 1.4 Not Applicable

1.1.2 violence, 1.1.4 pornography, 1.3 Kids, 2.3.9 spam, 3.1.3(a) Reader apps, 3.2.1 gambling, 3.2.2 unacceptable business models, 4.3 spam clones, 5.1.3 health, 5.1.5 location, 5.3 gaming/lotteries, 5.5 VPN, 5.4 VoIP, 4.2.6 XR.

---

## 2. Google Play Developer Program Policies Compliance

**Source**: Google Play Developer Policy Center (play.google/developer-content-policy/) + Data Safety section (answer/10787469) + Account deletion (answer/13327111) + Target API Level (developer.android.com/google/play/requirements/target-sdk) + UGC policy (answer/9876937) + Payments (answer/9858738) + Subscription disclosure (answer/140504) + Content rating (answer/188189) + User Data / Permissions (answer/9888170) — all fetched 2026-05-12 via docs-researcher agent.

### 2.1 Action Required (must fix before submission)

#### A5. UGC moderation features — overlaps with A1

Google Play's UGC policy is **explicit** about public platforms requiring in-app **Report user** and **Block user** mechanisms. Apple A1 covered these; Google Play makes them mandatory at the policy level. Same scope, same implementation work. Cross-link to A1.

#### A6. Data Safety form — declare 9 data types

The Data Safety form in Play Console must declare **all** data Skeinly transmits off-device. Below is the inferred declaration matrix:

| Data category | Data type | Purpose | Collection optional? | Encrypted in transit? |
|---|---|---|---|---|
| Personal info | Email address | Account management | Required for signup | Yes (HTTPS) |
| Personal info | Name (display name) | Account management + UGC attribution | Required for signup | Yes |
| Financial info | Purchase history (RevenueCat) | App functionality (subscription state) | Optional (only for Pro subscribers) | Yes |
| App activity | Page views, taps, sessions (PostHog) | Analytics | **Optional (opt-in via consent screen)** | Yes |
| App info and performance | Crash logs (Sentry) | Analytics | **Optional (opt-in via consent screen)** | Yes |
| Device or other IDs | FCM device token | App functionality (push notifications) | Optional (only after push permission grant) | Yes |
| Device or other IDs | User ID (Supabase UUID) | Account management | Required | Yes |
| Files and docs | Chart images / pattern data (UGC) | App functionality | Optional (only when sharing to Discovery) | Yes |
| Personal info | Bug report content (title + body) | App functionality (support) | Optional (only when user submits) | Yes |

**Service providers vs sharing**: Sentry / PostHog / RevenueCat / GitHub (bug report proxy) are likely **service providers** processing data on Skeinly's behalf, NOT third-party sharing under Play's definition. Need to **confirm DPA classification** for each before submitting the form.

**Action**:
1. Confirm DPA / processor agreements with Sentry / PostHog / RevenueCat / GitHub.
2. Complete Play Console → App content → Data safety form with the matrix above.
3. Have legal review the matrix once finalized.

**Owner**: technical-writer + product-manager.

---

#### A7. Account Deletion URL — register in Play Console Data Safety

Play Console **Data Safety → Account deletion** field requires a working deletion URL. Apple does not require this URL (Apple uses the in-app path; the URL field is Google Play-specific).

**Options**:
- (a) URL deep-links into the app's Settings → Delete Account screen (via App Link if assetlinks.json is configured).
- (b) Web-based deletion form at e.g. `https://skeinly.app/delete-account` that authenticates the user and calls Supabase `delete_own_account` RPC server-side. Required if user does NOT have the app installed but wants to delete (e.g., uninstalled but signed up).

**Recommendation**: Option (b) is policy-safer because Google specifically wants a path for users WITHOUT the app installed. Build a minimal web form at `docs/public/account-deletion/` that authenticates via Supabase Auth and triggers deletion.

**Action**: Implement web-based deletion form. Register URL in Play Console.

**Owner**: architect + technical-writer.

---

#### A8. Subscription disclosure UI — overlaps with A3

Google Play requires:
- Price + billing cycle + free trial terms shown **on paywall screen before purchase** (not just StoreKit-equivalent system sheet).
- Cancellation instructions accessible **within the app** (Settings → Subscription → Manage with link to Play Store subscription management).

Same audit as Apple A3 plus the **in-app cancellation path** specific to Play. The cancellation must deep-link to `https://play.google.com/store/account/subscriptions?sku=<product_id>&package=<package_name>` or equivalent.

**Action**: Audit Settings → Subscription Management deep link on Android. Add if missing.

**Owner**: ui-ux-designer + implementer.

---

### 2.2 Needs Verification

#### V7. Target Audience = Adults (avoid Families / COPPA triggers)

In Play Console → App content → Target audience and content, **explicitly select adults only** or "Teens and adults" (NOT "Children under 13"). Selecting any child age band triggers Designed for Families (DFF) requirements: COPPA compliance, child-directed ad restrictions (Skeinly has no ads, but the policy framework still applies), no behavioral advertising.

**Action**: Confirm target audience declaration at submission time.

#### V8. IARC content rating questionnaire

Complete via Play Console → App content → Content rating. Target rating: **Everyone**. UGC questionnaire requires truthful answers — UGC moderation (A5) must be in place before IARC submission to allow honest "Yes, we moderate UGC and provide report/block mechanisms" answer.

#### V9. Privacy Policy in-app link

Confirm Settings contains a tappable Privacy Policy link (not just store listing). Same as V1 from Apple.

#### V10. assetlinks.json — only if App Links verification is used

Skeinly Phase 24.5 uses intent-based push routing (not https:// URL interception). If no App Links are configured for https URLs, `/.well-known/assetlinks.json` is not required.

**Action**: Confirm push routing scheme. If only intent extras are used (no https interception), this section is N/A. If https App Links are added later, deploy `assetlinks.json` at the developer domain.

#### V11. Subscription cancellation deep link

Add Settings → Subscription Management with link to:
`https://play.google.com/store/account/subscriptions?sku=<product_id>&package=io.github.b150005.skeinly`

For iOS, the equivalent is `https://apps.apple.com/account/subscriptions`. Add both deep links to the existing `OsSettingsLauncher` or a dedicated `SubscriptionManagementLauncher` `expect/actual`.

**Owner**: implementer.

---

### 2.3 Confirmed Compliant

| Policy | Status |
|---|---|
| 1.1 Payments — Play Billing mandatory | Confirmed via RevenueCat |
| 1.6 Target API Level 35+ | Confirmed (Skeinly targets API 36) |
| 1.5 Account deletion — in-app path | Confirmed (Phase 17 / ADR-005) |
| 1.8 Permissions — POST_NOTIFICATIONS only, contextual request | Confirmed (Phase 24.2 deferred-prompt) |
| 1.8 No FOREGROUND_SERVICE / accessibility / background location | Confirmed |
| 2.4 MUwS — not applicable | Confirmed |

### 2.4 Not Applicable

Real-money gambling, background location, foreground services, accessibility services, loot boxes, ads, alternative billing, financial services, dangerous products, hate / sexual content production.

### 2.5 Open Question

Families Policy (answer/9899712 returned 404 at audit time). Mitigation: explicit "Adults" target audience declaration (V7) sidesteps the entire policy area.

AI-generated content: Skeinly currently has no generative AI. If future phases add AI-assisted pattern suggestions, re-audit.

## 3. Supabase RLS Policy + Security Advisor Audit

**Source**: `mcp__supabase__list_tables` + `pg_policies` query + `mcp__supabase__get_advisors(security)` against prod, 2026-05-12.

### 3.1 Confirmed Compliant

- **All 20 public tables have RLS enabled** ✅.
- **Personal data tables** (`device_tokens`, `feedback`, `subscriptions`, `user_symbol_pack_state`): own-row CRUD scoped by `auth.uid() = user_id` ✅.
- **Resource-owning tables** (`patterns`, `projects`, `chart_documents`, `chart_variations`, `progress`, `project_segments`): scoped by `owner_id = auth.uid()` ✅.
- **Public-readable tables** (`symbol_packs`, `symbol_pack_locales`, `app_config`): explicit `SELECT qual: true` ✅. `app_config` SELECT-anyone is intentional for the Phase 39 W4 force-update infrastructure (must be readable pre-login).
- **`profiles` schema verified**: only `id` / `display_name` / `avatar_url` / `bio` / `created_at` — **no email / no PII** in publicly-readable columns. Broad SELECT (authenticated-only) is safe ✅.
- **`subscriptions` writes**: no INSERT/UPDATE/DELETE RLS policies — writes happen only via `upsert_subscription_from_webhook` SECURITY DEFINER RPC called by service-role (`revenuecat-webhook` Edge Function), bypassing RLS by design ✅.
- **`chart_versions`** append-only (only INSERT/SELECT) ✅ (per ADR-007).
- **`activities`** append-only (only INSERT/SELECT own) ✅.
- **`feedback`** insert-only (no UPDATE/DELETE) — immutable audit trail ✅.

### 3.2 Action Required — High Priority

#### A9. Supabase Auth: Enable HIBP leaked-password protection

**Advisor finding**: `auth_leaked_password_protection` — disabled. Supabase Auth has a built-in HIBP (HaveIBeenPwned) integration that rejects passwords appearing in breach datasets. Currently off.

**Action**: Enable via Supabase Dashboard → Auth → Policies → "Leaked password protection" toggle. No code change required.

**Owner**: devops-engineer (user-side dashboard action).

**Source**: https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection

---

#### A10. Lock `search_path` on 4 functions

**Advisor finding**: `function_search_path_mutable` on:
1. `public.handle_new_user()` — auth bootstrap trigger
2. `public.touch_subscriptions_updated_at()` — migration 023
3. `public.update_updated_at()` — generic touch trigger
4. `public.set_progress_owner_id()` — progress bootstrap trigger

**Risk**: Without `SET search_path = public, pg_temp`, an attacker who can control the schema search path (via session-level config or schema injection) can hijack function calls inside the function body, executing arbitrary code as the function definer.

**Action**: Create migration that ALTERs each function to add `SET search_path = public, pg_temp`. Phase 24.1 already did this for `touch_device_tokens_updated_at` — follow that pattern.

**Owner**: architect / implementer.

**Source**: https://supabase.com/docs/guides/database/database-linter?lint=0011_function_search_path_mutable

---

#### A11. Revoke EXECUTE on internal SECURITY DEFINER functions from `anon` + `authenticated`

**Advisor finding**: 9 SECURITY DEFINER functions are exposed via `/rest/v1/rpc/<name>` to anon + authenticated roles. Many of these are internal triggers or admin-only and should NOT be RPC-callable.

| Function | Intended caller | Action |
|---|---|---|
| `apply_suggestion(...)` | authenticated user (PR target owner) | **Keep authenticated, revoke anon**. Internal RLS check exists inside the function. |
| `delete_own_account()` | authenticated user (self) | **Keep authenticated, revoke anon**. |
| `get_app_config()` | anyone (force-update needs to work pre-login) | **Keep anon + authenticated** — INTENTIONAL per Phase 39 W4. Document. |
| `is_pro(uid uuid)` | authenticated user (own check) + service-role (Edge Function) | **Keep authenticated, revoke anon**. |
| `grant_alpha_pro(uid uuid)` | service-role / admin only | **Revoke EXECUTE from anon AND authenticated**. |
| `handle_new_user()` | trigger ONLY, never as RPC | **Revoke RPC EXECUTE from anon AND authenticated**. |
| `rls_auto_enable()` | internal admin / one-shot bootstrap | **Revoke RPC EXECUTE from anon AND authenticated**. |
| `set_progress_owner_id()` | trigger ONLY | **Revoke RPC EXECUTE from anon AND authenticated**. |
| `touch_app_config_updated_at()` | trigger ONLY | **Revoke RPC EXECUTE from anon AND authenticated**. |
| `upsert_subscription_from_webhook(...)` | service-role only (revenuecat-webhook Edge Function) | **Revoke EXECUTE from anon AND authenticated**. Most security-critical revoke. |

**Action**: Single migration `REVOKE EXECUTE ON FUNCTION ... FROM anon, authenticated` (or just `anon`) per row above. Document each revocation rationale in the migration comment.

**Owner**: architect / implementer.

**Source**: https://supabase.com/docs/guides/database/database-linter?lint=0028_anon_security_definer_function_executable + 0029

---

#### A12. Tighten `comments` SELECT policy — token-shared project leakage

**Finding**: The `comments` SELECT policy's project-share clause uses:
```sql
EXISTS (SELECT 1 FROM projects pr JOIN shares s ON s.pattern_id = pr.pattern_id
        WHERE pr.id = comments.target_id
          AND comments.target_type = 'project'
          AND (s.to_user_id = auth.uid() OR s.share_token IS NOT NULL))
```

The `s.share_token IS NOT NULL` arm grants comment-read access to **any authenticated user** when a token-share exists on the underlying pattern — regardless of whether that user actually holds the token. Token-based share access should require the user to **present** the token (e.g., via a function parameter that the client passes), not just for a token-share to exist.

**Action**: Refactor the policy to remove the bare `share_token IS NOT NULL` arm. Replace with a function-based check that requires the caller to present the token. Alternatively, accept that token-shared content is "anyone authenticated can read if they know about the token mechanism" but explicitly document this in the share UX.

**Owner**: architect.

---

#### A13. Tighten `avatars` storage bucket SELECT policy — listing exposure

**Advisor finding**: `public_bucket_allows_listing` on `storage.objects` for `avatars` bucket — broad SELECT policy "Anyone can read avatars" allows listing all files. Public buckets serve files via direct URL; listing exposes more data than intended.

**Action**: Restrict SELECT to specific object paths (e.g., own-avatar by file naming convention `<user_id>/avatar.jpg`) OR rely on URL knowledge only (signed URLs / known paths).

**Owner**: architect.

**Source**: https://supabase.com/docs/guides/database/database-linter?lint=0025_public_bucket_allows_listing

---

### 3.3 Needs Verification

#### V12. `shares.share_token` entropy

`share_token` is `text NOT NULL = NO` (nullable), no DB-level default → client-side generated. The token-based access pattern's security entirely depends on token unguessability.

**Action**: Audit the Kotlin / Swift code that generates `share_token` to confirm:
- Uses a cryptographically secure RNG (`SecureRandom` on Android, `SecRandomCopyBytes` on iOS).
- Entropy ≥ 128 bits (e.g., 22+ char base64url, or UUIDv4).
- Token is never logged, never embedded in URLs visible in logs.

### 3.4 Closure of original Item 4 (SECURITY DEFINER search_path)

Action A10 above covers the entire Item 4 scope. Closed against Item 3.

---

## 4. (folded into Section 3)

See Section 3.2 A10. No standalone audit required.

## 5. Edge Function Rate Limiting / CORS / `verify_jwt` Audit

**Source**: `supabase/config.toml` + `grep -n verify_jwt|cors|rate.limit supabase/functions/*/index.ts`, 2026-05-12.

### 5.1 Inventory (4 Edge Functions)

| Function | `verify_jwt` | Auth method | Rate limit | CORS Origin |
|---|---|---|---|---|
| `revenuecat-webhook` | `false` | `Authorization: Bearer <RC_WEBHOOK_SECRET>` (constant-time compare) | **None** — intentional | `*` |
| `notify-on-write` | `false` | `Authorization: Bearer <DB_WEBHOOK_SECRET>` (constant-time compare) | **None** — intentional | `*` |
| `submit-bug-report` | `false` | `apikey: <publishable_key>` (rate-limit seed only — NOT authentication; per ADR-020 §Q4 the function is intentionally unauthenticated at the edge layer, authentication lives downstream at the GitHub App call) | ✅ 5 reports/hour per source hash (in-memory `Map`, IP-derived hash) | `*` |
| `request-pack-download` | `true` | Supabase JWT (authenticated user) | ✅ 10 calls/60s per user (sliding window) | `*` |

### 5.2 Confirmed Compliant

- **revenuecat-webhook + notify-on-write**: server-to-server only. Bearer secret (32-byte hex) provides authentication. Rate limit absence is intentional — the threat model is "compromised secret enables rapid spam", which rate limiting cannot meaningfully mitigate. Secret rotation cadence (per `ops/secrets-rotation.md`) is the relevant control.
- **submit-bug-report**: mobile-app-callable with 5/hour per source hash. Acceptable for closed-alpha scale (5–10 testers × 5 reports = 25/hour max). Hash is derived from `apikey` header (or `authorization` fallback), so a single tester's spam is bounded.
- **request-pack-download**: authenticated callers with 10/60s per user (sliding window). Sufficient for legitimate symbol pack download UX.
- **CORS `*` on all 4**: acceptable because (a) server-to-server functions don't care about browser origins, (b) mobile-app functions are called by non-browser native code where CORS doesn't apply.

### 5.3 Open Questions

- **In-memory rate limit per Deno isolate**: `submit-bug-report`'s `rateLimitMap: Map<string, RateWindow>` lives in the isolate's memory. Supabase Edge Functions spin up multiple isolates per region; an attacker hitting different isolates could bypass the 5/hour bound. Acceptable for alpha-scale; revisit if abuse surfaces. Alternative: move to Supabase table-backed rate limiting.
- **`request-pack-download`** rate limit storage: confirm if it's also in-memory or DB-backed.

### 5.4 Action Required

None for alpha. All 4 functions have appropriate auth + rate limit posture for the alpha scale.

---

## 6. Edge Function CORS — see Section 5

Folded. CORS `*` is the appropriate posture for Skeinly's use cases.

## 7. CodeQL + Dependency Vulnerability Audit

**Source**: `gh api repos/b150005/skeinly/code-scanning/alerts` against prod main, 2026-05-12.

### 7.1 Open Alerts

| # | Severity | Rule | File / Line | Status |
|---|---|---|---|---|
| 1 | HIGH | `java/android/implicit-pendingintents` | [SkeinlyMessagingService.kt:120](../../androidApp/src/main/kotlin/io/github/b150005/skeinly/notifications/SkeinlyMessagingService.kt) | **Likely false positive — triage** |

### 7.2 Analysis of Alert #1

The flagged code at lines 78–92 constructs the PendingIntent as:
```kotlin
val tapIntent = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    if (!route.isNullOrBlank()) {
        putExtra(MainActivity.EXTRA_PUSH_ROUTE, route)
    }
}
val pendingIntent = PendingIntent.getActivity(
    context,
    NEXT_REQUEST_CODE.getAndIncrement(),
    tapIntent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)
```

- ✅ **Explicit Intent**: target component is `MainActivity::class.java` — not implicit (no action string, no implicit URL).
- ✅ **`FLAG_IMMUTABLE`** set — prevents modification.
- ✅ `FLAG_UPDATE_CURRENT` is the standard pattern for tap-routed notifications.

CodeQL's `java/android/implicit-pendingintents` rule should NOT flag explicit PendingIntents with `FLAG_IMMUTABLE`. The alert is **likely a false positive** triggered by the rule's flow analysis incorrectly following the PendingIntent through `setContentIntent(pendingIntent)` into the `builder.build()` line and treating the broader notification creation as the offending site.

### 7.3 Action

1. **Triage Alert #1** in GitHub Security UI: dismiss with reason "False positive — explicit Intent with FLAG_IMMUTABLE per Android best practice" and document the dismissal rationale.
2. **OR** add a CodeQL suppression comment in the source file if dismissals do not survive subsequent scans.
3. **Document** in `docs/en/ops/repo-policy.md` (CI Known Limitations section) so this finding does not repeatedly surface as a new "issue."

**Owner**: security-reviewer + devops-engineer.

### 7.4 Dependency Vulnerability Audit

**Status**: TODO — separate item, see Section 8.

---

## 8. Dependency Vulnerability Audit (Gradle / SwiftPM / Deno)

**Status**: TODO.

## 7. Token Storage Verification (Keychain + EncryptedSharedPreferences)

**Status**: TODO.

## 8. Supabase Auth Configuration

**Status**: TODO — password policy, login rate limiting, email enumeration, session timeout, refresh token rotation.

## 9. R8 / ProGuard / iOS Symbol Stripping

**Status**: TODO.

## 10. App Transport Security + Network Security Config

**Status**: TODO.

## 11. Security Decisions (Cert Pinning / Jailbreak / Biometric / AppCheck / CAPTCHA)

**Status**: TODO — document explicit decisions with threat-model rationale.

## 12. GitHub Actions / Repository Protection Audit

**Source**: `gh api repos/b150005/skeinly/rulesets` + `.github/workflows/*.yml` inspection, 2026-05-12.

### 12.1 Repository protection ruleset

- `main-strict` (id 15581036) — `enforcement: active`, last updated 2026-05-02 ✅.

### 12.2 Workflow `permissions:` blocks (least-privilege)

| Workflow | Permissions | Notes |
|---|---|---|
| `ci.yml` | `contents: read` | ✅ Read-only |
| `dependency-review.yml` | `contents: read` + `pull-requests: write` | ✅ PR comment write only |
| `e2e.yml` | `contents: read` | ✅ |
| `pages.yml` | `contents: read` + `pages: write` + `id-token: write` | ✅ Needed for GitHub Pages OIDC |
| `release.yml` | `contents: write` | ✅ Needed for release creation |
| `security.yml` | (job-level) `security-events: write` + `actions: read` + `contents: read` | ✅ Job-scoped least-privilege |

### 12.3 Third-party action SHA pinning

| Action | Reference | SHA-pinned? |
|---|---|---|
| `actions/checkout` | `@v6` | Floating (first-party) |
| `actions/setup-java` | `@v5` | Floating (first-party) |
| `actions/upload-artifact` | `@v7` | Floating (first-party) |
| `actions/download-artifact` | `@v8` | Floating (first-party) |
| `actions/configure-pages` | `@v6` | Floating (first-party) |
| `actions/deploy-pages` | `@v5` | Floating (first-party) |
| `actions/upload-pages-artifact` | `@v5` | Floating (first-party) |
| `actions/dependency-review-action` | `@v4` | Floating (first-party) |
| `github/codeql-action/init` | `@v4` | Floating (per CLAUDE.md: tracked by dependabot for CodeQL CLI patches) |
| `github/codeql-action/analyze` | `@v4` | Floating (per CLAUDE.md policy) |
| `gradle/actions/setup-gradle` | `@50e97c2cd7a37755bbfafc9c5b7cafaece252f6e` | ✅ SHA |
| `reactivecircus/android-emulator-runner` | `@e89f39f1abbbd05b1113a29cf4db69e7540cae5a` | ✅ SHA |
| `softprops/action-gh-release` | `@b4309332981a82ec1c5618f44dd2e27cc8bfbfda` | ✅ SHA |
| `ruby/setup-ruby` | `@c4e5b1316158f92e3d49443a9d58b31d25ac0f8f` | ✅ SHA |

**Verdict**: ALL third-party (non-`actions/*`, non-`github/*`) actions are SHA-pinned ✅. First-party `actions/*` and `github/*` use floating tags by deliberate policy (dependabot tracks them; CodeQL CLI patches require floating ref).

### 12.4 Confirmed Compliant

Repository protection + workflow permissions + 3rd-party SHA pinning all satisfy alpha-launch security posture.

### 12.5 Action Required

None.

---

## 13. Secret Rotation Cadence

**Source**: `docs/en/ops/secrets-rotation.md` (header inspection 2026-05-12).

### 13.1 Confirmed Compliant

- Comprehensive runbook exists with **per-secret cadence** for every credential:
  - **Annual rotation**: distribution cert, APP_STORE_CONNECT_API_KEY, GOOGLE_PLAY_PUBLISHER_SA, APPLE_APNS_KEY, FIREBASE_SERVICE_ACCOUNT_JSON, REVENUECAT_WEBHOOK_SECRET, SKEINLY_DATABASE_WEBHOOK_SECRET, SKEINLY_BUGREPORT_PRIVATE_KEY_PEM, SENTRY_AUTH_TOKEN.
  - **On-incident only**: SUPABASE_PUBLISHABLE_KEY, SENTRY_DSN, POSTHOG_PROJECT_API_KEY, REVENUECAT_API_KEY.
  - **Never** (immutable): APPLE_TEAM_ID, KEYSTORE (signing key — never rotate), SKEINLY_BUGREPORT_APP_ID, SKEINLY_BUGREPORT_INSTALLATION_ID.
- Each procedure includes the exact `gh secret set` / `supabase secrets set` commands + verification steps.

### 13.2 Action Required (operational)

- **Pre-alpha**: NO action required (runbook is complete).
- **Post-alpha**: schedule annual hygiene rotation — first occurrence one year after alpha launch (Phase 40 GA + 1 year). Add calendar reminder + assign Owner.
- **Owner assignment**: doc currently does not name the owner of each rotation. Recommend assigning a single "secret rotation owner" role (e.g., devops-engineer or Owner) and naming them in the runbook header.

## 14. User Identifier Anonymization

**Status**: TODO.

## 15. Kill Switch / Maintenance Mode

**Status**: TODO — design + ADR + implementation pending.

## 16. Force Update Version Gate

**Status**: TODO.

## 17. Deep Link / Universal Link / App Link Verification

**Status**: TODO.

## 18. Supabase PITR + DR Drill

**Status**: TODO.

## 19. Migration Rollback Procedure

**Status**: TODO.

## 20. Privacy Policy + ToS + PII Review

**Status**: TODO.

## 21. Subprocessor List

**Status**: TODO.

## 22. GDPR / CCPA Data Portability

**Status**: TODO.

## 23. Data Retention Policy

**Status**: TODO.

## 24. App Store Privacy Nutrition Label

**Status**: TODO.

## 25. Google Play Data Safety Form

**Status**: TODO.

## 26. DMCA Takedown Procedure

**Status**: TODO — see Section 1.1 A4 above.

## 27. Account Deletion End-to-End Verification

**Status**: TODO.

## 28. Accessibility Audits (TalkBack / VoiceOver / Dynamic Type / Dark Mode / Reduce Motion / WCAG AA / Touch Targets / Focus Order / States)

**Status**: TODO — 7 sub-items.

## 29. Sentry / ANR / Performance Monitoring

**Status**: TODO.

## 30. App Store Connect + Play Console Listing

**Status**: TODO — screenshots EN+JA, descriptions, keywords, Age Rating, IARC.

## 31. Subscription Disclosure / Family Sharing / Restore Purchases

**Status**: TODO — partially covered in Section 1.1 A3.

## 32. Phased Rollout Strategy

**Status**: TODO.

## 33. Stitch Symbol Catalog Review (knitter agent)

**Status**: TODO.

## 34. Terminology Layperson Check (key surfaces)

**Status**: TODO.

## 35. Open Source Attribution Screen

**Status**: TODO.

## 36. Support Contact Channel

**Status**: TODO.

## 37. Help / FAQ

**Status**: TODO.

## 38. Golden Path Verification

**Status**: TODO — pending user prioritization for which features to verify first.

---

## Revision history

- 2026-05-12 — Initial creation. Section 1 (App Store Review Guidelines) audit complete via docs-researcher agent. Sections 2–38 placeholders awaiting per-item processing.
