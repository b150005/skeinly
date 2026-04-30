# Active Backlog — Next-Session TODOs

> **Created**: 2026-04-30
> **Source**: User feedback session 2026-04-30 + agent team deliberation
> **Index**: linked from [CLAUDE.md](../CLAUDE.md) `## Development Roadmap` section
> **Purpose**: Bugs, feature requests, and documentation tasks that were identified but deferred for prerequisite gating. Read at the start of any session that resumes development after the user completes Phase 39 vendor prerequisites.

---

## How to Use This File

1. At the start of a new session, scan this file to understand pending work.
2. Cross-reference [CLAUDE.md](../CLAUDE.md) `## Tech Debt Backlog` for phase-deferred items (this file holds session-deferred items).
3. When closing an item: either move the full entry to CLAUDE.md `## Development Roadmap > Completed` (if it grew into a Phase) or delete it from this file (if it was a quick fix).
4. Items are ordered by **prerequisite-readiness** within each section, not by importance.

---

## Critical Decisions Pending

### D0: App display name finalization — ✅ DECIDED 2026-04-30: **Skeinly**
- **Verification trail**: User tested "Knit Note" (taken), "Knitnote" (taken), "Stitch Studio" (taken), "Skein" (taken), "Knit Atelier" (available but rejected as not preferred), then **"Skeinly" (available + chosen)** on App Store Connect.
- **Localized App Name (Apple)**: User to register Japanese localized App Name "スケインリー" or similar via App Store Connect localization (separate from in-app `app_name` which stays locale-identical "Skeinly" per the 33.x convention).
- **Bundle ID**: changed from previous `io.github.b150005.knitnote` to `io.github.b150005.skeinly` as part of full rebrand (since nothing released yet, no migration cost).
- **Apply same name to**: Google Play Console, RevenueCat, Sentry, PostHog projects, support email account (user creates `skeinly.app@gmail.com` or similar).
- **Implementation**: Full rebrand executed in same session via single PR — see git log.

### D0.1: Competitive analysis vs StitchBook
- **Reference**: <https://apps.apple.com/jp/app/stitchbook/id6757340779>
- **User observation**: StitchBook has overlapping core (knitting pattern + project tracking). Skeinly differentiates via Git-like PR/branch, community features, and pattern editor.
- **User also noted**: StitchBook has features and design merits worth studying.
- **Action**: market-analyst + ui-ux-designer agent协議 + screenshot capture from StitchBook for visual reference; output competitive matrix for monetization-strategist to factor into pricing.
- **Phase**: pre-Phase-40 polish (post-Phase-39-beta).

