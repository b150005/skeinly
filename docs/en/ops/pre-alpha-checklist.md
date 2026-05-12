# Pre-Alpha Release Checklist

Tracker for the closed-alpha launch readiness audit. Every item below maps to a TODO in the active development session and progresses through Open → In Progress → Confirmed / Action Required.

## Completed Action Items (as of 2026-05-12)

| Action | Commit | Summary |
|---|---|---|
| **A14** Encrypt Supabase session tokens at rest | [7712136](https://github.com/b150005/skeinly/commit/7712136) | iOS `KeychainSettings` + Android `EncryptedSharedPreferences` via Koin `named("auth")` Settings; `SettingsSessionManager` wired in `SupabaseModule`. Existing 2 prod users re-authenticate on first launch per pre-v1 breaking-change policy. |
| **A15** Kill Switch / Maintenance mode | [e1e5b66](https://github.com/b150005/skeinly/commit/e1e5b66) | Migration 029 adds `maintenance_mode_active` + `maintenance_message_{en,ja}` to `public.app_config`. New `MaintenanceScreen` Composable; `ForceUpdateRequirement` renamed to `AppGateRequirement` with new `MaintenanceMode` variant that takes priority over `UpdateRequired`. |
| **A19** ToS DMCA safe-harbor (Apple 5.2 / Play UGC) | [e3dca48](https://github.com/b150005/skeinly/commit/e3dca48) | ToS §6.5 with 17 U.S.C. § 512 elements + counter-notification + repeat-infringer policy + designated agent (skeinly.app@gmail.com). EN + JA. |
| **A19a** Privacy Policy subprocessor table | [e3dca48](https://github.com/b150005/skeinly/commit/e3dca48) | 8-row subprocessor disclosure (Supabase, RevenueCat, Sentry, PostHog, Apple APNs, Google FCM, GitHub, Apple/Google IAP) replacing the prior single-line reference. EN + JA. |
| **A21** Data retention table | [e3dca48](https://github.com/b150005/skeinly/commit/e3dca48) | Per-data-type retention periods + deletion triggers (9 rows). EN + JA. |
| **A30** Subscription management deep link | [2a5e74b](https://github.com/b150005/skeinly/commit/2a5e74b) | `SubscriptionManagementLauncher` expect/actual; iOS opens `https://apps.apple.com/account/subscriptions`, Android opens `market://account/subscriptions?package=...` with web fallback. Settings → Manage Subscription row in both Compose + SwiftUI. |
| **A34** In-app Contact Support entry | [a8a94df](https://github.com/b150005/skeinly/commit/a8a94df) | `SupportContactLauncher` expect/actual + pure `composeSupportMailtoUrl()` URL composer with RFC 6068-compliant percent encoding. Settings → Contact Support row opens mailto: with diagnostic context (app version / OS / device / locale) pre-filled. +13 commonTest. |
| **A35** Help / FAQ pages + Settings link | [d6f07db](https://github.com/b150005/skeinly/commit/d6f07db) | New `docs/public/help/index.html` (EN + JA) with 10+ Q&A covering pattern creation, sharing, projects, Suggestions, Pro subscribe/cancel, account deletion, bug reporting, push permission, locale propagation, offline mode, language switch. Settings → Help & FAQ link in both Compose + SwiftUI. |
| **A10** Lock `search_path` on 4 SECURITY DEFINER + trigger functions | Migration 030 | `ALTER FUNCTION ... SET search_path = ''` on `handle_new_user`, `set_progress_owner_id`, `touch_subscriptions_updated_at`, `update_updated_at`. Supabase lint 0011 (`function_search_path_mutable`) cleared (4 → 0 advisor findings). |
| **A11** Revoke EXECUTE on internal SECURITY DEFINER functions | Migration 030 | Three-tier matrix: (a) `get_app_config` keeps anon + authenticated (intentional pre-sign-in surface); (b) `apply_suggestion` / `delete_own_account` / `is_pro` revoked from anon, kept on authenticated; (c) `grant_alpha_pro` / `handle_new_user` / `rls_auto_enable` / `set_progress_owner_id` / `touch_app_config_updated_at` / `upsert_subscription_from_webhook` revoked from anon + authenticated + PUBLIC. Supabase lints 0028 / 0029 reduced from 18 → 5 advisor findings (all 5 remaining intentional). |
| **A12** Tighten `comments` SELECT policy | Migration 030 | Dropped the bare `s.share_token IS NOT NULL` arm; token-shared project comments now require the caller to be the explicit `s.to_user_id`. Token-based viewers (anonymous-link recipients) no longer see comments, matching the read-only "view this pattern" UX. |
| **A13** Tighten `avatars` storage bucket SELECT policy | Migration 030 | Dropped broad `Anyone can read avatars` SELECT policy on `storage.objects`. The avatars bucket is `public = true`, so the Storage HTTP API continues to serve files via URL without an RLS policy. Owner upload/update/delete policies retained (scoped by `auth.uid()::text = (storage.foldername(name))[1]`). Supabase lint 0025 (`public_bucket_allows_listing`) cleared. App code does not call `.list()` on this bucket. |
| **A18** Migration rollback procedure doc | docs commit | New `docs/en/ops/migration-rollback.md` (+ JA mirror) covering: forward-only principle, destructive-migration matrix with recovery paths (DROP TABLE / DROP COLUMN / lossy ALTER / DROP FUNCTION / REVOKE / RLS DISABLE), pre-migration safety discipline (BREAKING tag + inline rollback plan + low-write window), drill procedure, PITR procedure, forward-fix vs PITR decision matrix. Cross-linked from `release.md`. |
| **A22** ATT decision rationale doc | docs commit | New section in `docs/en/ops/repo-policy.md` (+ JA mirror) — App Tracking Transparency NOT required, per-subprocessor analysis (PostHog / Sentry / RevenueCat / APNs / FCM / Supabase / GitHub all confirmed non-tracking + non-data-broker), conditions for re-evaluation, reviewer-facing summary text for App Store Connect Review Notes. |
| **A20** GDPR / CCPA data portability (alpha-scope) | docs commit | New SOP `docs/en/ops/data-export-sop.md` (+ JA mirror) covering email-based fulfillment: 7-day SLA, identity verification, Supabase Dashboard CSV export of all 17 user-scoped tables, Storage avatar enumeration, out-of-scope subprocessor instructions, response templates. Satisfies GDPR Art. 20 / CCPA right-to-know for alpha tester scope. Option B (in-app "Export My Data" + Edge Function) scheduled pre-Phase-40 GA in CLAUDE.md polish list. |
| **A7** Web-based account deletion URL | docs commit | New `docs/public/account-deletion/index.html` (+ JA mirror) with Option 1 (in-app deletion recommended) + Option 2 (web fallback via mailto with identity verification + 7-day SLA) + table of what gets deleted + warning on Apple/Google subscription cancellation. Linked from Privacy Policy "Your Rights" + landing `index.html` Support section. Satisfies Apple Guideline 5.1.1(v) web-accessible deletion path + Google Play account-deletion URL requirement. |
| **A17** Supabase PITR / DR drill SOP | docs commit | New `docs/en/ops/dr-drill-sop.md` (+ JA mirror) — RTO ≤ 1 h / RPO ≤ 5 min targets, 5-scenario drill catalog (accidental DELETE / DROP TABLE / faulty migration / Dashboard compromise / RLS bug mass overwrite), quarterly drill procedure with drill log table, real-incident PITR procedure, Free-tier fallback. Distinct from A18 migration-rollback runbook (different incident class). |
| **A3 / A8** Paywall pre-purchase disclosure | code commit | 5-item Apple 3.1.2(c) audit + 3-item Play subscription policy audit confirm 4/5 + 3/3 pass. Improved item 1 (vague "full symbol library" → enumerated feature bullets: premium symbol packs, future Pro-only features, indie development support) via new `body_paywall_features` i18n key + Compose + SwiftUI render. Expanded `body_paywall_trial_disclosure` to point at Settings → Manage Subscription. Restore Purchases + ToS + Privacy links unchanged (already passing). |
| **A16** iOS Universal Links — code wired, deploy pending | code commit | `iosApp.entitlements` already declared `applinks:b150005.github.io`. Added `application(_:continue:restorationHandler:)` to `AppDelegate.swift` that extracts the URL path via new `extractUniversalLinkRoute(from:)` helper and routes through the existing `.openPushRoute` notification (same SwiftUI consumer as APNs taps). AASA file template under `docs/well-known/apple-app-site-association` with `TEAMID_PLACEHOLDER` to fill at deploy time. `docs/well-known/README.md` documents the 3 deploy targets (custom domain / User Pages repo / external PaaS) with recommendation = User Pages repo for alpha. **User-side actions remaining**: pick deploy target, deploy AASA file, regenerate Distribution Provisioning Profile with Associated Domains capability. |
| **A33** OSS attribution screen | code commit | New `docs/public/licenses/index.html` (+ JA mirror) enumerating 21 OSS dependencies grouped by stack (KMP+Compose / Networking+Persistence / DI+Nav+Image / Security+Push+IAP / Observability / Test-only) with version + license link. New Settings → "Open Source Licenses" entry on Compose + SwiftUI opens the page in system browser. New `action_open_source_licenses` i18n key. Manual maintenance for alpha; AboutLibraries Gradle plugin upgrade scheduled pre-Phase-40 GA in CLAUDE.md polish list. |

## Outstanding Action Required Items

- **A1 / A5** UGC moderation (Report content + Block user + filter) — big scope, multiple sub-slices
- **A2** Review demo account + reproduction docs — user-side seed data + App Store / Play Console wiring
- **A4** Symbol catalog JIS provenance audit — knitter agent walk-through
- **A6** Play Console Data Safety form — user-side (per A6 matrix in §2.1)
- **A9 / V13** Enable HIBP leaked-password protection in Supabase Dashboard — user-side toggle
- **A23-A27** a11y audits (TalkBack/VoiceOver, Dynamic Type, Reduce Motion, color contrast, touch targets, focus order, states)
- **A28** Sentry crash-free SLO target alert
- **A29** Play Vitals + Sentry ANR alerts wire
- **A31** Knitter-agent symbol catalog correctness review
- **A32** Spot-check terminology on key surfaces
- **A36** User-prioritized golden-path verification

## Outstanding Needs Verification Items (user-side)

- **V1** Privacy Policy link accessible from within the app (in addition to App Store Connect metadata)
- **V2** ATT classification of PostHog / Sentry per current DPA
- **V3** iOS binary scan to confirm no private API selectors
- **V4** App Store Connect Review Notes content (PostHog / Sentry opt-in, bug-report proxy, BuildFlags.isBeta gating)
- **V5** Read Apple Developer Program License Agreement Schedule 2 for paywall string requirements
- **V6** Set Age Rating to 4+ in App Store Connect
- **V7** Play Console target audience = Adults (avoid Families / COPPA)
- **V8** Play Console IARC content rating questionnaire — Everyone target
- **V9** Privacy Policy in-app link verification
- **V10** Android `assetlinks.json` deployment verification — already confirmed at `https://b150005.github.io/.well-known/assetlinks.json` per Section 25.1
- **V11** Subscription cancellation deep link verification — closed by A30
- **V12** `shares.share_token` entropy audit (cryptographic RNG verification)
- **V13-V16** Supabase Auth Dashboard verifications (HIBP, password policy, rate limits, session timeout)
- **V17** Supabase Pro tier verification for PITR
- **V18** Apple Sandbox + Play License tester registration
- **V19-V21** App Store Connect + Play Console listing setup + demo account
- **V22** Family Sharing enable on Pro subscription

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

#### A10. Lock `search_path` on 4 functions — ✅ CLOSED (migration 030, 2026-05-12)

**Advisor finding**: `function_search_path_mutable` on:
1. `public.handle_new_user()` — auth bootstrap trigger
2. `public.touch_subscriptions_updated_at()` — migration 023
3. `public.update_updated_at()` — generic touch trigger
4. `public.set_progress_owner_id()` — progress bootstrap trigger

**Risk**: Without `SET search_path`, an attacker who can control the schema search path (via session-level config or schema injection) can hijack function calls inside the function body, executing arbitrary code as the function definer.

**Resolution**: Migration `030_pre_alpha_security_hardening.sql` applied `ALTER FUNCTION ... SET search_path = ''` to all four functions. Empty-string search_path is the strictest configuration — every identifier must be schema-qualified. All four function bodies either reference no schema objects (just `now()` from the always-implicit `pg_catalog`) or already fully qualify their references (`public.profiles`, `public.projects`), so the strict setting is safe. Advisor scan after apply: 4 → 0 `function_search_path_mutable` findings.

**Source**: https://supabase.com/docs/guides/database/database-linter?lint=0011_function_search_path_mutable

---

#### A11. Revoke EXECUTE on internal SECURITY DEFINER functions — ✅ CLOSED (migration 030, 2026-05-12)

**Advisor finding**: 9 SECURITY DEFINER functions exposed via `/rest/v1/rpc/<name>` to anon + authenticated roles. Many internal triggers / admin-only.

**Resolution**: Migration `030_pre_alpha_security_hardening.sql` applied the three-tier matrix below.

| Function | Resolution applied |
|---|---|
| `apply_suggestion(uuid, text, jsonb, text, uuid)` | `REVOKE EXECUTE FROM anon` — authenticated retains (Phase 38 PR apply). |
| `delete_own_account()` | `REVOKE EXECUTE FROM anon` — authenticated retains (ADR-005). |
| `get_app_config()` | **No revoke** — INTENTIONAL public surface for the pre-sign-in force-update gate (Phase 39 W4). Advisor warning explicitly accepted. |
| `is_pro(uuid)` | `REVOKE EXECUTE FROM anon` — authenticated retains (RLS policy dependency for Pro symbol packs etc.). |
| `grant_alpha_pro(uuid)` | `REVOKE EXECUTE FROM anon, authenticated, PUBLIC` — admin-only. |
| `handle_new_user()` | `REVOKE EXECUTE FROM anon, authenticated, PUBLIC` — trigger only. |
| `rls_auto_enable()` | `REVOKE EXECUTE FROM anon, authenticated, PUBLIC` — internal one-shot bootstrap. |
| `set_progress_owner_id()` | `REVOKE EXECUTE FROM anon, authenticated, PUBLIC` — trigger only. |
| `touch_app_config_updated_at()` | `REVOKE EXECUTE FROM anon, authenticated, PUBLIC` — trigger only. |
| `upsert_subscription_from_webhook(...)` | `REVOKE EXECUTE FROM anon, authenticated, PUBLIC` — service-role-only (revenuecat-webhook Edge Function). Most security-critical revoke. |

Trigger functions retain firing semantics without EXECUTE — Postgres invokes them as part of the table operation regardless of caller grants. Webhook RPCs run under `service_role` which bypasses ACL checks.

Advisor scan after apply: 18 SD-exposure findings (9 anon + 9 authenticated) → 5 (`get_app_config` anon + 4 intentional authenticated-keepers: `apply_suggestion` / `delete_own_account` / `get_app_config` / `is_pro`). All 5 remaining findings are deliberate per the matrix.

**Source**: https://supabase.com/docs/guides/database/database-linter?lint=0028_anon_security_definer_function_executable + 0029

---

#### A12. Tighten `comments` SELECT policy — token-shared project leakage — ✅ CLOSED (migration 030, 2026-05-12)

**Finding**: The `comments` SELECT policy's project-share clause used:
```sql
EXISTS (SELECT 1 FROM projects pr JOIN shares s ON s.pattern_id = pr.pattern_id
        WHERE pr.id = comments.target_id
          AND comments.target_type = 'project'
          AND (s.to_user_id = auth.uid() OR s.share_token IS NOT NULL))
```

The `s.share_token IS NOT NULL` arm granted comment-read access to **any authenticated user** when a token-share existed on the underlying pattern — regardless of whether that user actually held the token.

**Resolution**: Migration `030_pre_alpha_security_hardening.sql` recreated the policy with the share-recipient arm tightened to `s.to_user_id = auth.uid()` only. The bare `share_token IS NOT NULL` clause was deleted. Token-based share viewers (anonymous-link recipients on the read-only "view this pattern" UX) now correctly do not see comments — only the explicit share recipient does. This matches the share-flow UX where token-share is read-only viewing, not engagement.

**Source**: manual RLS audit (no Supabase advisor lint for this semantic).

---

#### A13. Tighten `avatars` storage bucket SELECT policy — listing exposure — ✅ CLOSED (migration 030, 2026-05-12)

**Advisor finding**: `public_bucket_allows_listing` on `storage.objects` for `avatars` bucket — broad SELECT policy "Anyone can read avatars" allowed listing all files.

**Resolution**: Migration `030_pre_alpha_security_hardening.sql` dropped the `Anyone can read avatars` SELECT policy. The avatars bucket has `public = true`, so the Storage HTTP API continues to serve files via the public-URL code path without consulting RLS. Owner upload / update / delete policies retained — all scoped by `auth.uid()::text = (storage.foldername(name))[1]`. App code does not call `.list()` on this bucket (verified via grep across `shared/`, `androidApp/`, `iosApp/` — only `upload` / `createSignedUrl` / `delete` / `publicUrl` are used). Advisor scan after apply: 1 → 0 `public_bucket_allows_listing` findings.

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

## 9. Token Storage Verification (Keychain + EncryptedSharedPreferences)

**Source**: `shared/src/{iosMain,androidMain}/kotlin/.../di/PlatformModule.{ios,android}.kt` + `shared/src/commonMain/.../di/SupabaseModule.kt` + supabase-kt 3.x source via Context7, 2026-05-12.

### 9.1 Critical Finding — Auth tokens stored UNENCRYPTED

**Severity**: HIGH (blocks GA, should fix before alpha for best-outcome posture per user policy).

The `PlatformModule.ios.kt` and `PlatformModule.android.kt` register an unencrypted `Settings` for general app-prefs use (`skeinly_prefs`):

```kotlin
// iOS:
single<Settings> { NSUserDefaultsSettings.Factory().create("skeinly_prefs") }
// Comment: "Use Keychain for auth tokens and user PII."

// Android:
single<Settings> { SharedPreferencesSettings.Factory(get()).create("skeinly_prefs") }
// Comment: "Use EncryptedSharedPreferences for auth tokens and user PII."
```

The comments are **aspirational** — they state the secure default, but the **actual Supabase Auth config in `SupabaseModule.kt`** never overrides the default SessionManager:

```kotlin
install(Auth) {
    flowType = FlowType.PKCE
    // NO `sessionManager = ...` line → uses default
}
```

Per supabase-kt 3.6.0 source (verified via Context7), the default is `SettingsSessionManager(createDefaultSettings())` which on:
- **Android**: `PreferenceManager.getDefaultSharedPreferences(context)` — **unencrypted** SharedPreferences in default `<package>_preferences` file.
- **iOS**: `NSUserDefaults.standardUserDefaults` — **unencrypted** NSUserDefaults.

**Risk**: Access tokens + refresh tokens are extractable from a rooted/jailbroken device or via USB debugging on a Developer-mode device. Modern OS-level disk encryption mitigates the data-at-rest case for non-rooted devices, but App Store / Play Store reviewers may flag this as a non-best-practice. The user explicitly named "token management" as a worry point.

### 9.2 Action Required

**A14. Wire encrypted SessionManager for Supabase Auth**

1. **iOS**: switch to `KeychainSettings` (provided by multiplatform-settings):
   ```kotlin
   // shared/src/iosMain/.../SupabaseModule.ios.kt (new actual)
   actual fun createAuthSettings(): Settings = KeychainSettings(service = "io.github.b150005.skeinly.auth")
   ```

2. **Android**: switch to `EncryptedSharedPreferences` wrapping:
   ```kotlin
   // shared/src/androidMain/.../SupabaseModule.android.kt
   actual fun createAuthSettings(context: Context): Settings {
       val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
       val prefs = EncryptedSharedPreferences.create(
           context, "skeinly_auth_secure", masterKey,
           EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
           EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
       )
       return SharedPreferencesSettings(prefs)
   }
   ```

3. **commonMain**: introduce `expect/actual` `createAuthSettings()` and wire into Supabase Auth install:
   ```kotlin
   install(Auth) {
       flowType = FlowType.PKCE
       sessionManager = SettingsSessionManager(createAuthSettings())
   }
   ```

4. **Migration**: existing users' tokens are in the unencrypted Settings. On first launch after the fix, the app should:
   - Try `SettingsSessionManager` on encrypted Settings first (empty initially).
   - If empty, read once from the old unencrypted Settings, save to encrypted, and delete from unencrypted.
   - Subsequent launches use encrypted only.

   For closed alpha (2 prod users to date), simpler approach: force re-login on first launch after the fix. Acceptable for pre-v1 (per `~/.claude/projects/.../memory/pre_v1_breaking_changes.md`).

**Owner**: implementer + security-reviewer (post-implementation review).

### 9.3 Closure

Item 9 stays open until A14 lands. Note: this is a code change with test impact (SessionManager unit tests, possibly E2E for sign-out/sign-in roundtrip).

---

## 10-12. Supabase Auth Configuration (password policy, rate limit, session timeout)

**Source**: prod env confirmed via `mcp__supabase__execute_sql` — 2 email-provider users currently exist (`auth.users`). The settings below are Dashboard-managed; MCP cannot read them directly. User-side verification required.

### 10.1 Password Policy

Default Supabase Auth password policy: **minimum 6 characters, no complexity rules, no HIBP check**. Industry baseline is ≥8 chars with HIBP check.

**Action V13** (user-side): Supabase Dashboard → Auth → Policies → set:
- Minimum password length: **12 characters** (NIST recommendation 2024+; balances usability with security)
- HIBP leaked-password protection: **ON** (already flagged in A9 above; closes both V13 and A9)
- Required password strength: configurable; recommend "Strong" (lowercase + uppercase + digit + symbol enforced, OR length-only with HIBP gate).

### 11.1 Login Rate Limiting

Supabase Auth provides built-in rate limits (5/min for failed sign-ins by default per IP) per their docs. Email enumeration via `signUp` returning different responses for existing vs non-existing emails is the default behavior — Supabase supports the `enable_signup` + email confirmation flow to mitigate.

**Action V14** (user-side): Supabase Dashboard → Auth → Rate Limits → verify defaults are appropriate:
- Sign-in: 5/min/IP (default; OK)
- Sign-up: 5/hour/IP (default; OK for closed alpha; revisit if abuse surfaces)
- Email confirmation: 4/hour/email (default; OK)
- Password reset: 4/hour/email (default; OK)

**Action V15** (user-side): Dashboard → Auth → Settings → **enable email confirmation** (require users to verify email before sign-in works). This also mitigates email enumeration during signup (consistent "check your email" response regardless).

### 12.1 Session Timeout + Refresh Token Rotation

Supabase Auth defaults: access token TTL 3600s (1 hour), refresh token TTL 30 days, **refresh token rotation enabled by default**.

**Action V16** (user-side): Dashboard → Auth → Settings → verify:
- JWT expiry: 3600s (default OK; consider 1800s = 30min for stricter posture)
- Refresh token reuse interval: 10s (default OK — short window for retry, but stale refresh tokens are invalidated on rotation)
- Refresh token rotation: **enabled** ✅ (default)
- Inactivity timeout: not natively supported by Supabase Auth; if required, implement client-side timer that calls `auth.signOut()` after N minutes of no app activity. Recommend post-alpha if business need surfaces.

### 12.2 Confirmed Compliant (post-V13-V16)

Once V13-V16 user-side actions are completed and verified, Skeinly's auth stack will be at industry-baseline security posture.

### 12.3 Action Required Beyond V13-V16

- Implement A14 (encrypted token storage) — code change, see Section 9.
- (Post-alpha) consider inactivity timeout if business need.

## 13. R8 / ProGuard (Android) + iOS Symbol Stripping

**Source**: `androidApp/build.gradle.kts` + `androidApp/proguard-rules.pro` + `iosApp/project.yml` + `shared/build.gradle.kts`, 2026-05-12.

### 13.1 Confirmed Compliant

**Android Release build**:
- `isMinifyEnabled = true` ✅ — R8 minification enabled.
- `isShrinkResources = true` ✅ — resource shrinking enabled.
- `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")` ✅ — comprehensive ruleset.
- `proguard-rules.pro` covers: kotlinx.serialization `$$serializer`, domain models, navigation route `@Serializable` classes, Koin, Sentry, etc.

**iOS Release build**:
- `iosTarget.binaries.framework { isStatic = true }` — Shared.framework is static-linked.
- No explicit `STRIP_*` flags in `iosApp/project.yml` → Xcode applies Release-config defaults:
  - `COPY_PHASE_STRIP = YES`
  - `STRIP_INSTALLED_PRODUCT = YES`
  - `DEAD_CODE_STRIPPING = YES`
- Kotlin/Native release binary uses `-opt` (optimized) + symbol stripping by default.

### 13.2 Action Required

None for alpha. Both platforms have appropriate release-mode obfuscation/stripping.

### 13.3 Verification Recommendation (post-alpha)

- Run `bundletool dump manifest --bundle=app-release.aab` and verify that release AAB does not contain debug symbols or unstripped code.
- Run `dwarfdump --uuid` on the iOS release `.app/Skeinly` binary and verify a UUID exists (debug symbols extracted to `.dSYM` for Sentry, stripped from binary). Already wired via Sentry upload pipeline in `release.yml`.

---

## 14. App Transport Security (iOS) + Network Security Config (Android)

**Source**: `androidApp/src/main/res/xml/network_security_config.xml` + grep of `iosApp/project.yml` + `iosApp/iosApp/Info.plist`, 2026-05-12.

### 14.1 Confirmed Compliant

**Android Network Security Config**:
```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```
✅ HTTPS-only. No cleartext exceptions. No certificate pinning declared (see Section 15.1).

**iOS App Transport Security**:
- No `NSAppTransportSecurity` key found in `Info.plist` or `project.yml` → ATS defaults apply.
- ATS defaults = HTTPS-only, TLS 1.2+ minimum, forward secrecy required.
- All Skeinly endpoints are HTTPS-only by vendor default (*.supabase.co, posthog.com, sentry.io, firebase.googleapis.com, RevenueCat, Apple/Google IAP).
- **No ATS exceptions required** ✅.

### 14.2 Action Required

None for alpha.

---

## 15. Certificate Pinning + Jailbreak / Root Detection Decisions

**Source**: agent-team deliberation 2026-05-12.

### 15.1 Decision — Certificate Pinning: NOT introduced for alpha

**Threat model**: An attacker between the device and Supabase / RevenueCat / PostHog / Sentry / Firebase servers (MITM at the network layer) would need to:
1. Have a CA certificate trusted by the device (corporate MDM-installed CA, OR compromised public CA).
2. AND the user must be on a compromised network (rogue Wi-Fi, malicious proxy).

For a knitting app with no high-value financial data on-device (subscriptions are server-side state; tokens are short-lived; pattern data is non-sensitive), the threat model does **not** justify the operational cost of certificate pinning:
- Certificate rotation breaks the app for all users → emergency app update required.
- Supabase / RevenueCat / Firebase use CDN-managed certificates that rotate (potentially) at any time.
- Pinning at the right level (cert vs public key vs subject DN) is non-trivial.

**Decision**: Rely on platform ATS / NSC (HTTPS + cert chain validation by OS) and trust-store hygiene. No app-level certificate pinning.

**Re-evaluate**: post-GA if (a) high-value financial features are added (e.g., gift cards, in-app marketplace), OR (b) a public Skeinly endpoint is attacked via MITM in the wild.

### 15.2 Decision — Jailbreak / Root Detection: NOT introduced for alpha

**Threat model**: Jailbreak/root detection signals to app code that the device is in a compromised state. The reaction is typically (a) refuse to run, OR (b) refuse high-value operations.

For Skeinly:
- IAP fraud is bounded by RevenueCat's server-side receipt validation, which runs regardless of device state.
- UGC moderation (Section 1.1 A1) is server-side enforceable independent of device state.
- Pattern data has no inherent value to an attacker rooting their own device.
- Detection is **trivially bypassable** by determined attackers using Frida / Magisk / etc., so it's security theater for low-value targets.

**Decision**: NO jailbreak/root detection. Knitting app threat model does not justify it.

**Re-evaluate**: if a market-survey of knitting apps surfaces a peer feature, OR if a real attack pattern emerges.

---

## 16. Biometric Authentication Decision

**Source**: agent-team deliberation 2026-05-12.

### 16.1 Decision — NOT introduced for alpha

**Use case for biometric**: protect a sensitive in-app action with a Face ID / Touch ID / fingerprint gate.

Skeinly currently has no in-app actions sensitive enough to warrant a biometric gate:
- Sign-in: device-level lock + Supabase Auth session already protects.
- Pattern editing: low-stakes; no destructive irreversible action.
- Account deletion: already double-confirmed via dialog (per ADR-005).
- Subscription purchase: gated by StoreKit's system authentication (Face ID prompts automatically when Apple ID requires it).

Introducing biometric authentication would require:
- KMP `expect/actual` `BiometricAuthenticator` (Android `androidx.biometric` + iOS `LocalAuthentication`).
- UX flow: opt-in setting + fallback when biometric unavailable / disabled.
- ~300 LOC + a11y handling for users without biometric hardware.

**Decision**: NO biometric authentication for alpha or GA. Re-evaluate if a high-stakes feature is added (e.g., direct purchase outside StoreKit, sensitive PII fields).

---

## 17. Firebase AppCheck for FCM Endpoint Protection

**Source**: agent-team deliberation 2026-05-12.

### 17.1 Decision — NOT introduced for alpha (potentially POST-GA)

**Threat model**: AppCheck validates that FCM-bound requests originate from the genuine Skeinly app (vs. an attacker spoofing FCM API calls to a registered project).

For Skeinly's push architecture:
- Push messages are sent **from** the Edge Function `notify-on-write` **to** FCM (server → FCM).
- The mobile app **receives** FCM messages and registers a token — it does NOT call FCM directly.
- AppCheck protects the **outbound** FCM Admin SDK calls from the Edge Function — but the Edge Function is server-side with a Service Account, so AppCheck is moot there.

**For the inbound mobile app side**: AppCheck would protect against an attacker registering a fake FCM token (claiming to be Skeinly) to receive other users' push notifications. But FCM tokens are bound to (app installation, FCM SDK identity), which itself is hard to spoof without compromising the Firebase project config.

**Decision**: AppCheck is NOT needed for Skeinly's current push architecture. Re-evaluate if direct mobile-to-Firebase API calls are introduced (e.g., Firebase Storage uploads, Firestore writes from the app — both currently N/A; Skeinly uses Supabase as the backend).

---

## 18. (folded into Section 12)

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

## 21. User Identifier Anonymization

**Source**: `iosApp/iosApp/iOSApp.swift` + `androidApp/src/main/kotlin/.../SkeinlyApplication.kt` + `shared/.../BugReportPreviewViewModel.kt` + `BugReportBodyFormatter.kt`, 2026-05-12.

### 21.1 Confirmed Compliant

| Identifier surface | Identifier value | Linked to PII? | Anonymized? |
|---|---|---|---|
| **Sentry** `event.user` | NOT called — default auto-generated installation UUID per device install | NO | ✅ |
| **PostHog** `distinct_id` | NOT called via `identify()` — default device-level random UUID | NO | ✅ |
| **`device_tokens.user_id`** | Supabase `auth.users.id` (UUID, stable per user) | Server-side via `auth.users.email` JOIN, RLS prevents cross-user reads | ✅ (own-row only) |
| **`profiles.id`** | Supabase `auth.users.id` (UUID) | Email lives in `auth.users.email` not `profiles` | ✅ |
| **Supabase Auth JWT** | Contains `sub` (UUID) + `email` (in token claims, but token is private) | Token is stored client-side only; never logged | ✅ (subject to A14 — encrypted storage) |

### 21.2 Cross-system identifier linkage

The only place where one identifier surface ties to another:

**PostHog distinct_id surfaced in bug report**:
- `BugReportBodyFormatter` includes the PostHog distinct_id in the GitHub Issue body when a tester submits a bug report. Purpose: cross-reference between Sentry/PostHog dashboards and the Issue.
- This is **opt-in by definition** — the user explicitly tapped "Send report" with the body preview visible. No covert surfacing.
- PostHog distinct_id by itself is **NOT PII** — it's a random UUID per device install.

### 21.3 Action Required

None. Identifier anonymization posture is appropriate.

### 21.4 Future consideration

If post-alpha analytics needs surface a "logged-in user attribution" requirement (e.g., funnel analysis by user lifetime), wire `PostHog.identify(supabaseUid)` + `Sentry.setUser({id: supabaseUid})` on sign-in. Document explicitly in Privacy Policy + Data Safety form when this happens. Currently NOT done — preserves the strict anonymity stance.

---

## 22. CAPTCHA / Bot Prevention Decision

**Source**: agent-team deliberation 2026-05-12.

### 22.1 Decision — NOT introduced for alpha

**Threat model for closed alpha** (5-10 testers via TestFlight + Play Internal):
- TestFlight + Play Internal Testing already gate via Apple/Google account verification.
- Signup is limited to invited testers (no public signup form).
- Abuse vector: signup flooding by automated bots — not a real concern when signup is invitation-only.

**Threat model post-public-GA**: signup flooding becomes a real concern. Mitigations available without CAPTCHA:
- Supabase Auth's built-in signup rate limit (5/hour/IP — Section 11) caps the worst case.
- Email confirmation requirement (V15) requires a real email per account — bot operators must consume disposable inboxes.
- Combine the two for "good enough" anti-bot.

**Decision**: NO CAPTCHA for alpha. Re-evaluate **post-GA + 30 days** when real signup traffic data is available. If signup abuse surfaces, options in priority order:
1. Tighten Supabase Auth rate limits (Dashboard).
2. Enable Supabase Auth's hCaptcha integration (built-in, requires Dashboard config + hCaptcha account).
3. Require Google / Apple Sign-In only, disabling email-password signup (steepest UX impact, last resort).

## 23. Kill Switch / Maintenance Mode

**Source**: agent-team deliberation + inspection of `public.app_config` (Phase 39 W4 infrastructure), 2026-05-12.

### 23.1 Current State

Skeinly has Force Update infrastructure but NOT a true Kill Switch / Maintenance Mode:
- `app_config` table exists with `min_required_version_*` (Force Update) but no `maintenance_mode_active`.
- Force Update can require users to update to a newer version, but cannot **disable** the app entirely (e.g., during a server-side incident).

### 23.2 Action Required — A15. Add Maintenance Mode to `app_config`

**Design** (minimal extension):

1. **Migration NNN** — extend `public.app_config`:
   ```sql
   ALTER TABLE public.app_config
   ADD COLUMN maintenance_mode_active boolean NOT NULL DEFAULT false,
   ADD COLUMN maintenance_message_en text,
   ADD COLUMN maintenance_message_ja text;
   ```

2. **Extend `get_app_config()`** SECURITY DEFINER RPC to return the new columns. Already anon-callable per Section 3 (intentional pre-login readability).

3. **Shared module**: rename `ForceUpdateGate` → `AppConfigGate` (or keep name + extend). Check order:
   1. If `maintenance_mode_active = true` → show `MaintenanceScreen` (no Update CTA, just message + manual retry).
   2. Else if `currentVersion < min_required_version_*` → show existing `ForceUpdateScreen`.
   3. Else → proceed to normal app.

4. **New `MaintenanceScreen` Composable** (parallels `ForceUpdateScreen` UI):
   - Title: "Skeinly is temporarily unavailable" / 「Skeinly は一時的にご利用いただけません」.
   - Body: maintenance_message_{en,ja} (server-controlled).
   - Button: "Retry" (re-fetches `get_app_config()`).

5. **i18n keys** (+4):
   - `title_maintenance_mode` / `body_maintenance_mode_fallback` / `action_maintenance_retry` + the JA mirrors.

6. **Tests**: extend `AppConfigGateTest` (or equivalent) with maintenance-mode cases.

7. **Runbook** — add to `docs/en/ops/incident-playbook.md`:
   ```sql
   -- Activate maintenance mode (emergency):
   UPDATE public.app_config
   SET maintenance_mode_active = true,
       maintenance_message_en = 'We are upgrading the backend. Please try again in 10 minutes.',
       maintenance_message_ja = 'バックエンドのアップグレード中です。10 分後に再度お試しください。';

   -- Deactivate:
   UPDATE public.app_config SET maintenance_mode_active = false;
   ```

**Owner**: architect (ADR-cut if non-trivial) + implementer.

**Re-evaluate**: post-alpha if real incidents surface the need for finer granularity (e.g., per-feature maintenance gates).

---

## 24. Force Update Version Gate

**Source**: Phase 39 W4 (commits leading to 2026-05-11 wave). `app_config` table + `get_app_config()` RPC + `ForceUpdateGate` + `ForceUpdateScreen` all present in `shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/forceupdate/`.

### 24.1 Confirmed Compliant

| Component | Location |
|---|---|
| Server config table | `public.app_config` (Phase 39 W4) |
| Server RPC | `public.get_app_config()` SECURITY DEFINER, anon-callable (intentional pre-login) |
| Shared gate | `shared/.../ui/forceupdate/ForceUpdateGate.kt` |
| Shared screen | `shared/.../ui/forceupdate/ForceUpdateScreen.kt` |
| Store URL launcher | `StoreUrlLauncher` `expect/actual` (Phase 39 W4) — opens App Store / Play Store deep link |
| Test coverage | (TBD — verify via grep but Phase 39 W4 spec implies coverage exists) |

**Activation procedure** (operator side):
```sql
UPDATE public.app_config
SET min_required_version_android = '0.2.0',
    min_required_version_ios = '0.2.0',
    force_update_message_en = 'A critical security update is available. Please update Skeinly.',
    force_update_message_ja = 'セキュリティ更新があります。アプリを更新してください。';
```

Current state: `min = 0.1.0` (no force-update active since 0.1.0 is the first beta version).

### 24.2 Action Required

None — Force Update is production-ready. Maintenance Mode extension is the only outstanding work (Section 23 A15).

## 25. Deep Link / Universal Link / App Link Verification

**Source**: `androidApp/src/main/AndroidManifest.xml` + `iosApp/iosApp.entitlements` + WebFetch of deployed verification files, 2026-05-12.

### 25.1 Android App Links — Confirmed Compliant

- AndroidManifest declares an `intent-filter android:autoVerify="true"` for `https` / `b150005.github.io` / pathPrefix `/skeinly/`.
- Server-side `https://b150005.github.io/.well-known/assetlinks.json` is **deployed and valid**, contains:
  - `package_name: "io.github.b150005.skeinly"` ✅
  - `sha256_cert_fingerprints` ✅ matches the release signing key (`18:B3:0D:4F:AB:0C:42:82:63:57:...`)
- Android will auto-verify on app install and route matching https URLs into the app.

### 25.2 iOS Universal Links — NOT configured (asymmetric)

- `iosApp/iosApp.entitlements` does NOT declare `com.apple.developer.associated-domains` for `applinks:b150005.github.io`.
- No `apple-app-site-association` file at `https://b150005.github.io/.well-known/apple-app-site-association`.
- Tapping `https://b150005.github.io/skeinly/...` links on iOS opens Safari, NOT the app.

This is **asymmetric** vs Android. For "best outcome" alpha posture, iOS should match.

### 25.3 Action Required — A16. Add iOS Universal Links + path-routing logic

1. **Edit `iosApp/iosApp.entitlements`**: add the `com.apple.developer.associated-domains` array:
   ```xml
   <key>com.apple.developer.associated-domains</key>
   <array>
       <string>applinks:b150005.github.io</string>
   </array>
   ```

2. **Deploy `apple-app-site-association` file** at `docs/public/.well-known/apple-app-site-association` (no extension, served with `Content-Type: application/json`):
   ```json
   {
       "applinks": {
           "apps": [],
           "details": [
               {
                   "appID": "<TEAM_ID>.io.github.b150005.skeinly",
                   "paths": ["/skeinly/*"]
               }
           ]
       }
   }
   ```
   - Substitute `<TEAM_ID>` with Skeinly's Apple Developer Team ID (10-char alphanumeric).
   - File must be served with `Content-Type: application/json` (GitHub Pages serves `.json` files this way by default; verify served headers).

3. **Provisioning Profile**: the Distribution Provisioning Profile must include the **Associated Domains** capability on the App ID. Currently includes Push Notifications (per Phase 24.2e). Add Associated Domains via Apple Developer Portal → App ID settings, then regenerate + re-register the profile.

4. **Path routing in `AppDelegate` / `iOSApp`**: implement `application(_:continue:restorationHandler:)` to handle `NSUserActivity` of type `NSUserActivityTypeBrowsingWeb` and route to the right in-app destination. Use the same `parsePushRoute` helper that Phase 24.5 introduced (extend the path table).

5. **Path semantics**: clarify what each path under `/skeinly/*` should do:
   - `/skeinly/` → open main app
   - `/skeinly/privacy-policy/`, `/skeinly/terms-of-service/` → open in **browser**, NOT in-app (these are policy pages, not in-app screens). Android `pathPrefix="/skeinly/"` currently captures these too — need to either narrow Android path filter OR build in-app screens for the policy pages.
   - `/skeinly/pull-request/<id>` → in-app PR detail (mirrors Phase 24.5 push route)

**Owner**: implementer + technical-writer (path semantics doc) + Apple Developer admin (user-side: regenerate provisioning profile).

### 25.4 Open Question — Android `pathPrefix` is too broad

Currently `pathPrefix="/skeinly/"` captures EVERY URL under `/skeinly/`, including the privacy policy + ToS pages. Tapping `https://b150005.github.io/skeinly/privacy-policy/` on Android opens the app, not Safari. This is **incorrect UX** — policy pages should open in browser.

Mitigations:
- (a) Narrow Android `intent-filter` to specific deep-link paths only (e.g., `pathPattern="/skeinly/pull-request/.*"`).
- (b) Handle non-deep-link paths in MainActivity by opening browser via `Intent(ACTION_VIEW, externalUrl)` (fall-through pattern).
- (c) Accept current behavior, add in-app browser-style screens for policy pages.

Decision required during A16 implementation. Recommend (a) — narrowest filter, clearest semantics.

**Owner**: architect.

## 26. Supabase PITR + DR Drill

**Source**: Supabase docs (Point-in-Time Recovery) + `mcp__supabase__list_branches` (returned empty list), 2026-05-12.

### 26.1 Verification needed

Supabase tier determines backup capability:
- **Free**: daily snapshot backups, **NO PITR**.
- **Pro / Team / Enterprise**: PITR up to 7 days (Pro) / 14 days (Team) / 28 days (Enterprise).

**Action V17** (user-side): Supabase Dashboard → Project Settings → General → verify plan tier. If Free, **upgrade to Pro before alpha** ($25/month) for PITR. Cost is trivial vs. unrecoverable data loss risk during alpha.

### 26.2 DR Drill — Action Required A17

Define a DR drill procedure in `docs/en/ops/incident-playbook.md`:

1. **PITR restore procedure** (Pro+ tier):
   - Supabase Dashboard → Database → Backups → "Restore to a point in time" → select timestamp.
   - This creates a NEW project; data migration to existing project requires manual `pg_dump` / `psql`.
   - Practice this once before alpha — write down the steps + screenshots.

2. **Daily backup restore** (Free tier fallback if A17.1 not done):
   - Supabase Dashboard → Database → Backups → "Restore from daily backup".
   - Coarser-grained recovery (up to 24-hour data loss).

3. **Disaster scenarios** to drill through:
   - Accidental `DELETE FROM patterns WHERE owner_id = ...` without WHERE (run by mistake via SQL Editor).
   - Faulty migration drops a column with prod data.
   - Account compromise: an attacker logs into Supabase Dashboard and drops a table.

4. **Recovery time objective (RTO)**: define and document. Recommended target for alpha:
   - RTO ≤ 1 hour (operator notices incident → starts restore → restored within 1 hour).
   - RPO ≤ 5 minutes (data loss bounded to 5 minutes via PITR).

**Owner**: devops-engineer + technical-writer.

---

## 27. Migration Rollback Procedure

**Source**: `mcp__supabase__list_migrations` (31 migrations applied to prod) + agent-team deliberation, 2026-05-12.

### 27.1 Current state

- All 31 migrations are **forward-only** — Supabase does not support automatic rollback.
- Migration naming: numbered `001`-`019` (early phases) + timestamped (`20260506074424_...` etc., from Phase 41.x).
- Recent migrations:
  - `028_app_config` (Phase 39 W4) — force-update infrastructure.
  - `phase_24_1_device_tokens` — push notification tokens.
  - `phase_d_terminology_audit_*` — schema-level renames (collaboration_core + suggestion_workflow).

### 27.2 Action Required — A18. Document migration rollback procedure — ✅ CLOSED (2026-05-12)

**Resolution**: New runbook [docs/en/ops/migration-rollback.md](migration-rollback.md) + JA mirror [docs/ja/ops/migration-rollback.md](../../ja/ops/migration-rollback.md). Covers:

1. **Forward-only principle** + the choice matrix (forward-fix vs PITR restore vs both).
2. **Destructive migration matrix** with per-class recovery paths (DROP TABLE / DROP COLUMN / ALTER ... TYPE lossy + lossless / DROP FUNCTION / ALTER FUNCTION search_path / REVOKE EXECUTE / DROP-CREATE POLICY / data-only DELETE / RLS DISABLE-ENABLE).
3. **Pre-migration safety discipline**: BREAKING tag in header, inline `-- Rollback plan:` comment block in the migration SQL, low-write apply window.
4. **Pre-v1 breaking-change policy** documented with the two existing precedents (Phase D terminology, migration 030 share-token arm removal). Phase 40 GA tightens the policy (ADR + coupled compatibility window + double-staffed review).
5. **Rollback drill procedure** — runnable on migration 030 as the first reference exercise.
6. **PITR procedure** — step-by-step Supabase Dashboard restore flow.
7. **Forward-fix vs PITR decision matrix** — symptom-indexed choice table.

Cross-linked from `release.md` and `pre-alpha-checklist.md`.

## 28-29. Privacy Policy + Terms of Service Review

**Source**: `docs/public/{privacy-policy,terms-of-service}/index.html` + JA mirrors, 2026-05-12.

### 28.1 Privacy Policy — Confirmed Coverage

EN + JA sections present:
- Overview / Information We Collect (Account / UGC / Auto-Collected / Diagnostic / Push)
- How We Use / Data Storage and Security / Data Sharing / Your Rights
- Children's Privacy (under 13)
- Local-Only Mode (no-analytics-no-crash-reporting acknowledgment)
- Data Processing (EU/EEA) — GDPR rights enumerated
- Changes to Policy / Contact Us

### 28.2 Privacy Policy — Needs Improvement

- **Subprocessor list** is incomplete: only Supabase named in "Data processor" line. PostHog + Sentry mentioned in pass-through but not enumerated with their roles. **Action**: add comprehensive subprocessor table — see Section 31 A19.
- **Data retention specifics** missing: "retained as long as account is active" is too vague for GDPR Art. 13. Need specifics per data type (e.g., crash logs in Sentry: 90 days; PostHog events: 1 year; auth tokens: refresh-token lifetime).
- **Data portability**: GDPR § lists the right but does NOT provide an actual portability path (download my data button or email request flow). **Action**: A20 below.

### 29.1 ToS — Confirmed Coverage

EN + JA sections present: Acceptance / Service / Accounts (Eligibility / Security / Sign-in) / Conduct / Subscriptions (Free / Pro / Trial / Renewal / Refunds / Price Changes) / IP (Your Content / Sharing License / Our Content / Symbol Catalog) / Public Patterns / Termination (User / Us) / Disclaimers / Liability / Privacy / Changes / Governing Law / Severability / Contact.

### 29.2 ToS — Action Required

**A19. Add DMCA safe-harbor section to ToS** (covers Apple Guideline 5.2 + Play UGC policy):

New section 6.5 (or 7.1) "DMCA Notice and Takedown":
- Compliant with 17 U.S.C. § 512 (DMCA safe harbor).
- Specifies the elements of a valid DMCA notice (per §512(c)(3)(A)):
  1. Physical / electronic signature.
  2. Identification of the copyrighted work.
  3. Identification of the infringing material with sufficient detail to locate.
  4. Contact info (address / phone / email).
  5. Good-faith statement.
  6. Statement under penalty of perjury that complaint is authorized.
- **Designated DMCA agent contact**: name + email + physical address.
- Counter-notification procedure (per § 512(g)).
- Repeat-infringer policy.

**Owner**: technical-writer (drafting) + Owner (designate agent contact info).

---

## 30. PII Inventory

**Source**: agent-team deliberation 2026-05-12 + Privacy Policy review.

### 30.1 PII Inventory

| Data type | Source | Where stored | Retention | Required? |
|---|---|---|---|---|
| Email address | User signup | Supabase `auth.users.email` | Until account deletion | ✅ for signup |
| Display name | User input | Supabase `public.profiles.display_name` | Until account deletion | Optional (default empty) |
| Avatar image | User upload | Supabase Storage `avatars` bucket | Until user deletes or account deletion | Optional |
| Bio text | User input | Supabase `public.profiles.bio` | Until account deletion | Optional |
| FCM / APNs device token | OS-generated | Supabase `public.device_tokens.token` | Until token rotation or account deletion | Required if push enabled |
| Locale | OS / app prefs | Supabase `public.device_tokens.locale` | Until account deletion | Required for push (servers locale) |
| Pattern data | User input | Supabase tables (patterns / chart_documents / variations / versions) | Until account deletion | Required for app function |
| Project + progress data | User input | Supabase tables | Until account deletion | Required for app function |
| Suggestion + comment text | User input | Supabase tables (UGC) | Until account deletion (target owner can also delete) | Required for collaboration |
| Bug report content | User input (opt-in) | GitHub Issues on `b150005/skeinly` | Forever (public repo Issues) | Optional (per submission) |
| PostHog event data | App emit (opt-in) | PostHog cloud | Per PostHog retention (default 7 years; adjust in project settings) | Optional (opt-in) |
| Sentry crash data | App emit (opt-in) | Sentry cloud | Per Sentry retention (default 90 days for Developer plan) | Optional (opt-in) |
| Subscription state | RevenueCat → Supabase | Supabase `public.subscriptions` + RevenueCat cloud | Per RevenueCat retention | Required for Pro features |
| IAP transaction history | Apple / Google | Apple / Google billing servers | Forever (per Apple/Google policy) | Required for IAP |
| Supabase auth session JWT | OS-generated | Local device (currently UNENCRYPTED — see A14) | Until logout / session expiry | Required for auth |

### 30.2 PII NOT collected

- ❌ Physical / mailing address (none)
- ❌ Phone number (none)
- ❌ Date of birth (none)
- ❌ Real name (display name is user-chosen, may be a handle)
- ❌ Location data (no GPS / IP-derived location storage)
- ❌ Biometric data (no Face ID / fingerprint storage even if biometric auth introduced later — Apple/Google handles)
- ❌ Contacts list (no contact-discovery features)
- ❌ Photos library access (only avatar / pattern images via user-initiated picker)
- ❌ Microphone / camera access (none)

### 30.3 Action Required

None — inventory matches the Privacy Policy disclosures (subject to A19 ToS DMCA addition + A21 retention specifics).

---

## 31. Subprocessor List

### 31.1 Confirmed Subprocessors

| Subprocessor | Purpose | Data processed | DPA / link |
|---|---|---|---|
| **Supabase** (via AWS) | Backend database + auth + storage + realtime | All app data: auth.users / public.* / Storage avatars | https://supabase.com/privacy |
| **RevenueCat** | Subscription state proxy + IAP receipts | Subscription state, app_user_id (= Supabase UID), platform / product_id / transaction_id | https://www.revenuecat.com/privacy |
| **Sentry** | Crash + error reporting (**opt-in**) | Stack traces, device model, OS version, anonymous install UUID | https://sentry.io/privacy/ |
| **PostHog** | Anonymous product analytics (**opt-in**) | Page views, button taps, anonymous distinct_id (per-install UUID) | https://posthog.com/privacy |
| **Apple (APNs)** | Push notification delivery (iOS) | Opaque device token + notification body | https://www.apple.com/legal/privacy/ |
| **Google (FCM)** | Push notification delivery (Android) | Opaque device token + notification body + app_id (Firebase project) | https://policies.google.com/privacy |
| **GitHub** | In-app bug report → Issue (**opt-in per submission**) | Bug title + body + PostHog distinct_id (cross-reference) | https://docs.github.com/en/site-policy/privacy-policies |
| **Apple App Store / Google Play** | IAP processing | Transaction details, user Apple/Google ID (not shared with Skeinly directly — RevenueCat receives) | per Apple/Google IAP terms |

### 31.2 Action Required — A19a. Add subprocessor table to Privacy Policy

Replace the current "Data Sharing" pass-through paragraph with the table above (or an HTML-formatted equivalent). EN + JA mirrors.

---

## 32. GDPR / CCPA Data Portability

### 32.1 Action Required — A20. Implement data portability — ✅ CLOSED for alpha (2026-05-12) / Option B scheduled pre-Phase-40

**Resolution (alpha-scope, Option A)**: New operational runbook [docs/en/ops/data-export-sop.md](data-export-sop.md) (+ JA mirror) covering:

- SLA: 7-day operator response target (well within GDPR Article 12(3) 30-day requirement)
- Receipt + identity verification workflow (refuse requests from unverified email channels per GDPR Article 12(6))
- Supabase Dashboard SQL Editor enumeration of all 17 user-scoped tables (profiles / patterns / projects / progress / project_segments / chart_documents / chart_versions / chart_variations / shares / comments / suggestions / suggestion_comments / activities / device_tokens / subscriptions / user_symbol_pack_state / feedback) — single CSV export
- Storage avatars enumeration + per-file download
- `auth.users` metadata inclusion
- Out-of-scope subprocessor data instructions (Apple/Google IAP, RevenueCat, GitHub Issues, Sentry, PostHog — each with self-serve pointer + relevant UUID)
- Response email templates (EN + JA) listing what's included and what's not
- Closure logging

This fully satisfies GDPR Article 20 + CCPA right-to-know for the 5–10 closed-alpha tester scope. The 7-day operator response is rehearsed during the alpha to keep the SOP fresh against the eventual real request.

**Scheduled (pre-Phase-40 GA, Option B)**: In-app "Export My Data" button in Settings + new Edge Function `export-my-data` (with `verify_jwt = true`) running the equivalent server-side queries scoped to the caller's UID, returning a downloadable JSON bundle. The Edge Function reuses the table enumeration in the SOP step 4 as its query body. Tracked in CLAUDE.md `### Planned — pre-Phase-40 polish` so it lands before the GA.

**Owner**: devops-engineer (Option A SOP, this commit) + implementer (Option B pre-Phase-40).

---

## 33. Data Retention Policy

### 33.1 Action Required — A21. Document specific retention periods

Replace the Privacy Policy's vague "retained as long as account is active" with a per-data-type table:

| Data type | Retention period | Trigger for deletion |
|---|---|---|
| Account record (auth.users) | Until account deletion | User-initiated deletion |
| Profile data (public.profiles) | Until account deletion | Cascades from account deletion |
| Pattern / project data | Until account deletion | Cascades |
| Push device tokens | Until token rotation OR account deletion | Token rotation overwrites; FK cascade on auth.users delete |
| PostHog event data | 12 months (configure in PostHog project settings) | Auto-expire per PostHog retention |
| Sentry crash data | 90 days (Developer plan default) | Auto-expire per Sentry retention |
| Supabase database backups | 7 days (Pro PITR) | Auto-expire per Supabase backup retention |
| GitHub Issues (bug reports) | Permanent (public Issues repo) | Manual close + lock if user requests |
| RevenueCat subscription data | Per RevenueCat retention policy | Per RevenueCat |
| Apple App Store / Google Play purchase history | Per Apple/Google policy | Out of Skeinly control |

**Owner**: technical-writer.

---

## 34. App Tracking Transparency (ATT) Decision

**Source**: Section 1.1 V2 analysis from Apple guidelines audit.

### 34.1 Decision — ATT prompt NOT required

Per the Apple Guidelines Section 1.1 V2 analysis:
- PostHog (opt-in, anonymous per-install distinct_id) → NOT cross-app tracking for advertising.
- Sentry (opt-in, anonymous installation UUID) → NOT tracking.
- RevenueCat → handles IAP only, NOT advertising attribution.

ATT prompt is required ONLY when an app links user/device data across third-party apps/websites for advertising/attribution. Skeinly does neither.

### 34.2 Action Required — A22. ATT decision rationale doc — ✅ CLOSED (2026-05-12)

**Resolution**: New section added to [docs/en/ops/repo-policy.md](repo-policy.md) (+ JA mirror) documenting:

- The two ATT-triggering conditions per Apple guidance (cross-app linkage for advertising / data-broker sharing) — both must be evaluated independently.
- Per-subprocessor analysis table: PostHog / Sentry / RevenueCat / Apple APNs / Google FCM / Supabase / GitHub all confirmed non-tracking + non-data-broker (2026-05-12 verification).
- Conclusion: `NSUserTrackingUsageDescription` is intentionally absent; `ATTrackingManager.requestTrackingAuthorization` is never called.
- Conditions that require re-evaluation (e.g., adding a third-party advertising SDK, subprocessor pivots from first-party to data-broker classification, RevenueCat changes subprocessor relationship, new opt-in feature joins Skeinly data with third-party data).
- Reviewer-facing summary text recommended for App Store Connect → App Review → Notes — saves review-loop time when the reviewer asks "why no ATT prompt?".

**A22.1** (continuous verification): Re-verify PostHog DPA + Sentry DPA at each contract renewal. PostHog is positioned as a first-party analytics processor as of 2026-05-12; if either pivots to data-broker / cross-app-attribution offering, A22 must be re-opened and the ATT decision re-evaluated.

---

## 35-36. App Store Privacy Nutrition Label + Play Data Safety form

### 35.1 App Store Privacy Nutrition Label (per Apple's data type categories)

Drafted matrix per the PII inventory:

| Apple Data Category | Apple Data Type | Used for | Linked to user? | Tracking? |
|---|---|---|---|---|
| Contact Info | Email Address | App Functionality | Yes | No |
| Contact Info | Name (display name) | App Functionality | Yes | No |
| Identifiers | User ID (Supabase UUID) | App Functionality | Yes | No |
| Identifiers | Device ID (FCM/APNs token) | App Functionality (push) | Yes | No |
| Purchases | Purchase History | App Functionality | Yes | No |
| User Content | Photos or Videos (avatar + pattern images) | App Functionality | Yes | No |
| User Content | Other User Content (patterns / projects / progress / comments / suggestions) | App Functionality | Yes | No |
| Diagnostics | Crash Data (Sentry, opt-in) | App Functionality | No (anonymous installation UUID) | No |
| Diagnostics | Performance Data (PostHog, opt-in) | Analytics | No (anonymous distinct_id) | No |

**Submission**: enter this in App Store Connect → App Privacy → Edit Data Types when creating the App Store listing.

**Owner**: technical-writer + product-manager (Owner approves).

### 36.1 Google Play Data Safety form

Already drafted in Section 2.1 A6 (covers the same categories with Play's terminology). Re-use that table for Play Console submission.

---

## 37. DMCA Takedown Procedure

**Status**: see Section 1.1 A4 + Section 29.2 A19 — addressed in those Action Required items. Closed.

---

## 38. Account Deletion End-to-End Verification

**Source**: ADR-005 + Supabase RPC `delete_own_account` (per Section 3.2 A11), 2026-05-12.

### 38.1 Verification needed

The in-app deletion path is implemented (Phase 17 / ADR-005). End-to-end test required before alpha:

1. Create test account (use real email).
2. Sign in.
3. Create pattern + project + 1 suggestion comment + 1 share + register push token.
4. Settings → Account → Delete Account → confirm.
5. Verify: account is signed out, all user data is gone from Supabase tables (cascade: auth.users → patterns / projects / chart_documents / chart_versions / chart_variations / shares / comments / suggestions / suggestion_comments / activities / progress / project_segments / device_tokens / subscriptions / user_symbol_pack_state / feedback / profiles / Storage avatars).
6. Re-sign-up with the same email → should succeed as a new account (no residual data).
7. Verify within Supabase Dashboard SQL Editor: `SELECT count(*) FROM <each_table> WHERE <user_id_col> = <old_uid>;` → all zero.

**Owner**: tester / devops-engineer (run drill before alpha invite).

### 38.2 Edge cases to test

- Account with active Pro subscription: subscription is cancelled? Or just disassociated from auth.users? Check `subscriptions` row state post-deletion.
- Account with active Suggestions on others' patterns: do the suggestions remain visible to the target owner (zombie reference), or do they cascade delete? Verify against ADR-014.
- Account whose patterns were copy-saved by other users: parent patterns are deleted, but child copies (forks) should remain. Verify the FK constraint behavior.

**Action**: document outcomes in `docs/en/ops/incident-playbook.md` as a "delete-account drill" entry.

## 39-45. Accessibility Audits

**Source**: grep + count across `shared/src/commonMain/kotlin/.../ui/`, `iosApp/iosApp/`, 2026-05-12.

### 39.1 Coverage Summary

| a11y dimension | Code-level coverage | Pre-alpha action |
|---|---|---|
| **TalkBack / VoiceOver** labels | Compose: 34/43 @Composable files (~79%) have `contentDescription` or `Modifier.semantics`. SwiftUI: 28+ files use `accessibilityLabel`. Reasonable baseline, but per-screen verification needed. | A23 below |
| **Dynamic Type / font scaling** | **Zero explicit code references** (no `@ScaledMetric`, no `LocalDensity`, no `fontScale` queries). Material 3 + SwiftUI defaults respect system font scale, but lack of audit means overrides could exist. | A24 below |
| **Dark Mode** | Material 3 + SwiftUI auto-adapt by default. No explicit dark-mode-only logic seen. | Manual visual check on each screen | 
| **Reduce Motion** | **Zero references** to `accessibilityReducedMotion` (iOS) or Compose's reduce-motion query. Animations (spring / fade / chart canvas transitions) are NOT gated. | A25 below |
| **Color contrast (WCAG 2.1 AA)** | Theme tokens used (Material 3 + SwiftUI semantic colors). Per-screen contrast verification needed. | Manual audit using Accessibility Inspector tools | 
| **Touch targets (≥ 44pt iOS / 48dp Android)** | ChartEditor has known small-cell-touch-target issue (deferred per CLAUDE.md Tech Debt). Other screens unknown. | A26 below |
| **Focus order + empty / loading / error states** | Mixed coverage. Per CLAUDE.md `## Output Quality Standard`, every screen should have explicit empty / loading / error states; need verification. | A27 below |

### 39.2 Action Required

**A23. TalkBack + VoiceOver walk-through audit**

Before alpha invite, an operator walks through every primary screen with TalkBack (Android) and VoiceOver (iOS) enabled. Document each gap:
- Unlabeled IconButton / IconToggle
- Decorative images without `contentDescription = null` (Compose) or `.accessibilityHidden(true)` (SwiftUI)
- Custom-drawn Canvas / Path elements without `Modifier.semantics(contentDescription = ...)`
- Composite components where the screen reader announces field-by-field instead of as a unit

Output: a per-screen gap list + fixes. Estimated effort: 2-4 hours for a single pass.

**Owner**: ui-ux-designer + ad-hoc tester.

**A24. Dynamic Type smoke test**

In iOS Settings → Display & Brightness → Text Size, set to maximum (largest accessibility text size). Launch Skeinly. Walk through every primary screen and confirm:
- Text doesn't truncate or clip
- Layout reflows correctly (no overlapping elements)
- No fixed-size hit areas that text outgrows

On Android: Settings → Display → Font size → Large. Same walk-through.

If issues found, add `@ScaledMetric` / `LocalDensity`-based responsive sizing on affected screens.

**A25. Reduce Motion respect**

Implement two `expect/actual` helpers (or top-level KMP functions):
```kotlin
expect fun isReduceMotionEnabled(): Boolean
// iOS: UIAccessibility.isReduceMotionEnabled
// Android: AccessibilityManager.isAnimationsEnabled (inverse) OR Settings.Global.ANIMATOR_DURATION_SCALE == 0f
```

Wire into all animated transitions:
- ChartCanvas zoom/pan animations
- Splash screen transitions
- Onboarding page slide animations
- Notification banner slide-in
- List item swipe-to-delete (could be acceptable as-is, evaluate)

When `isReduceMotionEnabled() == true`, use `AnimationSpec.snap()` / instant transitions instead.

**Owner**: implementer + ui-ux-designer review.

**A26. Touch target audit (≥ 48dp)**

For every clickable element, verify the touch target meets:
- Android: Material guideline 48dp ([Material 3 spec](https://m3.material.io/foundations/accessible-design/dimensions))
- iOS: HIG 44pt ([Apple HIG](https://developer.apple.com/design/human-interface-guidelines/buttons))

ChartEditor is the known violator (per Tech Debt entry). Other surfaces:
- Bottom Nav bar: standard Material 3 / Tab Bar — ✅ default 56dp / 44pt
- IconButtons: default 48dp / 44pt — ✅ if `Modifier.size` not overridden
- Custom small-icon-in-list-item: verify per occurrence

**Action**: visual audit + fix per screen. Production-ready alpha should pass ≥ 48dp on all primary actions; ChartEditor known issue stays deferred.

**A27. Empty / loading / error state audit**

For every list/grid screen, confirm three explicit states:
- Empty: clear copy + (where relevant) CTA to populate.
- Loading: skeleton or spinner (not a blank screen).
- Error: actionable copy + retry button.

Skeinly has these for some screens (Discovery, PR list, project list) per spec docs. Verify the rest.

**Owner**: ui-ux-designer.

### 39.3 Post-alpha extension

WCAG 2.1 AA color contrast formal audit (using Accessibility Inspector or axe-DevTools-style tooling) is post-alpha unless real beta-tester feedback surfaces a contrast issue.

## 46-48. Observability — Sentry / ANR / Performance Monitoring

**Source**: `iosApp/iosApp/iOSApp.swift:25-78` + `androidApp/src/main/kotlin/.../SkeinlyApplication.kt:35-55` + Sentry SDK defaults, 2026-05-12.

### 46.1 Sentry Crash Reporting — Confirmed Compliant

| Config | iOS | Android |
|---|---|---|
| DSN | xcconfig → Info.plist | BuildConfig.SENTRY_DSN_ANDROID |
| Environment tag | `"development"` / `"production"` per build | `"development"` / `"production"` per build |
| `tracesSampleRate` | 0.2 (20%) | 0.2 (20%) |
| `attachScreenshot` | `false` (privacy) | `false` (privacy) |
| `attachViewHierarchy` | `false` (privacy) | `false` (privacy) |
| App Hang Tracking | Simulator: OFF, Debug: 4s threshold, Release: 2s default | Default ANR detection (5s threshold) via SentryAndroid SDK |
| Init timing | BEFORE Koin (captures Koin init failures) ✅ | BEFORE Koin ✅ |

### 46.2 Action Required — A28. Set crash-free SLO target

Pre-alpha decision: define the **crash-free user rate target** for the closed-alpha tester pool. Recommendations:
- Alpha threshold: **≥ 99% crash-free users** (5-10 testers × few weeks = a single crash hits 90% rate easily; relax to 95% for closed alpha).
- GA threshold: **≥ 99.5%** (industry standard for consumer apps).

Configure Sentry → Project Settings → Alerts → create an alert that fires when crash-free user rate drops below threshold for ≥ 1 hour. Slack / email destination.

**Owner**: devops-engineer.

### 47.1 ANR Detection — Confirmed Compliant (Android)

- SentryAndroid SDK has built-in ANR detection. With `enableAppHangTracking` (default true on Sentry for Android 8.0+), captures ANRs ≥ 5 seconds.
- Android Vitals (Play Console → Vitals) auto-captures ANRs from production builds; no additional wiring needed.

### 47.2 Action Required — A29. Configure Android Vitals + Sentry ANR alerts

- Play Console: enable "ANR rate is bad" + "ANR rate is excessive" Vitals alerts → email notifications.
- Sentry: ANR issues are captured as separate issue types (`application_not_responding`); confirm they triage to the same alert / on-call channel as crashes.

**Owner**: devops-engineer.

### 48.1 Performance Monitoring — Confirmed Compliant

- Sentry `tracesSampleRate = 0.2` provides distributed tracing for 20% of transactions. Captures auto-instrumented routes (Compose navigation, Ktor HTTP, RoomDatabase / SQLDelight). Sufficient for alpha-scale.
- No explicit cold-start tracing yet; SentryAndroid's `enableAutoSessionTracking` (default true) captures app-start as a session metric.
- No PostHog performance events; PostHog is for product analytics, not perf.

### 48.2 Action Required (post-alpha)

- Cold-start time profiling — pre-GA, baseline + track cold-start TTID (time-to-initial-display) via Sentry → expand to TTFD (time-to-fully-drawn) when content is hydrated.
- Memory leak detection — LeakCanary is Android-only; iOS has Instruments for ad-hoc audit. Post-alpha unless real OOM signals surface from Sentry / PostHog.

**Owner**: performance-engineer.

## 49-55. Store Listings + Phased Rollout

**Source**: `docs/en/store-listing.md` + agent-team deliberation, 2026-05-12.

### 49.1 Apple Sandbox + Play License Tester Registration

**Action V18** (user-side, pre-alpha invite):
1. **Apple Sandbox Testers**: App Store Connect → Users and Access → Sandbox → add a Sandbox Tester per alpha tester. Each gets a unique sandbox-only email. Testers sign out of their real Apple ID on the test device's IAP settings + sign in with the sandbox account.
2. **Play License Testers**: Play Console → Settings → License Testing → add tester email. Same email must be the Google account on the test device for IAP test purchases.

Documented in `docs/en/ops/beta-testing.md` per CLAUDE.md. Verify count: ≥ 5 sandbox + ≥ 5 license testers registered ahead of alpha invite.

### 50.1 App Store Connect Listing

**Source content**: `docs/en/store-listing.md` already drafted with bilingual app name, short / full description, categories, content rating target.

**Action V19** (user-side):
1. App Store Connect → My Apps → Skeinly → App Information / Pricing and Availability / App Privacy / Version.
2. Upload screenshots (EN + JA) — see `docs/en/store-listing.md` for required sizes. Capture screenshots from the actual app per the Phase 39 launch readiness scope.
3. App Privacy: enter the Nutrition Label per Section 35.1.
4. Keywords: review the keyword field for SEO; current `docs/en/store-listing.md` lists candidate keywords.
5. App Review Information: enter demo account credentials per Section 52.1 below.
6. Age Rating: Set 4+ via App Review questionnaire (Section 1.1 V6).

### 51.1 Google Play Console Listing

**Same source content** from `docs/en/store-listing.md`.

**Action V20** (user-side):
1. Play Console → Skeinly → Main store listing / Privacy & security.
2. Data Safety form per Section 2.1 A6 + Section 36.1.
3. Account Deletion URL per Section 2.1 A7.
4. Content Rating (IARC) questionnaire per Section 2.2 V8.
5. Target Audience and Content: declare "Adults only" per V7.
6. Screenshots (EN + JA).

### 52.1 Review Demo Account + Reproduction Docs

**Required for both Apple App Review and Google Play Review**.

**Action A2 / V21** (devops-engineer + product-manager):
1. Create permanent `demo@skeinly.example` Supabase user via dashboard.
2. Seed data via SQL or Edge Function:
   - 3 patterns (rectangular, polar, variation) — covers chart editor + structured chart authoring.
   - 1 project actively in progress (current row > 0, photos attached).
   - 1 active Suggestion on a public pattern (so reviewers can exercise the collaboration flow).
   - 1 device with notifications enabled for testing push delivery.
   - Pro subscription state (or a path to trigger paywall via sandbox IAP).
3. Document in Review Notes:
   - Credentials: email + password.
   - Feature walkthrough: "Sign in → patterns library → tap '...' → ...".
   - IAP sandbox note for App Store reviewers.
   - Beta-only feature gates: "PostHog/Sentry SDKs only initialize in builds with BuildFlags.isBeta = true; they are dormant in this version."

### 53.1 Subscription Disclosure Compliance

Per Section 1.1 A3 (Apple) + Section 2.1 A8 (Play). Combined action items:

**Action A3 + A8 + A30**:
1. Audit paywall (`SubscriptionPaywallScreen` or equivalent) for the 5-item checklist (feature list, prominent price, annual total, Restore Purchases, ToS/Privacy links).
2. iOS: Settings → Subscription Management deep link to `https://apps.apple.com/account/subscriptions`.
3. Android: Settings → Subscription Management deep link to `https://play.google.com/store/account/subscriptions?sku=<product_id>&package=io.github.b150005.skeinly`.
4. Single `SubscriptionManagementLauncher` `expect/actual` (mirrors `StoreUrlLauncher` from Phase 39 W4).

**Owner**: implementer + ui-ux-designer.

### 54.1 Family Sharing Decision

**Decision** (agent-team 2026-05-12): **OPT IN to Family Sharing for Pro subscription**.

Rationale:
- Family Sharing is a positive UX signal — Apple highlights Family Sharing-eligible apps in App Store discovery.
- No technical work — toggle in App Store Connect at the subscription product level.
- No revenue impact — Apple's "one purchase shared with up to 6 family members" model. Subscription price stays the same; one IAP entitlement is shared.
- Risk: Family Sharing increases "user count" for analytics purposes (one purchase, multiple devices). RevenueCat handles this transparently via `app_user_id`.

**Action V22** (user-side): App Store Connect → Subscription product → Family Sharing → enable. Google Play: equivalent "Family Library" enrollment at the subscription level.

### 55.1 Phased Rollout Strategy

For alpha + GA both.

**Alpha** (this phase):
- Closed to 5-10 invited testers via TestFlight Internal + Play Internal Testing.
- Full functionality available.
- Feedback channel: in-app bug report (Phase 39 W5 GitHub App proxy) + email to support contact.

**GA (Phase 40+)**:
- Play Console **Staged Rollout**: 1% → 5% → 25% → 50% → 100% over 2 weeks. Pause if crash-free rate < SLO.
- App Store **Phased Release**: 7-day phased release (default). Pause if Sentry signals breakage.

**Operational** :
- Define a kill switch (Section 23 A15) so a regression can be rolled back without app-update friction.
- Define rollback procedure for force-update (revert `min_required_version_*` to a lower value if a force-update was triggered erroneously).

**Owner**: devops-engineer + Owner.

## 56. Stitch Symbol Catalog Review

**Source**: `mcp__supabase__execute_sql SELECT * FROM public.symbol_packs` + `shared/src/commonMain/kotlin/.../domain/symbol/catalog/`, 2026-05-12.

### 56.1 Current Catalog

| Pack ID | Tier | Symbols | Pro? | Source |
|---|---|---|---|---|
| `jis.knit.beginner` | free | 35 | — | JIS knitting standard |
| `jis.crochet.beginner` | free | 35 | — | JIS crochet standard |

Total: **70 free symbols** bundled. No Pro packs yet (per user's intent — alpha-after symbol authoring will define Pro pack contents + level metadata).

### 56.2 Action Required — A31. Knitter agent walk-through

Before alpha invite, the **knitter agent** (per CLAUDE.md `## Agent Team`) reviews:
1. Each of the 70 symbols' JIS labels (EN + JA) — are they accurate? Spelling? Convention-correct?
2. The visual SVG / Bitmap representation against JIS publication standards.
3. Any symbols that crochetter / knitter beta testers might find confusing or wrong.
4. The chevron form of `jis.crochet.reverse-sc` (already flagged in CLAUDE.md Tech Debt — awaiting beta-tester feedback).

Output: per-symbol findings list. Fix any HIGH-confidence errors pre-alpha; defer cosmetic polish to post-beta.

**Owner**: knitter agent + ui-ux-designer review.

### 56.3 Pro Pack Authoring — Deferred (per user 2026-05-12)

> User policy: "Pro用のコンテンツがまだ用意されていないなら、alphaリリース後に編目記号アセットの追加・修正を行うので、そのタイミングでどの編目記号をPro版にするか、編目記号のメタデータ(レベルなど)定義していきましょう"

Action:
- Post-alpha: knitter agent + product-manager design Pro pack contents (advanced / specialty stitches).
- Define `tier: 'pro'` packs + per-symbol metadata (level: beginner / intermediate / advanced / expert).
- Re-test paywall + symbol unlock flow.

**Owner**: knitter agent + product-manager.

---

## 57. Terminology Layperson Check (key surfaces)

**Source**: agent-team deliberation + Section 1.1 of [CLAUDE.md](../../.claude/CLAUDE.md) on the deferred terminology audit, 2026-05-12.

### 57.1 Constraint Context

Per CLAUDE.md, the full terminology audit (every user-facing word, EN + JA) is **post-beta**. But the user's pre-alpha checklist instruction asks for at least the **important surfaces** (Settings / Onboarding / main CTAs) reviewed for layperson clarity NOW.

### 57.2 Action Required — A32. Spot-check key surfaces

Before alpha invite, walk through these surfaces in both EN and JA:
1. **Onboarding** (1st-launch flow) — every word must be comprehensible to a layperson knitter.
2. **Settings** — section titles ("Beta" / "Account" / "About") + row labels.
3. **Sign In / Sign Up screens** — auth-flow copy.
4. **Main CTAs** — "Create Project", "Add Pattern", "Open Chart Editor", etc.
5. **Discovery feed** — empty state copy + filter UI labels.
6. **PR / Suggestion flow** — already underwent the 2026-05-10 terminology audit (Git → knitter language); spot-check the final EN+JA copy.
7. **Pro paywall** — F1 Pro benefits copy.

For each surface, ask: "Would a non-developer hobbyist knitter understand this on first read?" Flag jargon ("variation"? OK; "branch"? avoid; "diff"? avoid → "comparison"). The 2026-05-10 audit already covered most of this; confirm no regressions.

Output: per-surface findings list with proposed rewordings.

**Owner**: knitter agent + ui-ux-designer.

### 57.3 Full audit (post-beta)

The full terminology audit remains scheduled for post-beta per CLAUDE.md. The pre-alpha A32 is **spot-check only**, not the full sweep.

## 58. Open Source Attribution Screen

**Source**: grep of `shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/settings/SettingsScreen.kt` for `License` / `Acknowled` etc., 2026-05-12.

### 58.1 Current state

Settings screen does **NOT** have a Licenses / Acknowledgements / Open Source row.

### 58.2 Action Required — A33. Add OSS attribution screen

Skeinly bundles ~40+ third-party libraries (Kotlin / Compose Multiplatform / Supabase-kt / RevenueCat / Sentry / PostHog / etc.). Per Apple Guideline 5.1.1 (in practice, attribution best-practice) + Play attribution expectations + most OSS licenses' attribution requirement, an in-app attribution screen is expected.

**Implementation options**:
1. **Manual list** — drafted from `libs.versions.toml` + iOS SwiftPM packages. ~200 lines maintained manually. Pro: precise. Con: drift risk.
2. **Automated** (preferred): use `com.github.jk1.dependency-license-report` Gradle plugin for Android + `LicensePlistExtractor` for iOS. Renders into a hosted `licenses.html` page in `docs/public/` + in-app WebView OR markdown render.

**Implementation plan** (A33):
1. Add Gradle plugin `com.github.jk1.dependency-license-report` to `androidApp/build.gradle.kts`.
2. Run `./gradlew licenseReport` — emits `androidApp/build/reports/dependency-license/index.html`.
3. Process the output into a JSON or Markdown format that the KMP shared UI can render.
4. Add `Settings → About → Open Source Licenses` row → opens a Composable screen rendering the list.
5. For iOS: collect SwiftPM dependency licenses via similar tooling, OR consolidate into the same `licenses.json` shipped from CI.
6. Wire i18n: section title + "View license" button labels.

**Owner**: implementer + technical-writer.

### 58.3 Pre-alpha minimum

If full OSS plugin wiring slips for alpha, ship at minimum:
- Markdown file at `docs/public/licenses/index.html` with the top-15 dependencies listed manually.
- Settings → About → Open Source Licenses → opens that URL in browser.

---

## 59. Support Contact Channel

**Source**: `docs/public/privacy-policy/index.html` + `docs/public/terms-of-service/index.html`, 2026-05-12.

### 59.1 Existing

`skeinly.app@gmail.com` is the support email, published in Privacy Policy + ToS pages. Reasonable for alpha-scale (5-10 testers).

### 59.2 Action Required — A34. In-app support contact entry

Add **Settings → About → Contact Support** row with two options:
1. `mailto:skeinly.app@gmail.com?subject=Skeinly%20support` deep link.
2. Or open the Privacy Policy contact section in browser.

Pre-fill the mail draft with diagnostic info (app version + OS version + locale) to make support triage faster. Use the existing `DeviceContextProvider` (Phase 39 W5b uses it for bug reports).

### 59.3 Recommended (post-alpha)

Migrate from a personal Gmail to a dedicated `support@skeinly.app` domain. Set up auto-reply with FAQ links. Track support volume (use Inbox label + tag system or migrate to Help Scout / Zendesk).

**Owner**: technical-writer (in-app entry) + Owner (domain email setup).

---

## 60. Help / FAQ

**Source**: agent-team deliberation 2026-05-12.

### 60.1 Current state

No Help / FAQ content in-app or on `docs/public/`.

### 60.2 Action Required — A35. Minimum Help / FAQ for alpha

Draft a minimal FAQ page covering the most-likely-asked questions:
1. "How do I create a pattern?" → onboarding redirect.
2. "How do I share a pattern?" → Discovery + share-via-link flow.
3. "How do I track a project?" → row counter usage.
4. "What is a Suggestion?" → collaboration vocabulary explainer.
5. "How do I subscribe to Pro?" → paywall walkthrough + Restore Purchases reminder.
6. "How do I cancel my subscription?" → deep link to Apple/Google subscription management (per A30).
7. "How do I delete my account?" → Settings → Delete Account.
8. "How do I report a bug?" → Bug report flow (Phase 39 W5b).
9. "Why is the app asking for push notifications?" → collaboration events explainer.
10. "Why are notifications in English when my phone is Japanese?" → locale propagation explainer (per Phase 24.1 design).

**Deliverable**: `docs/public/help/index.html` (EN + JA mirror at `docs/public/ja/help/index.html`). Linked from Settings → About → Help in-app.

**Owner**: technical-writer.

### 60.3 Post-alpha extension

Convert to a structured Help Center (e.g., Notion / Intercom / Crisp) once alpha feedback identifies the most-asked questions. Add screenshots + video walkthroughs for chart editor + collaboration flows.

---

## 61. Golden Path Verification

**Source**: this session's earlier dialogue with the user, 2026-05-12.

### 61.1 Pending User Input

The user is asked to prioritize which features they want manually verified before alpha invite. Candidates from the earlier session listing:

**Core**:
- Pattern creation → chart editor (symbol palette + canvas + history undo/redo) → save.
- Project creation + row counter progress tracking.
- Variation (アレンジ) creation + version history + restore.

**Collaboration**:
- Discovery feed → save to library (copy).
- Suggestion (提案) creation + comment + Apply.
- Suggestion conflict resolution flow.

**Notifications + subscription**:
- Push (PR opened / commented / merged-closed) → cold-start tap deep link.
- F1 Pro subscription purchase via Apple Sandbox / Play License tester → Pro feature unlock.

**Settings + privacy**:
- Sign in / out (Supabase Auth).
- Notification permission explainer + OS Settings deep link.
- Analytics opt-in (PostHog / Sentry) + Bug Report submission.
- Account deletion (cascade verification per Section 38).

**i18n**:
- EN / JA language switch (with Android per-app locale limitation per CLAUDE.md Tech Debt).

### 61.2 Action Required — A36. User-prioritized golden path verification

Before alpha invite:
1. Prompt user to rank these features (must-verify / nice-to-verify / skip).
2. Run Maestro flows (`e2e/flows/{android,ios}/`) corresponding to the must-verify features.
3. For Pro subscription, register Sandbox / License testers per V18 first.
4. For Discovery / Suggestion, register Supabase prod data + ensure non-empty Discovery feed exists.
5. Document any defects to fix before alpha invite.

**Owner**: tester (operator with running emulator/simulator).

---

## Revision history

- 2026-05-12 — Initial creation. Section 1 (App Store Review Guidelines) audit complete via docs-researcher agent. Sections 2–38 placeholders awaiting per-item processing.
