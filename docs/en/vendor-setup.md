# Vendor Account Setup — Phase A0 (alpha pre-launch)

> Japanese translation: [docs/ja/vendor-setup.md](../ja/vendor-setup.md)

Single source of truth for one-time vendor portal setup before the first alpha (Phase 39) tag is pushed. Covers Apple Developer Portal + App Store Connect + Google Play Console + Universal Links + RevenueCat. Per-secret OBTAIN/REGISTER procedures live in [release-secrets.md](release-secrets.md). Recurring operational tasks (release, beta tester invite, incident response) live in [ops/](ops/).

Use this doc as a **checklist** while clicking through the vendor portals. The "why" behind each decision is in [ADR-016](adr/016-phase-41-revenuecat-subscription.md), [ADR-017](adr/017-phase-24-push-notifications.md), and [pre-alpha-checklist.md](ops/pre-alpha-checklist.md); link out rather than restate.

## Skeinly constants

These values are reused throughout every vendor form.

| Field | Value |
|---|---|
| Bundle ID (iOS) | `io.github.b150005.skeinly` |
| Application ID (Android) | `io.github.b150005.skeinly` |
| Subscription Product ID (iOS — monthly) | `io.github.b150005.skeinly.pro.monthly` |
| Subscription Product ID (iOS — yearly) | `io.github.b150005.skeinly.pro.yearly` |
| Subscription Product ID (Android) | `io.github.b150005.skeinly.pro` (with `monthly` + `yearly` base plans) |
| Free Trial duration | 7 days |
| Pricing | $3.99 USD / month + $24.99 USD / year |
| RevenueCat entitlement | `entlaaca26b181` (Skeinly Pro) |
| RevenueCat packages | `$rc_monthly` + `$rc_annual` |
| App Name | `Skeinly` |
| Default Language | English (U.S.) |
| Apple Developer Team ID | (your 10-char ID; populate after enrollment) |
| Privacy Policy URL | `https://b150005.github.io/skeinly/privacy-policy/` |
| Account deletion URL | `https://b150005.github.io/skeinly/account-deletion/` |
| Support email | `skeinly.app@gmail.com` |
| Support URL | `https://github.com/b150005/skeinly/issues` |

## Prerequisites