### D0.2: AppIcon redesign for Skeinly brand
- **Status**: open. Current icon (per CLAUDE.md Phase 19b citation but not yet verified against actual assets) was a "yarn ball motif" — no longer aligned with **Skeinly** brand which evokes a skein (loosely coiled hank of yarn).
- **Note from user (2026-04-30)**: actual current AppIcon does NOT have the purple `#7B61FF` color that CLAUDE.md Phase 19b claims. Documentation is out of sync with shipped assets.
- **Cross-link**: same physical icon files involved in [B1 — iOS AppIcon "19 unassigned children"](#b1-ios-appicon-not-displayed-on-home-screen). Closing B1 (Contents.json restructure) and D0.2 (skein motif redesign + recolor decision) can land in a single icon-work PR.
- **Color**: open. `#7B61FF` (current `AccentColor.colorset` from Phase 39.0.2 Sprint A PR3) is one option but not committed to — designer/user judgment for the actual icon artwork.
- **Phase**: D0.2 + B1 bundled, before Phase 39 beta TestFlight upload.

---

## P0 Bugs (verified on iOS device 2026-04-30; Android verification pending)

### B1: iOS AppIcon not displayed on home screen
- **Symptom**: Build warning `AppIcon.appiconset has 19 unassigned children`. Custom AppIcon not reflected on home screen.
- **Suspected root cause**: `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json` declares 19 per-size icon slots (legacy iOS layout) but only the 1024×1024 universal asset is provisioned (per Phase 19b artifact).
- **Fix scope**: Either (a) provide all 19 size variants from Phase 19b's source assets, or (b) restructure `Contents.json` to iOS 14+ universal-only single-1024 form (recommended — modern iOS auto-derives all sizes).
- **Android verification needed**: Confirm Android adaptive icon renders via Pixel Launcher mask (related to existing Tech Debt Backlog "Android Adaptive Icon" entry — `mipmap-anydpi-v26/ic_launcher.xml` is missing).

### B2: PR Submit infinite loading + state leak across tabs
- **Symptom**: Open a PR via "Submit" → loading spinner never clears → switching back to "Incoming" tab carries the loading state over.
- **Suspected root cause**: `isOpeningPullRequest` flag not cleared on success/failure, AND/OR PullRequestList state shared incorrectly between Incoming/Outgoing instances.
- **Root issue per user**: "Insufficient end-to-end flow testing." All flows must be verified.
- **Fix scope**:
  - Investigate `ChartViewerViewModel.openPullRequestInternal` flag clearing
  - Investigate `PullRequestListViewModel` state isolation (check if `defaultFilter` parameter actually creates separate instances or shared state via Koin)
  - Add Maestro E2E flow `P1_open_pr.yaml` for Android + iOS covering: open chart → submit PR → success snackbar → check Outgoing tab list update → check Incoming tab is independent
  - Add XCUITest `PullRequestSubmitFlowUITests.swift` for iOS
- **Files to inspect**: `ChartViewerViewModel.kt`, `PullRequestListViewModel.kt`, `PullRequestDetailViewModel.kt`, iOS `StructuredChartViewerScreen.swift` `OpenPullRequestSheet`, iOS `PullRequestListScreen.swift`.

### B3: Settings shows "Sign Out" / "Delete Account" while signed out
- **Symptom**: Fresh install, never signed in → Settings screen still renders the account-management section with "サインアウト" / "アカウントを削除".
- **Suspected root cause**: `SettingsViewModel.state.currentUserId == null` not gating the section render. Phase 33.1.8 i18n migration may have caused regression.
- **Files to inspect**: `SettingsScreen.kt` (Compose) + `SettingsScreen.swift` (SwiftUI).
- **App Store risk**: Apple Reviewers may reject UIs that imply features the user can't access. **Must fix before Phase 39 beta TestFlight upload**.

### B4: "Discover Patterns" shows "Internet required" despite online connection
- **NOT a Supabase migration issue**. Verified 2026-04-30 via `mcp__supabase__list_migrations` — all 16 migrations applied on prod (`nasjwbrhkcbkyegthrrl.supabase.co`).
- **Suspected root causes** (in order of likelihood):
  1. App's compiled `SupabaseCredentials` is empty (TestFlight build accidentally configured as local-only)
  2. `ConnectivityMonitor` actual implementation has false-positive offline path on iOS (NWPathMonitor edge case?)
  3. `DiscoveryViewModel` or `GetPublicPatternsUseCase` surfaces wrong error string for a different failure type
- **Files to inspect**:
  - `shared/build.gradle.kts` `SupabaseCredentials` generation (verify env vars / GitHub Secrets are populated)
  - `shared/src/iosMain/.../ConnectivityMonitor.kt` (iOS actual)
  - `shared/src/androidMain/.../ConnectivityMonitor.kt` (Android actual)
  - `DiscoveryViewModel.kt` error mapping
- **Reproduction**: install fresh, sign in (or skip auth), tap "パターンを探す" / "Discover Patterns".

### B5: Full screen-by-screen UI/UX audit
- **Scope**: Settings, Discovery, Pull Request Submit dialog, all empty/loaded/error states across 15+ screens (mirror Phase 39.0.2 Sprint A approach).
- **Method**: Capture screenshots from iOS + Android emulators, convene ui-ux-designer + a11y-architect agents in parallel.
- **Expected output**: HIGH/MEDIUM finding list, fix PRs cut atomically per Sprint A precedent (~6 PRs).
- **Phase**: After B1-B4 are closed (cleaner baseline for screenshot capture).

---

## P1 Feature Requests (post-Phase-39-beta)

### F1: Dynamic stitch symbol delivery + Pro subscription
**User vision**:
- Beginner symbols: bundled in-app at install time (offline-first); online updates auto-apply without Store update
- Intermediate / Advanced symbols: Pro subscription only
- Offline + sub-expired enforcement: revoke local access to Pro packs

**Architecture decisions needed before implementation** (agent team协議):
- **Storage**: Supabase tables (recommended given existing infra) vs external CMS (Contentful / Strapi / Sanity).
- **Schema sketch**: `symbol_packs(id, tier {free|pro}, version, manifest_json, payload_json, signed_until, created_at)` + `user_symbol_pack_versions(user_id, pack_id, downloaded_version)` cache table.
- **Update mechanism**: app boot → fetch manifest → diff against local cache → download payload via Supabase REST.
- **Offline + sub-expired enforcement**: signed manifest with expiration timestamp; client validates RevenueCat entitlement on each Pro pack access.
- **User-request workflow**: how do users submit symbol requests? GitHub Issues template? in-app form? dedicated email? — needs product-manager + knitter agent协议.
- **Content authoring tool**: agent team can write directly to Supabase (existing pattern), OR build a minimal admin UI.

**Phase**: Phase 41 or 42 (post-Phase-39-beta).

**References**: ADR-009 (parametric symbols), Phase 30.x Completed (existing static catalog of 35 crochet + 30+ knit symbols).

### F2: Alpha tester per-screen bug reporting + operation logs
**User vision**:
- Testers can report bugs per-screen (with attached screenshot + operation log).
- Detailed operation logs help reproduce bugs.

**Architecture sketch** (agent team协议 needed for finalization):
- **Reporting UX**: floating "報告" / "Report" CTA on every screen (or shake-to-report iOS / 3-finger-long-press Android).
- **Operation log capture**: PostHog session replay (already integrated via `SkeinlyApplication.kt` / `iOSApp.swift` since Phase F1/F2) + custom `posthog.capture("screen_action", properties)` events.
- **Privacy**: needs explicit user consent flow + privacy policy update (PostHog-collected data disclosure).
- **Data retention**: PostHog free tier 1M events/month. Sub if exceeded.
- **Submission target**: GitHub Issues via `.github/ISSUE_TEMPLATE/beta-bug.yml` (already exists from Phase 39.0 prep) — auto-populate with screenshot, screen ID, last-N actions from PostHog session.

**Phase**: Phase 39 beta scope (or early Phase 40 if 39 ships with current bug-template-only flow).

**References**: existing Sentry + PostHog scaffolding, `.github/ISSUE_TEMPLATE/beta-bug.yml`.

---

## Documentation Tasks

### Doc1: Spec doc structure
- Create `.claude/docs/spec/` directory.
- Per-feature spec files (one per major feature):
  - `spec/chart-editor.md` (covers Phase 32, 32.1–32.4, 35.1a–35.1d)
  - `spec/pull-request-flow.md` (covers Phase 38.0–38.4)
  - `spec/segment-progress.md` (covers Phase 34, ADR-010)
  - `spec/discovery-fork.md` (covers Phase 26, 36.0–36.5, ADR-012)
  - `spec/collaboration-history.md` (covers Phase 37.0–37.4, ADR-013)
  - `spec/i18n.md` (covers Phase 33.0–33.4)
  - `spec/auth.md` (covers Phase 3b, 5c, Settings)
  - `spec/realtime-sync.md` (covers Phase 3b+, 14, 15, RealtimeSyncManager)
  - `spec/symbol-catalog.md` (covers Phase 29, 30.x, ADR-008/009/011)
- **Index** in CLAUDE.md `## Specifications` section pointing to each spec file.
- **Goal**: Claude Code agents can pull feature-specific specs into context without reading all of CLAUDE.md (3000+ lines).

### Doc2: CLAUDE.md slimming
- Move "Completed" Phase entries (Phase 1 through Phase 38.4) to `.claude/docs/phase-history/phase-XX.md` per file (or grouped per major phase).
- Keep CLAUDE.md focused on:
  - Project intro + architecture principles
  - Tech stack table
  - Active rules + agent team list + development workflow
  - CI Known Limitations (still active items)
  - **Index** pointing to phase-history + spec docs + active-backlog.md
  - Tech Debt Backlog (still active items)
- **Target**: CLAUDE.md ≤ 500 lines (currently ~3000+).

### Doc3: README + vendor-setup + release-secrets updates (post D0 decision)
- **README.md**: add **RevenueCat** to technical stack table (decision made 2026-04-30); verify Sentry / PostHog mentions exist in body (currently only referenced via release-secrets link).
- **docs/{en,ja}/vendor-setup.md**: add Phase A0c "RevenueCat Setup" (Apple/Google IAP linkage, API key issuance, Webhook secret).
- **docs/{en,ja}/release-secrets.md**: add `REVENUECAT_API_KEY_IOS` / `REVENUECAT_API_KEY_ANDROID` / `REVENUECAT_WEBHOOK_SECRET` entries.
- **App display name update**: pending D0 decision; propagate across i18n + Info.plist + AndroidManifest + README + privacy policy + store metadata.

---

## Implementation Tasks (queued for next session)

### I1: Sentry / PostHog environment tagging
- **Android** (`SkeinlyApplication.kt`): inside `SentryAndroid.init { options -> ... }`, add `options.environment = if (BuildConfig.DEBUG) "development" else "production"`.
- **iOS** (`iOSApp.swift`): inside `SentrySDK.start { options in ... }`, add `options.environment = isDebug ? "development" : "production"`.
- **PostHog** (both platforms): register `$environment` super property on init.
- **Tests**: none (configuration-only change).
- **Phase**: alongside any Sentry/PostHog wiring change — possibly bundled with B4 if that turns out to be a credentials-injection issue.

---

## Pre-existing Tech Debt (cross-reference, still open in CLAUDE.md)

These items are tracked in [CLAUDE.md](../CLAUDE.md) `## Tech Debt Backlog` but are likely to surface during the next session's work — including for cross-reference:

- **Android Adaptive Icon** (`mipmap-anydpi-v26/ic_launcher.xml` missing) — relevant to B1.
- **iOS Discovery thumbnail live-render** (Phase 36.4 → 36.4.1 deferral) — relevant to B5 if Discovery screen audit surfaces it.
- **ChartEditor zoom + WCAG 2.5.8** (post-beta).
- **`reverse-sc` chevron form** (knitter feedback dependent).
- **ViewModel error-message localization** — relevant to F1 / B4 error messages.

---

## Closing Notes

- **Bundle ID** is unified across iOS + Android as `io.github.b150005.skeinly` (verified 2026-04-30 — CLAUDE.md `.android` suffix references in Phase 39.0.1 / 33.3 are stale runtime-package memos, not the actual installed app id).
- **Supabase prod is healthy** (16/16 migrations applied 2026-04-30 via MCP).
- **Vendor setup docs** ([docs/ja/vendor-setup.md](../../docs/ja/vendor-setup.md), [docs/ja/release-secrets.md](../../docs/ja/release-secrets.md)) already exist (2026-04-29) and cover most P0 prereqs in detail. Cross-reference these before authoring new procedure docs.
- **Sentry + PostHog SDKs are already wired in code** on both platforms (`SkeinlyApplication.kt`, `iOSApp.swift`). Dormant until DSN/API key is provisioned.
