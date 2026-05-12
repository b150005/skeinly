# Terminology Spot-Check — Pre-Alpha Key Surfaces (2026-05-12)

> Companion to `terminology-audit-2026-05-10.md` (the full Phase D
> Git→knitter terminology audit). This document covers the **pre-alpha
> A32 spot-check** narrowed to the surfaces a tester touches in the
> first session.

## Scope

Per `docs/en/ops/pre-alpha-checklist.md §57.2` (audit item A32), the
pre-alpha review covers the surfaces below; the **full** layperson-
audience terminology audit remains scheduled post-beta.

| # | Surface | Sources reviewed | Status |
|---|---|---|---|
| 1 | Onboarding (first-launch flow) | `OnboardingScreen.kt` + `title_onboarding_*` / `body_onboarding_*` keys (EN + JA) | ✅ pass |
| 2 | Settings sections + row labels | `SettingsScreen.kt` + `label_*_section` keys | ✅ pass with notes |
| 3 | Sign In / Sign Up / Password reset | `LoginScreen.kt` + `ForgotPasswordScreen.kt` + auth-flow keys | ✅ pass |
| 4 | Main CTAs (Pattern / Project / Chart / Variation create) | `action_create_*` / `action_new_*` keys | ⚠ minor consistency note |
| 5 | Discovery feed (Browse Patterns) | `DiscoveryScreen.kt` + `title_browse_patterns` + `hint_search_public_patterns` | ✅ pass |
| 6 | Suggestion flow (PR/branch/diff terminology) | `title_suggestions` / `action_apply_suggestion` / `title_variations` | ✅ pass (already audited 2026-05-10) |
| 7 | Pro paywall (F1 monetization) | `PaywallScreen.kt` + `body_paywall_*` keys | ✅ pass (updated this session, A3/A8) |

## Method

The reviewer (single-pass, not a roleplayed knitter-agent run) read each
surface's user-visible strings in both EN and JA, asking for every
phrase: **"Would a non-developer knitter understand this on first
read, without context from the help docs?"** Strings that triggered
the question marked as flags below.

## Findings

### Surface 1 — Onboarding ✅

EN copy:
- "Track Your Knitting Projects" + "Keep all your knitting projects organized in one place with row counting and progress tracking."
- "Count Every Stitch" + "Never lose your place again. Tap to count rows, add notes, and attach progress photos."
- "Build Your Pattern Library" + "Save your favorite patterns with gauge, yarn info, and needle sizes for easy reference."
- CTAs: "Get Started" / "Skip"

JA copy (parity verified):
- 「編み物プロジェクトを管理」/「段数カウントと進捗記録で、すべての編み物プロジェクトを一か所に整理できます。」
- 「一目ずつ数える」/「編みかけの場所を見失いません。タップで段数を数え、メモや進捗写真を残せます。」
- 「パターンライブラリを作る」/「お気に入りのパターンをゲージ・糸情報・針号数と一緒に保存できます。」
- CTAs: 「はじめる」/「スキップ」

Both EN and JA copy use audience-appropriate knitting domain terms
(gauge / 糸情報 / 針号数). The target user IS a knitter; these are not
jargon for the audience. No flags.

### Surface 2 — Settings ✅ with notes

Section headers (EN / JA):
- "About" / 「アプリについて」 — ✅
- "Beta" / 「ベータ」 — see note
- "Account" / 「アカウント」 — ✅
- "Danger Zone" / 「危険な操作」 — see note
- "Skeinly Pro" / 「Skeinly Pro」 — ✅

**Note 2.1 — "Beta" section header**: Visible only when `BuildFlags.isBeta = true` (which collapses to `(major == 0)` via the `verifyIosBetaFlag` Gradle invariant). At Phase 40 GA, the major version bumps to 1, the section becomes hidden, and the contents (Send Feedback, Notifications) migrate elsewhere per CLAUDE.md `Tech Debt → Phase 40 GA release prep → Bug-report Settings entry GA opening`. **Action**: not required pre-alpha (the section is only seen by testers who understand "Beta"). Track in Tech Debt.

**Note 2.2 — "Danger Zone" header**: EN is GitHub-style developer convention; consumer audience may read it as alarming-but-recognizable. JA 「危険な操作」 is more natural for a non-developer audience. The EN copy works in this audience because (a) "Danger Zone" is widely used in consumer apps (iOS Settings, Notion, etc.) and (b) the row inside is literally a destructive action (Delete Account) where alarm is appropriate. **Action**: no change.

### Surface 3 — Sign In / Sign Up / Password reset ✅