- **Apple Developer Program enrollment** — $99/yr at <https://developer.apple.com/programs/>
- **Google Play Console publisher account** — $25 one-time at <https://play.google.com/console/signup>
- **Mac with Xcode 26+** — for Keychain Access and `.p12` export. See [README Prerequisites](../../README.md#prerequisites)
- **`gh` CLI authenticated** — `gh auth login`
- **RevenueCat account** — free at <https://app.revenuecat.com> (paid Mid-Market tier at $2.5K MRR)

## Phase overview

Do each phase in order. Phases within `A0a` / `A0b` / `A0c` / `A0d` are not interleavable — each builds on artifacts from the previous one.

| Phase | What it covers | Depends on |
|---|---|---|
| **A0a** | Apple Developer Portal: App ID, APNs key, certs, profiles, ASC API key | Apple enrollment |
| **A0b** | App Store Connect: app record, IAP products, Free Trial, ASSN V2 webhook, sandbox testers, App Privacy | A0a |
| **A0c** | Google Play Console — IAP: subscription product, base plans, Free Trial offers, license testers, Pub/Sub + RTDN | Play Console enrollment + app upload |
| **A0d** | Google Play Console — App content + Store Listing + Internal Testing | A0c |
| **A0e** | Universal Links / App Links (AASA + assetlinks.json) | A0a + A0c |
| **A0f** | RevenueCat: project, app links, product import, entitlement + offering binding, webhook | A0b + A0c |

---

## Phase A0a — Apple Developer Portal

### A0a-1: Create the App ID

- [ ] <https://developer.apple.com/account> → **Certificates, Identifiers & Profiles** → **Identifiers** → **+** → **App IDs** → **App**
- [ ] Description: `Skeinly`, Bundle ID: **Explicit** → `io.github.b150005.skeinly`
- [ ] Enable 4 capabilities: **Sign In with Apple** (Configure → Enable as a primary App ID), **Push Notifications**, **Associated Domains**, **In-App Purchase**
- [ ] Continue → Register

Adding capabilities later forces Provisioning Profile regeneration. Only enable what alpha actually needs.

### A0a-2: Generate the APNs Auth Key (`.p8`)

- [ ] **Keys** → **+** → Name `skeinly APNs` → enable **Apple Push Notifications service (APNs)** → Continue → Register
- [ ] **Download the `.p8` file immediately** (one-time download; filename `AuthKey_<KEY_ID>.p8`)
- [ ] Note the 10-char **Key ID**
- [ ] Save both to a password manager
- [ ] Base64-encode + register as `APPLE_APNS_KEY_BASE64` Edge Function secret per [release-secrets.md](release-secrets.md#supabase-edge-function-secrets)
- [ ] Register `APPLE_APNS_KEY_ID` Edge Function secret (10-char value)

The same key works for both production and TestFlight builds.

### A0a-3: Apple Distribution Certificate

See [release-secrets.md §1](release-secrets.md#1-apple_distribution_cert_base64). Existing cert covers all 4 capabilities.

### A0a-4: Provisioning Profile

CI fetches the App Store Distribution profile at runtime via `sigh` (using the ASC API key from A0a-5). No GitHub Secret carries the profile bytes.

**Order matters**: regenerate the profile **after** A0a-1 so all 4 capabilities are baked in. Pre-existing profiles miss new capabilities.

### A0a-5: App Store Connect API Key

See [release-secrets.md §4–6](release-secrets.md#4-app_store_connect_api_key_base64). Register as `APP_STORE_CONNECT_API_KEY_BASE64` GitHub Secret. Same key powers TestFlight upload via fastlane.

---

## Phase A0b — App Store Connect (App + IAP + Sandbox)

### A0b-1: Create the App record

- [ ] <https://appstoreconnect.apple.com> → **My Apps** → **+** → **New App**
- [ ] Platform: iOS, Name `Skeinly`, Primary Language **English (U.S.)**, Bundle ID `io.github.b150005.skeinly`, SKU `skeinly-001`, User Access **Full Access** → Create

### A0b-2: Subscription Group

- [ ] App → **Monetization → Subscriptions** → **+** → Reference Name `Skeinly Pro` → Create
- [ ] **App Store Localizations**: add EN (U.S.) + Japanese (Japan), both with Subscription Group Display Name `Skeinly Pro`

### A0b-3: Create the Monthly product

- [ ] Inside `Skeinly Pro` group → **Create**
- [ ] Reference Name `Skeinly Pro Monthly`, Product ID **`io.github.b150005.skeinly.pro.monthly`** ⚠️ permanent
- [ ] Subscription Duration **1 Month** → Save
- [ ] Subscription Prices → US **$3.99** → review JP auto-converted price (typically ¥600 range, accept default) → Confirm
- [ ] Availability: all territories
- [ ] App Store Localizations — EN (U.S.):
  - Display Name `Skeinly Pro Monthly` (30 char limit)
  - Description `Unlock all Pro features. Auto-renews monthly.` (55 char limit per locale)
- [ ] App Store Localizations — Japanese:
  - Display Name `Skeinly Pro 月額プラン`
  - Description `Skeinly Pro の全機能を解放。毎月自動更新。`

### A0b-4: Create the Yearly product

- [ ] Same flow with: Reference `Skeinly Pro Yearly`, Product ID **`io.github.b150005.skeinly.pro.yearly`**, Duration **1 Year**, US price **$24.99** (JP auto-converts ¥3,600–¥4,000)
- [ ] EN Display `Skeinly Pro Yearly`, EN Description `Unlock all Pro features. Auto-renews yearly. Save 40%+.` (55 chars exactly)
- [ ] JA Display `Skeinly Pro 年額プラン`, JA Description `Skeinly Pro の全機能を解放。毎年自動更新、40% お得。`

### A0b-5: Subscription levels

- [ ] Group → **Edit Order** → place **both products at Level 1** → Save

Same level = crossgrade behavior at next renewal. Both grant the same RC entitlement.

### A0b-6: 7-day Free Trial — Monthly

- [ ] Monthly product detail → **Subscription Prices** → **View all Subscription pricing** → **Set up Introductory Offer**
- [ ] Countries: all (match availability). Start: today. **End: blank** (permanent). Offer Type: **Free Trial**. Duration: **1 Week** (7 days). Price: $0 auto. → Confirm

⚠️ Introductory offers cannot be edited after creation — only delete + recreate.

Apple auto-enforces "one redemption per customer per subscription group" — both products share the count. No operator action.

### A0b-7: 7-day Free Trial — Yearly

- [ ] Same as A0b-6 against `Skeinly Pro Yearly`

### A0b-8: App Store Server Notifications V2

- [ ] RevenueCat Dashboard → Apps & providers → Skeinly iOS → **Apple Server to Server notification settings** → copy URL
- [ ] ASC → App → **General → App Information → App Store Server Notifications**:
  - Production Server URL: paste the RC URL → Save
  - Sandbox Server URL: paste the **same** URL → Save
- [ ] If a Notification Version picker appears, choose **Version 2**. If absent, V2 is auto-applied (Apple shipped a default-to-V2 UI change without doc update)
- [ ] Verify: RC Dashboard → **Send test event** → 200 OK + "Last received" updates

### A0b-9: Sandbox testers

- [ ] ASC home → **Users and Access** → **Sandbox** tab → **+** → create at least 2 (one US + one JP recommended)
- [ ] Use **plus-subaddressing on a real Gmail inbox** (e.g. `skeinly.app+sandbox-us-1@gmail.com`) — Apple's own help page uses the same pattern
- [ ] First Name = cohort (`Core` / `Beta`), Last Name = `Tester-<locale>-<n>` (these cannot be edited after creation)
- [ ] On device: Settings → [Apple ID] → **Media & Purchases** → Sign Out (NOT iCloud top-level), then sign in at the StoreKit prompt

### A0b-10: App Privacy declaration

- [ ] App detail → **App Privacy** → Privacy Policy URL `https://b150005.github.io/skeinly/privacy-policy/`
- [ ] Click **Edit** on "Data Collection" → checkbox dialog opens listing 14 categories
- [ ] **Check exactly these 11 boxes** (Apple labels in JA; cross-references to Skeinly's data flow in [pre-alpha-checklist.md §35.1 A6](ops/pre-alpha-checklist.md#a6-data-safety-form)):

| Apple category | Sub-option (check this) | Skeinly source |
|---|---|---|
| **連絡先情報** (Contact Info) | **名前** (Name) | `display_name` in Supabase profile — freeform; users may put real names |
| 連絡先情報 | **メールアドレス** (Email Address) | Supabase Auth email (raw + hashed both count per Apple's note) |
| **ユーザコンテンツ** (User Content) | **写真またはビデオ** (Photos or Videos) | Project progress photos in Supabase Storage |
| ユーザコンテンツ | **カスタマーサポート** (Customer Support) | Bug report content → GitHub Issue via `submit-bug-report` Edge Function |
| ユーザコンテンツ | **その他のユーザコンテンツ** (Other User Content) | Chart data, patterns, comments, suggestions |
| **ID** (Identifiers) | **ユーザID** (User ID) | Supabase UUID; also covers `display_name` as a handle per Apple's definition ("スクリーン名、ハンドル、アカウントID") |
| ID | **デバイスID** (Device ID) | APNs device token + PostHog `distinct_id` (opt-in) |
| **購入** (Purchases) | **購入** (Purchases) | RevenueCat subscription state via webhook (`subscriptions` table — Pro entitlement, product, expires_at) |
| **使用状況データ** (Usage Data) | **製品の操作** (Product Interaction) | PostHog page views / taps / scrolls (opt-in via consent screen) |
| **診断** (Diagnostics) | **クラッシュデータ** (Crash Data) | Sentry crash logs (opt-in) |
| 診断 | **パフォーマンスデータ** (Performance Data) | Sentry performance (opt-in; matches Apple's "起動時間、ハング率、エネルギー使用量") |

- Every other sub-option stays unchecked. Two non-obvious ones worth knowing:
  - **財務情報 > 支払い情報** — Apple's inline form note: StoreKit / RevenueCat flows are exempt because "デベロッパは支払い情報にアクセスできません".
  - **検索履歴** — pattern search queries are transient request params, not persisted off-device.

- [ ] **保存** → Apple flags each checked data type with a yellow ⚠️ "X を設定" button on the dashboard. Click each one to open a follow-up modal listing 6 purposes ("該当するものをすべて選択") and fill it per the table below.

**Per-data-type purpose selection** — open each modal, check only what's listed, save:

| Modal title (Apple) | Check these (only) |
|---|---|
| 名前 | アプリの機能 |
| メールアドレス | アプリの機能 |
| 写真またはビデオ | アプリの機能 |
| カスタマーサポート | アプリの機能 |
| その他のユーザコンテンツ | アプリの機能 |
| ユーザID | アプリの機能 |
| デバイスID | **アプリの機能 + アナリティクス** |
| 購入履歴 | アプリの機能 |
| 製品の操作 | **アナリティクス** |
| クラッシュデータ | アプリの機能 |
| パフォーマンスデータ | アプリの機能 |

- [ ] After 次へ on each modal, Apple asks:
  - **Linked to user?** → **Yes** (all 11)
  - **Used for tracking?** → **No** (all 11)

Why this mapping (one-liners):

- クラッシュデータ / パフォーマンスデータ are NOT アナリティクス — Apple's アプリの機能 definition explicitly lists "crash minimization" + "scaling and performance"; アナリティクス is user-behavior evaluation, not app-behavior diagnostics.
- ユーザID stays at アプリの機能 only because `PostHog.identify(supabaseUid)` is intentionally unwired (CLAUDE.md "strict anonymity stance").
- デバイスID gets both: APNs token = push routing (アプリの機能), PostHog `distinct_id` = device-level analytics linkage (アナリティクス).
- メール / 名前 do NOT need デベロッパの広告またはマーケティング — transactional auth emails are not marketing under Apple's definition.

### A0b-11: Import products into RevenueCat (manual dashboard step)

- [ ] RC Dashboard → **Project Settings → Apps & providers** → Skeinly iOS → **Products** → click the **Import** button next to **+ New**
- [ ] Confirm import of both `io.github.b150005.skeinly.pro.monthly` + `io.github.b150005.skeinly.pro.yearly`

Required even when ASC ↔ RC OAuth is wired. The MCP binding (A0f-4) is the next step after this.

### A0b-12: ASC verification

- [ ] Subscription Group `Skeinly Pro` with EN + JA localizations
- [ ] Both products exist with correct duration + price + EN/JA localizations
- [ ] Both at Level 1
- [ ] Free Trial 7-day on both
- [ ] Production + Sandbox Server URLs set with V2
- [ ] ≥ 1 sandbox tester registered (one US + one JP recommended)
- [ ] Both products visible in RC dashboard after Import

The「メタデータが不足」(Missing Metadata) badge persists until App Review Screenshot + promotional 1024×1024 image are added. Phase 39 alpha does NOT need those — sandbox purchase still works. Address before Phase 40 GA submission.

### A0b — Phase-40-GA-only ASC surfaces (skip for Phase 39 alpha)

These ASC surfaces only render on the public App Store product page. Phase 39 alpha is TestFlight Internal only, so they have zero display value during alpha. Skipping is the correct call; address them as part of the Phase 40 GA submission audit.

- **App 情報 → アプリのアクセシビリティ** (App Accessibility) — declares which assistive-tech features the app supports end-to-end (VoiceOver / Voice Control / Dynamic Type / Dark Interface / Differentiate Without Color / Sufficient Contrast / Reduce Motion / Captions / Audio Descriptions). Apple compliance: you may only declare features you've **verified work end-to-end for core tasks**. False declarations are App-Review rejectable. Skeinly's a11y audit is mid-flight (see CLAUDE.md Tech Debt: A25 Reduce Motion iOS SwiftUI sweep + M5 ChartEditor zoom WCAG 2.5.8 both pre-Phase-40-GA). Defer until the audit closes.
- **App プライバシー → プライバシーニュートリションラベル — App Review Information screenshots** — required for IAP review only (the IAP "メタデータが不足" badge above).
- **成長とマーケティング** group (App 内イベント / カスタムプロダクトページ / プロダクトページの最適化 / プロモーションコード / Game Center) + **フィーチャー → ノミネート** — App Store **public product page** marketing surfaces; render only on the public App Store, which does not exist for Phase 39 TestFlight Internal. These are NOT RevenueCat's territory — ASC handles pre-install marketing (App Store product page content), RevenueCat handles post-install monetization (in-app paywall + subscription offerings). The two layers do not overlap. Notably, ASC's "プロモーションコード" issues codes that give a user free access to the app or an IAP product, whereas RC's "Promotional Offers" issues subscription discounts / extensions to existing subscribers — completely separate mechanisms, both usable post-GA. Game Center is N/A (Skeinly is not a game).

### A0b common gotchas

- Product ID is permanent. Triple-check before saving.
- Duration cannot change after App Review.
- Introductory offer cannot be edited — only delete + recreate.
- Description char limit is 55 per locale (operator-verified 2026-05-13; Apple docs still say 45). Display Name caps at 30.
- Sandbox tester email must never have been used as a real Apple ID. Plus-subaddressing avoids needing multiple real inboxes (Gmail / iCloud / Fastmail / ProtonMail support `+`; Outlook does NOT).
- ~1 hour metadata propagation delay before sandbox sees a new product.

---

## Phase A0c — Google Play Console (App + IAP + License testers + RTDN)

### A0c-1: Create the app record

- [ ] <https://play.google.com/console> → **All apps** → **Create app**
- [ ] App name `Skeinly`, Default language **English – en-US**, App or game **App**, Free or paid **Free**
- [ ] Accept declarations → Create

### A0c-2: Create the Subscription Product

- [ ] App → **Monetize with Play → Products → Subscriptions** → **Create subscription**
- [ ] Product ID **`io.github.b150005.skeinly.pro`** ⚠️ permanent + non-reusable
- [ ] Name `Skeinly Pro` (≤55 chars, user-visible)
- [ ] Create → **Edit subscription details** → add benefits (≤4, ≤40 chars each):
  - `Unlimited chart creation`
  - `Advanced pattern analysis`
  - `Priority support`
- [ ] Description (internal-only, ≤200 chars): `Skeinly Pro auto-renewable subscription. Monthly ($3.99) + yearly ($24.99) base plans, 7-day free trial. RevenueCat entitlement entlaaca26b181 via $rc_monthly / $rc_annual packages.`

⚠️ Do NOT mention "free trial" or specific prices in benefit text — Play policy prohibits it.

### A0c-3: Monthly base plan

- [ ] Subscription details → **Add base plan**
- [ ] Base Plan ID **`monthly`** (single word, `a-z 0-9 -` only, ≤63 chars), Type **Auto-renewing**, Billing period **Monthly**
- [ ] Grace period 3 days (or 7 for better retention), Account hold 30 days
- [ ] User's base plan and offer changes: **Charge on next billing date** (defer billing to next renewal)
- [ ] Resubscribe: Enabled, Tags: empty, Backwards compatible: **mark** (only this plan)
- [ ] **Manage country/region availability** → US + JP + all target markets + **New countries/regions** (auto-include future markets) → Save
- [ ] **Update prices** → enter base **`3.99`** USD → review JP auto-conversion (¥600 range) → Save
- [ ] **Activate**: click `monthly` ID text (or `›` arrow at far right of the row — may need horizontal scroll) → opens edit page → scroll to bottom → click **Activate** / 「有効にする」. NOT in the 3-dot ⋮ menu.

### A0c-4: Yearly base plan

- [ ] Same flow with: Base Plan ID **`yearly`**, Billing period **Yearly**, Grace 7 days (annual recommendation)
- [ ] Charge on next billing date, Resubscribe enabled, Tags empty, Backwards compatible **NOT marked**
- [ ] Base price **`24.99`** USD (JP ¥3,600–¥4,000)
- [ ] Activate via the same edit-page-bottom button

### A0c-5: Free Trial offer — Monthly

- [ ] Subscription details → **基本プランと特典** section → top-right **「特典を追加」** link → pick `monthly` in dialog → **「特典を追加」** button → form opens
- [ ] Offer ID **`monthly-trial`** ⚠️ permanent, `a-z 0-9 -` only, ≤63 chars
- [ ] Base plan & availability: pre-selected `monthly`, countries 174/174 inherit
- [ ] **提供の条件**: **新規ユーザーの獲得 (New customer acquisition)**
- [ ] **資格** (sub-form): **この定期購入を利用したことがない (Has not used this subscription)** (default; subscription-product scope, future-proof for multi-product later)
- [ ] Tags: empty
- [ ] Scroll to **「段階」** section → **「段階を追加」** → **無料トライアル (Free trial)** → Duration **7 days**
- [ ] Save → click **Activate** at bottom of offer edit page

### A0c-6: Free Trial offer — Yearly

- [ ] Same flow against `yearly` base plan: Offer ID `yearly-trial`, same eligibility + qualification + 7-day phase
- [ ] Save → Activate

### A0c-7: Japanese localization

- [ ] **Grow users → Translations → Manage translations → Select languages → Japanese (Japan) (ja-JP) → Apply**
- [ ] Add ja-JP strings: Subscription Name `Skeinly Pro`, Benefit 1 `無制限のチャート作成`, Benefit 2 `高度なパターン分析`, Benefit 3 `優先サポート`

Google's free machine translation does not cover Japanese — enter manually.

### A0c-8: Publish to Internal Testing track

- [ ] Build + upload via `release.yml` CI flow ([release.md](ops/release.md)). `gradle-play-publisher` uploads in `DRAFT` state.
- [ ] Pre-requisite for license tester purchases: app must be published to at least one track (Internal Testing minimum). Drafts reject purchases.

⚠️ First Bundle upload requires Phase A0d (App content + store listing) to be complete. Run A0d before clicking "Start rollout to testers".

### A0c-9: License testers

- [ ] Play Console → **Settings → License testing**
- [ ] Add tester Google account emails (one per line, up to 2,000)
- [ ] Save changes

Accelerated test renewals (per RC tester): free trial 3 min, monthly 5 min, yearly 30 min, grace 5 min.

### A0c-10: Pub/Sub topic for RTDN

- [ ] Recommended path: RC Dashboard → Skeinly Android → service credentials → **Connect to Google** (RC generates the topic ID for you)
- [ ] Or manually: [GCP Console → Pub/Sub → Topics → Create topic](https://console.cloud.google.com/cloudpubsub/topicList) → ID `play-billing-notifications`
- [ ] Enable Pub/Sub API at <https://console.cloud.google.com/flows/enableapi?apiid=pubsub>
- [ ] Topic → Permissions tab → **Add Principal**:
  - New principal: `google-play-developer-notifications@system.gserviceaccount.com`
  - Role: **Pub/Sub Publisher** (`roles/pubsub.publisher`)
  - Save

⚠️ If your GCP org enforces Domain Restricted Sharing, add a `system.gserviceaccount.com` exception or the grant fails.

### A0c-11: Configure RTDN in Play Console

- [ ] App → **Monetize with Play → Monetization setup → Real-time developer notifications**
- [ ] Enable: ✅, Topic name: `projects/<gcp_project>/topics/<topic_name>` from A0c-10
- [ ] Notification content: **Subscriptions, voided purchases, and all one-time products**
- [ ] **Send Test Message** → expect success
- [ ] Save changes

### A0c-12: Verify RTDN in RevenueCat

- [ ] RC Dashboard → Skeinly Android → check **Last received** timestamp updates
- [ ] (Optional) enable **Track new purchases from server-to-server notifications**

### A0c-13: Import products into RevenueCat

- [ ] RC Dashboard → **Project Settings → Apps & providers** → Skeinly Android → **Products** → **Import** button
- [ ] Confirm import of `io.github.b150005.skeinly.pro:monthly` + `io.github.b150005.skeinly.pro:yearly` (colon-separated RC identifier format for post-Feb-2023 Play products)

### A0c-14: Play Console IAP verification

- [ ] Subscription Product `io.github.b150005.skeinly.pro` exists
- [ ] Base plan `monthly` Active, marked backwards-compatible
- [ ] Base plan `yearly` Active
- [ ] Offer `monthly-trial` Active, 7-day phase
- [ ] Offer `yearly-trial` Active, 7-day phase
- [ ] ja-JP translation added
- [ ] App published to Internal Testing
- [ ] ≥ 1 license tester (one US + one JP recommended)
- [ ] Pub/Sub topic + IAM Publisher grant in place
- [ ] RTDN configured + test message succeeded
- [ ] Both Android products visible in RC dashboard after Import

### A0c common gotchas

- Subscription Product ID + Base Plan ID have different char sets (product allows `_` `.`; base plan only `a-z 0-9 -`). Single-word base plans sidestep the issue.
- Only one base plan can be backwards-compatible. Mark monthly.
- License tester needs app published to a track (draft rejects purchases).
- RC product identifier uses `subscription_id:base_plan_id` colon separator for Android (vs `.` for iOS).
- RC's Play Console service credentials need ~36 hr warmup before Pub/Sub Connect works.

---

## Phase A0d — Google Play Console (App content + Store Listing + Internal Testing)

Required for the **first App Bundle upload** to Internal Testing. Most settings live under **App content** in the left nav.

### A0d-1: Privacy policy

- [ ] App content → **Privacy policy** → URL `https://b150005.github.io/skeinly/privacy-policy/` → Save

Verify the URL returns 200 in a separate tab before saving (GitHub Pages deploy delay ~1–5 min).

### A0d-2: App access (with reviewer demo accounts)

- [ ] App content → **App access** → select **All or some functionality is restricted** (Skeinly requires sign-in)
- [ ] Click **Add instructions** → modal opens

Skeinly uses Supabase email+password (no username). Reviewer accounts use Gmail plus-subaddressing per the [`beta-testing.md`](ops/beta-testing.md) convention:

| Platform | Email | Used in |
|---|---|---|
| iOS (ASC App Review Information) | `skeinly.app+review-ios@gmail.com` | Apple reviewer only |
| **Android (this step)** | `skeinly.app+review-android@gmail.com` | Google reviewer only |

Splitting per platform avoids demo-state pollution when review windows overlap and improves Supabase Auth audit-trail clarity.

| Field | Limit | Value |
|---|---|---|
| Instructions name | 60 | `Skeinly Reviewer Access (Android)` (33 chars) |
| Username, email, or phone | 100 | `skeinly.app+review-android@gmail.com` |
| Password | 100 | 16+ char strong password generated via 1Password |
| Other information needed to access the app | 500 | Paste the 478-char EN sample below |
| There is no other information... (checkbox) | — | **Leave unchecked** |

Play's helper text + right-rail guidance require English. Sample (478 chars, English only):

```
Sign in with the credentials above (Supabase email+password auth, no 2FA). Demo data is pre-seeded: 3 patterns (rectangular / polar / variation), 1 in-progress project with row counter and photos, 1 active Suggestion in Discovery. The account is in Pro state, so Settings > Upgrade is reachable without a purchase. IAP runs in Play Billing sandbox via license tester registration. Delete the account via Settings > Account or https://b150005.github.io/skeinly/account-deletion/.
```

If you re-edit and overflow 500: drop the parenthetical pattern types first, then the Account deletion sentence.

⚠️ **Demo account prerequisites** (Supabase-side, before saving here):

- [ ] Create `skeinly.app+review-ios@gmail.com` user in Supabase Auth Dashboard (manually flip to email-confirmed)
- [ ] Create `skeinly.app+review-android@gmail.com` the same way
- [ ] Run idempotent seed for both accounts (3 patterns / 1 project / 1 Suggestion / Pro state)
- [ ] Grant Skeinly Pro entitlement to both in RevenueCat via `grant-customer-entitlement` (so the Pro state is already visible without forcing a paywall purchase)

After adding instructions, leave the "**提供した認証情報を Android がパフォーマンスやアプリの互換性のテストに使用することを許可する**" checkbox **enabled** (default ON):

- This grants Google's **Pre-Launch Report** infrastructure permission to sign in with the demo credentials and auto-test the app across Google's real-device lab (multiple OEMs / screen sizes / API levels). Results appear at テストとリリース → Pre-launch reports minutes after each App Bundle upload.
- Value-for-cost is high — individual developers cannot maintain a Samsung / Pixel / low-RAM / foldable matrix; Google's lab runs every release for free.
- Trust-model is unchanged: the credentials are already shared with Google human reviewers at App Review time; this just adds Google's automated test infrastructure as another consumer.
- Operational caveat: bot traffic will hit the demo account periodically. The idempotent seed above is the right shape; the seed should not assume a "fresh state" between Pre-Launch runs. Sentry / PostHog will receive some bot-origin events — filter by `app_user_id` if metrics noise becomes a concern.

### A0d-3: Ads

- [ ] App content → **Ads** → **No, my app does not contain ads** (Skeinly is ad-free; monetization is IAP only)

### A0d-4: Content rating (IARC)

Step 1 of 3 — **カテゴリ** (Category) page:

- [ ] Email: **`skeinly.app+rating@gmail.com`** (plus-alias dedicated to content-rating correspondence — the form helper notes the address "may be shared with rating bodies and IARC", so this lane covers IARC + every regional body that issues a Skeinly rating: ESRB / PEGI / USK / CERO / ClassInd / ACB / etc.)
- [ ] Category: **その他のすべてのアプリの種類** (All other app types) — Skeinly's core is project + pattern management (single-user); UGC / Discovery / comments / suggestions are secondary. NOT ゲーム (not a game) and NOT ソーシャルまたはコミュニケーション (communication is not Skeinly's primary purpose, unlike Facebook / Twitter / Skype). The "all other" category also routes the questionnaire down a shorter path focused on UGC / digital purchases — game-category questionnaires drag in violence / gambling / sexual-content branches Skeinly doesn't need.
- [ ] ☑ **International Age Rating Coalition (IARC) の利用規約に同意します** — tick the agreement checkbox
- [ ] **次へ** → Step 2 アンケート (the actual IARC questionnaire)

**Expected outcome**: 12+ across PEGI / USK / IARC / ACB, Teen on ESRB, 14+ on Russia RARS. NOT Everyone, NOT 4+. This is the standard rating for any app with significant UGC + user-to-user interaction (Instagram / Twitter / Reddit / Pinterest land at the same 12+ band). PEGI / USK / ESRB rate UGC-enabled apps 12+ automatically regardless of moderation quality, on the principle that UGC inherently can include rated content. Skeinly's collaboration is core to product vision (Phase 36-38), so this is unavoidable and acceptable. Internal Testing track does not display the rating; A0d-5 Target Audience 18+ ≥ IARC 12+ keeps the declaration consistent; Apple's age rating (separate questionnaire) typically lands at 4+ or 9+ because Apple does NOT auto-12+ for UGC apps.

Step 2 is a 5-section questionnaire — only the first section is gated; the rest show their sub-questions inline. Answer per the matrices below.

| Section | Has top-level gate? | Skeinly approach |
|---|---|---|
| **ダウンロード済みアプリ** | Yes (はい / いいえ at top) | Answer **いいえ** — bundled content is JIS knitting / crochet glyphs (70 symbols) + UI strings + app icon, none of which is rating-relevant. Saying はい would expand 10+ sub-questions (暴力 / 血液 / 流血 / 恐怖 / 性的 / ギャンブル / 言葉 / 規制物質 / 下品なユーモア etc.) all resolving to いいえ anyway — pick いいえ for the shorter, lower-risk path. |
| **ユーザー コンテンツの共有** | No (sub-questions visible inline) | Answer the 8 sub-questions per the matrix below. |
| **オンライン コンテンツ** | No (sub-questions visible inline) | Sub-questions covered below. Skeinly's symbol packs (Phase 41 dynamic infrastructure: `symbol_packs` + `symbol_pack_locales` Supabase tables + RPC delivery) are server-delivered curated content — not UGC, not bundled — so the section applies. Knitting/crochet glyphs only, no violence / sex / language / drugs / gambling, fully moderated since Skeinly team authors them. |
| **年齢制限が適用される製品または活動の宣伝または販売** | No (sub-questions visible inline) | No ads, no sales / promotion of alcohol / tobacco / firearms / lottery / etc. The IAP subscription itself is not age-restricted. All sub-questions answer いいえ. |
| **その他** | No (sub-questions visible inline) | 5 sub-questions visible directly — answer matrix below. |

**ユーザー コンテンツの共有 sub-questions** (expand after the gate answers はい):

| Sub-question | Answer | Why |
|---|---|---|
| 音声通信 / SMS / 画像オーディオ共有で交流・コンテンツ交換? | **はい** | [Google's policy guidance (answer/11070862)](https://support.google.com/googleplay/android-developer/answer/11070862) reads broader than the JA UI wording suggests: "Yes" applies when users can freely exchange UGC including **comments, photo sharing, or any other UGC exchange** — not just voice / SMS / messaging-style audio-image. Skeinly's comments on shared patterns + Discovery image sharing + Suggestion proposals all match. |
| UGC が **主要な** コンテンツソース? | **はい** | Skeinly's only non-UGC bundled content is the 70-symbol JIS catalog, which is primitives (alphabet-equivalent), not "content". Everything users actually see as content (patterns / charts / comments / suggestions) is user-generated. Collaboration is core to the product vision per Phase 36 Discovery+Fork / Phase 37 Collaboration Core / Phase 38 Pull Request workflow. The Wave E UGC moderation foundation investment (ADR-021) further confirms the UGC-primary shape — answering いいえ here would be inconsistent with the level of moderation infrastructure. Counter-test: removing shared UGC from Skeinly would erase Discovery + suggestions + comments — the platform's differentiating value vs a plain project tracker. |
| ヌード公開を許可? | **いいえ** | Not a feature; Terms of Service prohibit; UGC moderation removes. |
| 露骨な暴力表現の公開を許可? | **いいえ** | Same. |
| ユーザー / UGC をブロックする機能? | **はい** | Wave E foundation (ADR-021) — `user_blocks` table + RLS NOT-EXISTS filter is shipped server-side. User-facing UI ships pre-Phase-40 GA (ADR-021 §D4). Forward-looking answer; alternative is いいえ + re-take questionnaire at GA, but the rating outcome (Everyone) is identical either way. |
| ユーザー / UGC を報告する機能? | **はい** | Same — `submit-ugc-report` Edge Function + GitHub Issue mirror + 24h operator triage SLA ([`ugc-moderation-sop.md`](ops/ugc-moderation-sop.md)). |
| チャットモデレート? | **いいえ** | No chat in Skeinly. Comments and suggestions are async forum-style posts on patterns, not real-time chat. |
| 対話を招待友人のみに制限可? | **いいえ** | No friend-only mode / private circles. All interactions in Discovery / comments / suggestions are public. |

**その他 sub-questions** (5 questions visible inline):

| Sub-question | Answer | Why |
|---|---|---|
| ユーザーの詳細な現在地情報を他のユーザーと共有? | **いいえ** | Skeinly collects no location data — declared in A0d-6 Data safety. |
| ユーザーはアプリを通じてデジタル商品を購入できる? | **はい** | IAP Pro subscription via StoreKit / Play Billing. |
| 現金報酬 / ギフトカード / play-to-earn / 換金可能暗号通貨 / 譲渡可能デジタル資産 (NFT) の発行? | **いいえ** | None of these mechanisms exist in Skeinly. |
| ウェブブラウザまたは検索エンジン? | **いいえ** | Skeinly is a craft project-management app, not a browser. |
| 主にニュースまたは教育商品? | **いいえ** | Core is project management + collaboration. Pattern browsing on Discovery can be incidentally educational but the app is not structured as a curriculum / news product. |

After Step 2 submission, IARC computes region-specific ratings automatically. **Verified outcome 2026-05-14**: PEGI 12 / USK 12+ / IARC 12+ / ACB 12+ / ESRB Teen / RARS 14+. Driven by UGC + Users-Interact descriptor + IAP disclosure — not by any rating-relevant Skeinly content (the content itself is craft-safe). This is the standard band for collaboration-enabled apps; accept and proceed.

### A0d-5: Target audience

- [ ] App content → **Target audience** — the form lists 6 age bands but **Play Console blocks the under-13 three (5歳以下 / 6〜8歳 / 9〜12歳)** because the A0d-4 IARC outcome includes ESRB Teen (13+). Only 13〜15歳 / 16〜17歳 / 18歳以上 are selectable.
- [ ] **Check all three available bands** (13〜15歳 + 16〜17歳 + 18歳以上). Reasoning: (1) the DFF (Designed for Families) policy is already structurally avoided because ESRB 13+ blocks the child age bands; (2) teens are a legitimate Skeinly audience (knitting / crochet learners from school clubs, family teaching) — restricting to 18+ artificially excludes real users and reduces Play Store discoverability; (3) [pre-alpha-checklist V7](ops/pre-alpha-checklist.md) explicitly accepts both "Adults only" and "Teens and adults" as compliant.
- [ ] Appeal to children: **No** (Skeinly is not directed at under-13 even though teens are a target)
- [ ] (If asked) Children may use the app: **My app is not directed at children**

If you want the strictest declaration, check **18歳以上 only** instead. Trade-off: smallest policy surface but excludes a legitimate teen audience and reduces Play Store algorithmic reach. The 3-band selection is the recommended default.

### A0d-6: Data safety

App content → **データ セーフティ**. The form is a 5-page wizard:
1. **概要** — landing page, click 開始 / 編集
2. **データの収集とセキュリティ** — top-level collection + security + account / deletion questions
3. **データの種類** — per-data-type declaration (the 9-type matrix below)
4. **データの使用と処理** — per-data-type purpose + sharing
5. **プレビュー** — review + 公開

#### Page 2: データの収集とセキュリティ

| Question | Answer |
|---|---|
| アプリは対象になる種類のユーザーデータを収集または共有しますか? | **はい** |
| アプリで収集するユーザーデータはすべて、転送時に暗号化されますか? | **はい** (HTTPS / TLS across Supabase / RevenueCat / GitHub / APNs / FCM) |
| アプリが対応しているアカウントの作成方法 (該当をすべて) | **Depends on Phase 26 (OAuth Sign-In) implementation state at submission time** — see below. |
| ユーザーによるアカウントの作成をアプリで許可していない | **チェックしない** (sign-up は可能) |
| アカウント削除用 URL | `https://b150005.github.io/skeinly/account-deletion/` |
| アカウントの削除を必要とすることなく、一部またはすべてのデータの削除をリクエストする方法をユーザーに提供していますか? (任意) | **いいえ** — Skeinly は account-level deletion のみ (atomic `delete_own_account` RPC)、partial deletion は提供しない。90 日自動削除でもない |

**アカウント作成方法 selections by Phase 26 state**:

| Phase 26 state | Check |
|---|---|
| **Pre-Phase-26** (current — email/password only) | **「ユーザー名とパスワード」のみ** |
| **Post-Phase-26** (Apple Sign-In + Google Sign-In shipped — HARD-GATE for alpha launch) | **「ユーザー名とパスワード」** + **「OAuth」** |
| If MFA / biometric is also wired in any Phase 26 sub-slice | Add **「ユーザー名、パスワード、その他の認証」** |

Phase 26 is the alpha-launch HARD-GATE (per the Planned section above). At submission time, update this section to reflect what is actually shipped in the build being submitted.

「**その他のバッジ**」セクション (任意):
- **独自のセキュリティ審査**: チェックしない (3rd-party security audit 未実施)
- **UPI での支払い確認済み**: チェックしない (Skeinly はインド NPCI 認定対象のファイナンスアプリではない)

#### Page 3: データの種類 — 9-type declaration matrix

Declare these per [pre-alpha-checklist.md §35.1 A6](ops/pre-alpha-checklist.md#a6-data-safety-form):

| Data category | Type | Required? | Encrypted | Deletable |
|---|---|---|---|---|
| Personal info | Email | Yes | Yes | Yes |
| Personal info | Display name | Yes | Yes | Yes |
| Personal info | Bug report content | No (user-submitted) | Yes | Via support |
| Financial info | Purchase history (RevenueCat) | No (Pro only) | Yes | Yes |
| App activity | PostHog events | No (opt-in) | Yes | Anonymized |
| App info and performance | Sentry crash logs | No (opt-in) | Yes | Anonymized |
| Device or other IDs | FCM/APNs token | No (push permission) | Yes | Yes |
| Device or other IDs | Supabase user UUID | Yes | Yes | Yes |
| Files and docs | UGC (chart images / pattern data) | No (Discovery share only) | Yes | Yes |

#### Page 4: データの使用と処理

- Data sharing: **No** for all (Sentry / PostHog / RevenueCat / GitHub are service providers, not "sharing" per Play's definition)
- Security practices: encryption in transit **Yes**, users can request deletion **Yes** (in-app + web), independent verification **No**, Families Policy **No**

### A0d-7: Government / Financial / Health

- [ ] **Government apps**: No
- [ ] **Financial features**: No (IAP subscriptions are not financial services per Play's definition)
- [ ] **Health**: No

### A0d-8: App category and contact details

- [ ] Dashboard → **Select app category and provide contact details** (or Store listing overview → Store settings)
- [ ] App or game: **App**
- [ ] Category: **Lifestyle** (per [store-listing.md](store-listing.md))
- [ ] Tags: pick from Play's curated list — `Knitting`, `Hobby`, `Craft`, `Pattern` (up to 5; not free-form)
- [ ] Email: `skeinly.app@gmail.com` (publicly displayed)
- [ ] Website: `https://b150005.github.io/skeinly/`
- [ ] Phone: leave empty
- [ ] External marketing: **No**

### A0d-9: Store listing

- [ ] Store presence → **Main store listing**
- [ ] App name `Skeinly` (30 chars)
- [ ] Short description (80 chars EN/JA — see [store-listing.md](store-listing.md))
- [ ] Full description (4000 chars EN/JA — see [store-listing.md](store-listing.md))
- [ ] Graphic assets:
  - App icon 512×512 (already at `androidApp/src/main/ic_launcher-playstore.png`)
  - **Feature graphic 1024×500** ⚠️ NOT YET AUTHORED — required even for Internal Testing rollout
  - Phone screenshots ≥ 2 (EN + JA recommended) ⚠️ NOT YET CAPTURED
  - Tablet 7" + 10" optional

### A0d-10: Internal Testing track setup

- [ ] **Test and release → Testing → Internal testing** → **Create new release**
- [ ] CI uploads via `release.yml` + `gradle-play-publisher` with `releaseStatus = DRAFT` (CLAUDE.md Tech Debt entry)
- [ ] Release notes EN + JA both required ([release.md](ops/release.md))
- [ ] **Testers** tab → add Google account emails (≤ 100 for Internal track) — share the opt-in link from "How testers join your test"
- [ ] License testers (separate list under Settings → License testing — same email goes on both)
- [ ] **Save → Review release** halts at DRAFT (safety: `releaseStatus = DRAFT` blocks auto-rollout)
- [ ] Click **Start rollout to testers** manually when all of A0d is green

### A0d-11: Pre-rollout checklist (Internal Testing distribution)

- [ ] A0d-1: Privacy policy URL registered + 200 OK
- [ ] A0d-2: App access demo credentials registered + both demo accounts exist + seeded + Pro entitlement granted
- [ ] A0d-3: Ads = No
- [ ] A0d-4: Content rating = Everyone
- [ ] A0d-5: Target audience = Adults only (no child age bands)
- [ ] A0d-6: Data safety all categories declared + Account Deletion URL registered
- [ ] A0d-7: Government / Financial / Health = No
- [ ] A0d-8: Category Lifestyle + contact email + website
- [ ] A0d-9: Listing copy EN + JA + icon + feature graphic + ≥ 2 phone screenshots
- [ ] A0d-10: Internal Testing track + testers + license testers + release notes EN + JA

### A0d common pitfalls

1. Switching tabs without saving — each section has its own Save.
2. Privacy URL 404 / 503 — wait for GitHub Pages deploy (~1–5 min).
3. Declaring "no data collection" with sign-up — false. Email + display name are minimum.
4. Selecting any child age band — DFF flag is slow to remove. Adults only from the start.
5. License tester ≠ Internal tester — Pro IAP test needs registration on both lists.
6. Feature graphic required even for Internal Testing — listing completeness is enforced regardless of track visibility.

---

## Phase A0e — Universal Links (AASA) + Android App Links (assetlinks.json)

### A0e-1: Decide the hosting strategy

Apple requires AASA at `https://<domain>/.well-known/apple-app-site-association` over HTTPS, `Content-Type: application/json`, no redirects. Skeinly's GitHub Pages serves at `https://b150005.github.io/skeinly/` (project page), but AASA must live at the apex `https://b150005.github.io/.well-known/...` which is the **user site** in a separate repo.

| Option | Cost | When |
|---|---|---|
| **A — Create `b150005/b150005.github.io` user site** | $0 | Recommended for alpha |
| **B — Custom domain (`skeinly.app` etc.)** | ~$10/yr | Recommended for v1.0 |
| **C — Defer to v1.0, URI scheme only** | $0 | If Universal Links can wait |

**Alpha recommendation**: Option A. Migration to B later is just a CNAME change.

### A0e-2: AASA file content

```json
{
  "applinks": {
    "apps": [],
    "details": [
      {
        "appIDs": ["<TEAMID>.io.github.b150005.skeinly"],
        "components": [
          { "/": "/skeinly/share/*" }
        ]
      }
    ]
  }
}
```

- [ ] Substitute `<TEAMID>` with your 10-char Apple Developer Team ID
- [ ] Deploy to `https://b150005.github.io/.well-known/apple-app-site-association` (no `.json` extension)
- [ ] Add `applinks:b150005.github.io` to iOS app entitlements

### A0e-3: assetlinks.json (Android App Links)

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "io.github.b150005.skeinly",
    "sha256_cert_fingerprints": ["<APP_SIGNING_SHA256>"]
  }
}]
```

- [ ] Get `<APP_SIGNING_SHA256>` from Play Console → Setup → App integrity → App signing key SHA-256
- [ ] Deploy to `https://b150005.github.io/.well-known/assetlinks.json`

### A0e-4: Verification after deploy

```bash
curl -I https://b150005.github.io/.well-known/apple-app-site-association
# Expect: HTTP/2 200, content-type: application/json
curl https://b150005.github.io/.well-known/apple-app-site-association | jq .
# Expect: valid JSON parse

# Apple's validator (alternative)
# https://search.developer.apple.com/appsearch-validation-tool
```

---

## Phase A0f — RevenueCat

RC is the cross-platform IAP / subscription orchestration layer. Skeinly's products feed in via A0b-11 + A0c-13 imports; this phase wires up the entitlement + offering + webhook.

### A0f-1: Create the RevenueCat Project

- [ ] RC Dashboard → **+ New Project** → Name `Skeinly` → Create

### A0f-2: Link the iOS App

- [ ] **Project Settings → Apps → + New → App Store**
- [ ] App name `Skeinly iOS`, Bundle ID `io.github.b150005.skeinly`
- [ ] Upload the `.p8` from A0a-5 + Key ID + Issuer ID
- [ ] Save → wait < 1 min for RC to fetch product list
- [ ] **API Keys** tab → copy the **Public iOS SDK Key** (`appl_...`) → register as `REVENUECAT_API_KEY_IOS` per [release-secrets §18](release-secrets.md#18-revenuecat_api_key_ios)

### A0f-3: Link the Android App

- [ ] **Project Settings → Apps → + New → Play Store**
- [ ] App name `Skeinly Android`, Package `io.github.b150005.skeinly`
- [ ] Upload the `revenuecat@<project-ref>.iam.gserviceaccount.com` SA JSON (this SA has "View financial data" + "Manage orders and subscriptions" in Play Console → Users and permissions)
- [ ] Save → wait for RC to fetch product list
- [ ] **API Keys** tab → copy the **Public Android SDK Key** (`goog_...`) → register as `REVENUECAT_API_KEY_ANDROID` per [release-secrets §19](release-secrets.md#19-revenuecat_api_key_android)

### A0f-4: Entitlement + Offering binding

This is the step the **next session performs via RevenueCat MCP** after A0b-11 + A0c-13 dashboard imports are done. The MCP tools:

- [ ] `list-products` — confirm 4 products visible (iOS monthly + yearly + Android `:monthly` + `:yearly`)
- [ ] `list-offerings` then `list-packages` — find existing `$rc_monthly` / `$rc_annual` on the `default` offering
- [ ] `attach-products-to-package` × 4: iOS+Android monthly → `$rc_monthly`, iOS+Android yearly → `$rc_annual`
- [ ] `list-entitlements` + `attach-products-to-entitlement` — confirm `entlaaca26b181` includes all 4

The client then calls `Purchases.shared.getOfferings()` and reads the `current` offering's packages — no hardcoded product IDs in client code.

### A0f-5: Webhook integration

- [ ] Generate strong shared secret: `openssl rand -hex 32`
- [ ] RC → **Integrations → Webhooks → + New Webhook**:
  - URL: `https://<project-ref>.supabase.co/functions/v1/revenuecat-webhook`
  - Authorization header: paste the secret
  - Environment: leave default (Sandbox + Production)
- [ ] Save → register the secret as `REVENUECAT_WEBHOOK_SECRET` per [release-secrets EF-4](release-secrets.md#ef-4-revenuecat_webhook_secret)
- [ ] Trigger a test event from the RC dashboard → verify Edge Function logs receive 200

### A0f-6: End-to-end smoke test

- [ ] iOS: TestFlight build on real device, sign in with a Sandbox tester (one US + one JP), open paywall, tap monthly → StoreKit Sandbox dialog → complete purchase → verify entitlement granted + `subscriptions` row exists via Supabase MCP `execute_sql`
- [ ] Android: Play Internal Testing build, sign in with license tester Google account, open paywall, tap monthly → Play Billing dialog with test annotation → complete purchase → verify same `subscriptions` row write

---

## Phase A0 — Verification checklist (before tagging the first alpha)

### Apple side
- [ ] App ID `io.github.b150005.skeinly` with 4 capabilities
- [ ] APNs `.p8` downloaded, EF secrets registered
- [ ] Provisioning Profile regenerated after capabilities added
- [ ] ASC app + Subscription Group `Skeinly Pro` + EN/JA localizations
- [ ] 2 IAP products with correct IDs + EN/JA localizations + 7-day Free Trial each
- [ ] Both products at Level 1
- [ ] ASSN V2 webhook configured (Production + Sandbox) → RC test event succeeded
- [ ] ≥ 2 Sandbox testers (one US + one JP recommended)
- [ ] App Privacy declaration submitted
- [ ] Products imported into RC dashboard

### Google side
- [ ] Play Console app `Skeinly` published to Internal Testing
- [ ] Subscription Product `io.github.b150005.skeinly.pro` with both base plans Active + both Free Trial offers Active
- [ ] ja-JP translation added
- [ ] ≥ 1 license tester (one US + one JP recommended)
- [ ] Pub/Sub + IAM Publisher + RTDN test message succeeded
- [ ] Products imported into RC dashboard
- [ ] App content all green: Privacy / App Access / Ads / Content Rating / Target Audience / Data Safety / Government / Financial / Health / Category / Store Listing
- [ ] Demo accounts created in Supabase + seeded + Pro entitlement granted

### Cross-vendor
- [ ] AASA + assetlinks.json deployed (or Option C explicitly accepted)
- [ ] RC Project with iOS + Android apps linked
- [ ] `REVENUECAT_API_KEY_IOS` + `REVENUECAT_API_KEY_ANDROID` GitHub Secrets registered
- [ ] Entitlement `entlaaca26b181` bound to all 4 products via MCP
- [ ] `default` offering with `$rc_monthly` + `$rc_annual` packages
- [ ] `REVENUECAT_WEBHOOK_SECRET` EF secret registered + test webhook 200
- [ ] End-to-end smoke test passed on both platforms

## Cross-references

- Per-secret OBTAIN / VERIFY / REGISTER procedures: [release-secrets.md](release-secrets.md)
- ADR-016 (RevenueCat decisions, pricing, entitlement): [adr/016-phase-41-revenuecat-subscription.md](adr/016-phase-41-revenuecat-subscription.md)
- ADR-017 (Push notifications): [adr/017-phase-24-push-notifications.md](adr/017-phase-24-push-notifications.md)
- ADR-021 (UGC moderation foundation): [adr/021-pre-alpha-ugc-moderation.md](adr/021-pre-alpha-ugc-moderation.md)
- Compliance audit (per-policy verification): [ops/pre-alpha-checklist.md](ops/pre-alpha-checklist.md)
- Branch protection + CI: [ops/repo-policy.md](ops/repo-policy.md)
- Release flow (tag push → CI → store upload): [ops/release.md](ops/release.md)
- Closed-beta tester invite operations: [ops/beta-testing.md](ops/beta-testing.md)
- Privacy policy source: [docs/public/privacy-policy/](../public/privacy-policy/)
- Account deletion page source: [docs/public/account-deletion/](../public/account-deletion/)
- Store listing copy (EN + JA): [store-listing.md](store-listing.md)

## Revision history

| Date | Change |
|---|---|
| 2026-05-13 | Consolidated `ops/iap-setup-app-store-connect.md`, `ops/iap-setup-play-console.md`, and `ops/play-console-app-setup.md` into this file per the "one-time setup → vendor-setup.md" rule in [ops/README.md](ops/README.md). Renumbered phases (Apple A0a+A0b, Google A0c+A0d, Universal Links A0e, RevenueCat A0f). Compressed prose to checklist format. |
| Earlier | Initial Phase A0 procedure scoped to Apple-side. |
