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
- [ ] Data Types Collected (Skeinly's actual scope — see [pre-alpha-checklist.md §35.1](ops/pre-alpha-checklist.md)):
  - **Identifiers**: User ID (Supabase UUID, required), Device ID (PostHog distinct_id, opt-in)
  - **User Content**: Other User Content (UGC — patterns, comments)
  - **Diagnostics**: Crash Data + Performance Data (Sentry, opt-in)
  - **Usage Data**: Product Interaction (PostHog, opt-in)
  - **Purchases**: Purchase History (RevenueCat, Pro subscribers only)
- [ ] For each type: Linked to user = Yes (User ID anchors), Used for tracking = **No**

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

### A0d-3: Ads

- [ ] App content → **Ads** → **No, my app does not contain ads** (Skeinly is ad-free; monetization is IAP only)

### A0d-4: Content rating (IARC)

- [ ] App content → **Content rating** → register contact email + category → start the IARC questionnaire

Target: **Everyone** (all ages). Skeinly answers:

| Category | Answer |
|---|---|
| Violence, Sexual content, Profanity, Fear/Horror, Drugs/Alcohol/Tobacco, Gambling | **No** |
| User interaction | **Yes** (sharing / comments / suggestions / activity feed) |
| User-generated content (UGC) | **Yes** (patterns + comments) |
| Location sharing | **No** |
| Personal information shared between users | **No** (display name only is public) |
| Digital purchases | **Yes** (IAP) |
| Report / Block user mechanisms | **Yes** (Wave E foundation per ADR-021 — `submit-ugc-report` + `user_blocks` + 24h operator triage) |

User-facing Report/Block UI ships pre-Phase-40 GA (ADR-021 §D4); the foundation already satisfies Play policy.

### A0d-5: Target audience

- [ ] App content → **Target audience** → check **18 and older (Adults only)** only — uncheck every child age band
- [ ] Appeal to children: **No**
- [ ] (If asked) Children may use the app: **My app is not directed at children**

⚠️ Any child age band triggers the **Designed for Families (DFF) policy**: COPPA compliance, child-directed ad restrictions, no behavioral advertising. Skeinly sidesteps this entirely by declaring Adults only.

### A0d-6: Data safety

- [ ] App content → **Data safety** — declare 9 data types per [pre-alpha-checklist.md §35.1 A6](ops/pre-alpha-checklist.md#a6-data-safety-form):

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

- [ ] Data sharing: **No** for all (Sentry / PostHog / RevenueCat / GitHub are service providers, not "sharing" per Play's definition)
- [ ] Security practices: encryption in transit **Yes**, users can request deletion **Yes** (in-app + web), independent verification **No**, Families Policy **No**
- [ ] Account and data deletion:
  - Web URL: `https://b150005.github.io/skeinly/account-deletion/`
  - What gets deleted: account info, patterns, projects, progress, comments, suggestions, device tokens, subscription state, UGC reports, feedback, avatars. Retained: minimal logs only when required by law.
  - Partial deletion: **No** (account-only via atomic `delete_own_account` RPC)

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