EN: "Sign In", "Sign Up", "Sign Out", "Email", "Password", "Forgot password?", "Enter your email and we'll send you a reset link."

All strings are industry-standard, layperson-clear. No flags.

### Surface 4 — Main CTAs ⚠ minor consistency note

Inventory:
- "New Project" / "Create Project" — `action_new_project` / `action_create_project`. ✅
- "New Pattern" / "Create Pattern" — `action_new_pattern` / `action_create_pattern`. ✅
- "Add Photo" / "Add Note" — `action_add_photo` / `action_add_note`. ✅
- "Create chart" (lowercase 'c' in 'chart') — `action_create_chart`. ⚠ inconsistent with "Create Pattern" / "Create Project" Title Case.
- "Add layer" (lowercase 'l' in 'layer') — `action_add_layer`. ⚠ inconsistent with "Add Photo" / "Add Note" Title Case.
- "New variation" (lowercase 'v') — `action_create_variation`. ⚠ inconsistent with "New Project" Title Case.

**Note 4.1 — Capitalization consistency**: The Chart Editor / Variation surface uses sentence-case CTAs ("Create chart", "Add layer", "New variation") while the higher-level navigation surface uses Title Case ("Create Pattern", "New Project"). This is a minor visual inconsistency, not a comprehension issue.

**Action**: out of scope for pre-alpha. The cost (3 string updates + 3 i18n keys' JA mirrors + iOS xcstrings updates + visual review) outweighs the benefit (purely cosmetic). Reopen at the post-beta full terminology audit when the surface-by-surface sweep covers every CTA.

### Surface 5 — Discovery feed ✅

EN: "Browse Patterns" (title + entry button) + "Search public patterns…" (search hint).
JA: 「パターンを探す」+「公開パターンを検索…」.

Both convey the action without jargon. The Discovery feed itself was renamed from "Discovery" to "Browse Patterns" per the Phase D 2026-05-10 audit — confirmed regression-free here.

### Surface 6 — Suggestion / PR / branch / diff flow ✅

This is the surface that underwent the major Phase D 2026-05-10
terminology audit (`audits/terminology-audit-2026-05-10.md`). The
spot-check verifies no regressions have entered since:

| Git mental model | Skeinly user-facing (EN) | Skeinly user-facing (JA) | Confirmed in keys |
|---|---|---|---|
| pull request | Suggestion | 提案 | `title_suggestions` / `title_suggestion_detail` ✅ |
| branch | Variation | アレンジ | `title_variations` / `action_create_variation` ✅ |
| commit / revision | Version | バージョン | Internal model only — no user-visible verbiage uses "commit" ✅ |
| merge | Apply changes | 変更を反映 | `action_apply_changes` / `action_apply_suggestion` ✅ |

No regression. The renamed model classes (`Suggestion` / `ChartVariation` / `ChartVersion`) are internal; user-visible copy uses the knitter-friendly terminology throughout.

### Surface 7 — Pro paywall ✅

This surface was updated **this session** as part of pre-alpha audit
item A3/A8 (commit 62cc6b8). Result of the update:

- Pitch line `body_paywall_pitch` rewritten from "Unlock the full
  symbol library and support indie development." (vague) to "Skeinly
  Pro gives you access to premium content and helps support
  independent development." (specific).
- New `body_paywall_features` key adds 3 enumerated bullets
  (premium symbol packs / future Pro-only features / development
  support), required by Apple Guideline 3.1.2(c).
- `body_paywall_trial_disclosure` expanded to point at
  Settings → Manage Subscription for cancellation.

No further changes needed for terminology.

## Aggregate result

| Status | Count |
|---|---|
| ✅ pass | 6 surfaces |
| ⚠ note (no action) | 1 surface (Main CTAs capitalization) |
| ❌ blocker | 0 |

**Verdict**: pre-alpha A32 is **closed**. No blockers for alpha
invite. The Main CTAs capitalization is filed as a polish item for
the post-beta full terminology audit.

## Cross-reference

- `audits/terminology-audit-2026-05-10.md` — full Phase D Git→knitter audit
- `.claude/CLAUDE.md` `## Vocabulary mapping` — canonical EN/JA mapping
- `docs/en/ops/pre-alpha-checklist.md §57` — A32 closure record
- `docs/en/ops/pre-alpha-checklist.md` `Pre-Phase-40 polish` — future Capitalization-pass note

## Update history

| Date | Change | By |
|---|---|---|
| 2026-05-12 | Initial spot-check — pre-alpha audit item A32 | b150005 |
