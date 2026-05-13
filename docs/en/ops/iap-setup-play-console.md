# IAP Setup — Google Play Console (Skeinly Pro)

> Japanese translation: [docs/ja/ops/iap-setup-play-console.md](../../ja/ops/iap-setup-play-console.md)

## Goal

Create the Skeinly Pro auto-renewable subscription on the Google side end-to-end:

- **Subscription Product:** `io.github.b150005.skeinly.pro` (reverse-DNS, iOS-symmetric)
- **Monthly Base Plan:** `monthly` — $3.99/month
- **Yearly Base Plan:** `yearly` — $24.99/year
- **7-day Free Trial** offer attached to each base plan
- **Real-time Developer Notifications (RTDN)** routed through Google Cloud Pub/Sub to RevenueCat
- **License testers** registered so closed-beta testers can purchase without real charges

> **Note on the reverse-DNS scheme** (Agent Team decision 2026-05-13): the subscription product ID matches the iOS bundle-ID prefix (`io.github.b150005.skeinly.pro`) so the resulting RevenueCat identifiers — `io.github.b150005.skeinly.pro:monthly` + `io.github.b150005.skeinly.pro:yearly` — share the common prefix `io.github.b150005.skeinly.pro` with the iOS product IDs `io.github.b150005.skeinly.pro.monthly` + `.yearly`. Only the separator differs (`:` on Android, `.` on iOS — both forced by their respective ecosystem conventions). Cross-platform Pro entitlement debugging in RC dashboard / analytics groups naturally by the shared prefix.

After this runbook completes, the operator hands off to the next session for the [RevenueCat side](../adr/016-phase-41-revenuecat-subscription.md) (product-to-package binding via the RevenueCat MCP).

Counterpart for iOS: [iap-setup-app-store-connect.md](iap-setup-app-store-connect.md).

## Prerequisites

- Admin role in Google Play Console for the Skeinly app.
- Skeinly app record exists in Play Console (package name `io.github.b150005.skeinly`) and is published to **at least one track** (Internal Testing is sufficient). **License tester purchases will not work on an unreleased draft app.**
- Google Cloud Console access in the same GCP project that holds the Play Console service account credentials (typically created during initial Play Console setup).
- RevenueCat project exists with Android app registered.

## Critical decisions (already locked in)

| Decision | Value | Rationale |
|---|---|---|
| Pricing | $3.99/mo + $24.99/yr | ADR-016 §50 |
| Free trial duration | 7 days | ADR-016 §50 |
| Structure | 1 Subscription Product × 2 Base Plans | Modern PBL 7+ idiom. Free trial eligibility is enforced at subscription-product scope = one redemption per user across both base plans → trial cost protected. RevenueCat product identifier `subscription_id:base_plan_id` (colon-separated) is the post-Feb-2023 format. |
| Subscription Product ID | `io.github.b150005.skeinly.pro` (reverse-DNS, iOS-symmetric) | Item ID character set per Play Console dialog hint: lowercase letters (`a-z`), digits (`0-9`), underscores (`_`), periods (`.`), starting with letter or digit. 29 chars, under the 40-char limit. Matches the iOS bundle ID prefix so cross-platform RC dashboard grouping is natural. |
| Base Plan IDs | `monthly` + `yearly` (single-word, no separator needed) | **Hyphens-allowed but unused here — Base Plan IDs accept `a-z`, `0-9`, `-` per Google Publisher API.** Single-word names sidestep the underscore-vs-hyphen issue entirely. Per [research-critic 2026-05-13](https://developers.google.com/android-publisher/api-ref/rest/v3/monetization.subscriptions). |
| Backwards compatibility | Monthly base plan marked compatible | RevenueCat docs note older SDK versions only see base plans marked "Use for deprecated billing methods". Marking the monthly plan keeps that fallback path open. |
| Base region for pricing | USD | Play Console auto-converts to all enabled countries; per-country override available. |
| Track for license testers | Internal Testing | License testers can purchase from any published track; Internal is the lowest-friction one. |

### ID validity reference

| Field | Allowed characters | Skeinly value |
|---|---|---|
| Subscription Product ID | `a-z`, `0-9`, `_`, `.` (must start with letter or digit, max 40 chars) | `io.github.b150005.skeinly.pro` (reverse-DNS — matches iOS bundle ID prefix) |
| Base Plan ID | `a-z`, `0-9`, `-` only (max 63 chars) | `monthly` / `yearly` (single-word, no separator needed) |
| Offer ID | same as base plan | `monthly-trial` / `yearly-trial` |

Source: Play Console dialog hint (Subscription Product ID), [Google Publisher API — Subscriptions reference](https://developers.google.com/android-publisher/api-ref/rest/v3/monetization.subscriptions) (Base Plan ID).

> **Forward-compat note**: Earlier iterations of this runbook used `skeinly_pro` + `pro-monthly` / `pro-yearly` (Android-native short form). The 2026-05-13 Agent Team agreed on the reverse-DNS scheme to maximize iOS symmetry, since Base Plan IDs accepting only hyphens means we can't use periods there anyway — and naming Base Plans as single words (`monthly`, `yearly`) sidesteps the hyphen-vs-underscore issue entirely while keeping the Subscription Product ID prefix-identical to iOS.

## Order of operations

1. Create Subscription Product `io.github.b150005.skeinly.pro` (no base plan yet)
2. Add monthly base plan `monthly` + set prices + mark backwards compatible + activate
3. Add yearly base plan `yearly` + set prices + activate
4. Create 7-day Free Trial offer on monthly base plan + activate
5. Create 7-day Free Trial offer on yearly base plan + activate
6. Add Japanese (ja-JP) translation for store listing
7. Publish app to Internal Testing track if not already
8. Add license tester Google accounts
9. Create Pub/Sub topic in GCP + grant IAM Publisher permission to Google's RTDN service account
10. Configure RTDN in Play Console pointing at the Pub/Sub topic
11. Verify in RevenueCat dashboard that the test notification was received

Steps 9–11 can be ordered slightly differently: if RevenueCat's "Connect to Google" flow generates the topic ID for you, do Step 11a (generate topic ID in RevenueCat) before Step 9.

---

## Step 1 — Create Subscription Product

Play Console → Skeinly app → sidebar **Monetize with Play → Products → Subscriptions** → **Create subscription**.

| Field | Value |
|---|---|
| Product ID (アイテム ID) | `io.github.b150005.skeinly.pro` (reverse-DNS, iOS bundle ID prefix; 29 chars under 40 limit) |
| Name | `Skeinly Pro` (max 55 chars; user-visible in subscription emails and the Google Play subscription center) |

Click **Create**.

### Permanent fields

The product ID can never be changed or reused. If you typo it, you must archive the product and create a new one with a different ID. Reuse of an archived ID is also blocked. Plan before you type.

### Add subscription benefits

After creating, click **Edit subscription details**. Add **up to 4 benefit lines** (max 40 chars each):

- `Unlimited chart creation`
- `Advanced pattern analysis`
- `Priority support`
- (optional 4th)

**Do not mention "free trial" or specific prices in benefit text** — Play policy prohibits it. The trial and price are surfaced separately in the user's subscription UI.

### Add the internal-only Description (optional, max 200 chars)

Below the benefits, there is a 説明 / Description field (200-char cap, Google Play does NOT show this to users — "Google Play でユーザーには表示されません"). It's an internal note for the developer team. You can leave it blank, or paste this 181-char reference (well under the 200 limit):

```
Skeinly Pro auto-renewable subscription. Monthly ($3.99) + yearly ($24.99) base plans, 7-day free trial. RevenueCat entitlement entlaaca26b181 via $rc_monthly / $rc_annual packages.
```

Authoritative reference: [Play Console Help — Create and manage subscriptions](https://support.google.com/googleplay/android-developer/answer/140504).

## Step 2 — Add the Monthly Base Plan

From the subscription details page, click **Add base plan**.

| Field | Value |
|---|---|
| Base Plan ID | `monthly` (single word, no separator) |
| Type | **Auto-renewing** |
| Billing period | Monthly |
| Grace period | 3 days (Play default; consider 7 days for better retention) |
| Account hold | 30 days (default) |
| User's base plan and offer changes | **Charge on next billing date** (`次回の請求日に請求`) — defer billing change to the next renewal, do NOT charge immediately. Industry standard for subscription plan changes (Netflix / Spotify / YouTube Premium / Apple Music all use this); reduces chargeback risk + matches user mental model of "switch plan → next bill reflects new plan". Agent Team decision 2026-05-13. |
| Resubscribe | Enabled |
| Tags | **Leave empty** (Phase 39). Tags are an internal label (max 20 chars, lowercase + digits + hyphen) used for client-side `queryProductDetailsAsync` filtering or Play Console analytics grouping. Skeinly's RevenueCat abstraction (via `$rc_monthly` / `$rc_annual` lookup keys) bypasses client-side base-plan queries entirely, and with only 2 base plans there's no grouping need. Tags are editable post-activation, so future-state needs (intro-pricing A/B test, holiday campaigns, legacy-price grandfather) can be added when actually required. |

### 2a. Country availability

Click **Manage country/region availability** → check **United States**, **Japan**, and any other target markets. Optionally check **New countries/regions** to auto-include future Google-supported markets. **Apply** → **Save**.

### 2b. Prices

Click **Update prices**:
1. Select all countries you just enabled.
2. Enter base price: **`3.99`** USD.
3. Play Console auto-converts to each selected country's local currency using current exchange rates and applies local "price charming" (rounding patterns).
4. Review the auto-converted price for Japan (JPY). Typical conversion lands in the ¥600–¥700 range. Click the pencil icon to override if you have a specific preference.
5. **Apply** → **Save**.

### 2c. Mark backwards compatible (RevenueCat-critical)

Open the base plan's **overflow menu (⋯)** → select **Use for deprecated billing methods** (sometimes labeled "Mark as backwards compatible").

This makes the monthly base plan visible to clients on older Play Billing Library versions. RevenueCat's documentation calls this out explicitly: *"Only base plans marked as 'backwards compatible' in Google Play Console are available in older SDK versions."*

**Mark only the monthly base plan as backwards compatible**, not the yearly one. Only one base plan per subscription product can be marked.

### 2d. Activate

**Activation is a standalone button at the bottom of the base plan edit page — NOT in the 3-dot ⋮ overflow menu on the base plan row.** Source: [Play Console Help — Create and manage subscriptions](https://support.google.com/googleplay/android-developer/answer/140504), verified 2026-05-13.

Path:
1. From the subscription details page, navigate to the base plan edit page. There are TWO equivalent click targets in the current Play Console UI:
   - **The `›` (right arrow) button at the far right of the base plan row** — operator-confirmed 2026-05-13 to exist, but it's positioned past the visible table columns, so you may need to **scroll the table horizontally** to reveal it. On narrow viewports the arrow is hidden by default.
   - **The base plan ID text (`monthly`) in the ID column** — rendered as a clickable hyperlink (no horizontal scroll needed). Equivalent navigation target to the arrow.
2. The base plan edit page opens.
3. Scroll to the bottom of the page.
4. Click **Activate** (the newer label is **Enable** in some JA locales — 「有効にする」 — and the older label is 「アクティブ化」; both trigger the same action) → confirm in the dialog. Status changes from **Inactive** (未公開) to **Active** (有効).

The 3-dot ⋮ menu on the base plan row exposes secondary actions ONLY — typically just **Use for deprecated billing methods** (`サポートを終了した請求方法で使用`) for a draft base plan. Activate is NOT in that menu. If clicking the ⋮ menu only shows the deprecated-billing-methods option, that's expected — keep looking at the row text instead.

### Critical constraint

Once a base plan is **activated and a purchase has been made against it**, the parent subscription product can no longer be deleted (only archived). Plan IDs are also permanent.

## Step 3 — Add the Yearly Base Plan

Same procedure as Step 2 with these values:

| Field | Value |
|---|---|
| Base Plan ID | `yearly` (single word, no separator) |
| Type | Auto-renewing |
| Billing period | **Yearly** |
| Grace period | 7 days (recommended for annual — longer grace appropriate for higher-value commitment) |
| Account hold | 30 days |
| User's base plan and offer changes | **Charge on next billing date** — same as monthly base plan. Symmetric defer-billing for plan switches; for yearly→monthly downgrades this means the user remains on yearly until the current year ends, then switches to monthly — acceptable per the established industry pattern. |
| Resubscribe | Enabled |
| Tags | **Leave empty** — same rationale as monthly base plan. |
| Backwards compatible | **Do NOT mark** |

Set base price to **`24.99`** USD; Play Console auto-converts. Japan typically lands in the ¥3,600–¥4,000 range.

Click **Activate** (same path as monthly in Step 2d: open the base plan edit page by clicking the `yearly` ID text → scroll to bottom → click the Activate / 「有効にする」 button. NOT via the 3-dot ⋮ menu).

## Step 4 — Free Trial offer (Monthly)

Free trials are not embedded inside base plans — they are **separate Offer objects** attached to each base plan. Create one offer per base plan.

From the subscription details page → **Base plans and offers** section → on the `monthly` row click **Add offer**.

| Field | Value |
|---|---|
| Associated base plan | `monthly` (pre-selected) |
| Offer ID | `monthly-trial` (single hyphen separator; base plan ID is one word so this stays parseable) |
| Eligibility | **New customer acquisition** → **"The user has never had entitlement to this subscription"** |
| Country availability | Same set as the base plan |

### Add the trial phase

### Finding Add phase in the offer creation form

After filling in the offer ID + base plan association + eligibility, scroll within the same offer-creation form to the **Phases** / **フェーズ** section. The **Add phase** / **フェーズを追加** button lives there — NOT on a separate sub-page. You do not need to save the offer first to access it.

Click **Add phase** / **フェーズを追加** → three phase types appear:
- **Free trial** — zero cost for a duration you specify (3 days minimum, 3 years maximum)
- **Single payment** — one-time discounted upfront payment
- **Recurring payment** — discounted recurring billing for N billing periods

Select **Free trial** → set Duration to **7 days**. No price fields appear for Free Trial — it's zero-cost by definition.

### Activate the offer

Click **Save** to persist the offer. Then click **Activate** at the bottom of the offer edit page to make it purchasable. The offer's Activate button is separate from the base plan's Activate button (an offer can be in draft state even on an activated base plan, and vice versa).

> **Prerequisites verified 2026-05-13** (Google Play Console Help): the base plan does NOT need to be Activated before you can create + configure an offer with phases on it. A draft (Inactive / 未公開) base plan accepts offer creation just fine. Country availability is parallel-configurable (not a gate). The offer itself must be Activated for it to be purchasable.

### Eligibility scope notes

- **"The user has never had entitlement to this subscription"** = applies once per user across the WHOLE subscription product (both monthly and yearly base plans share the eligibility count). This is the desired behavior — trial cost protected.
- Alternative: "the user has never had any subscription in this app" would block trial users who had a different Skeinly subscription historically. Not our case.
- **Eligibility is enforced automatically by Google Play.** No backend check required.
- Google Play requires a valid payment method on file before starting a trial.

Authoritative reference: [Play Console Help — Understanding subscriptions](https://support.google.com/googleplay/android-developer/answer/12154973).

## Step 5 — Free Trial offer (Yearly)

Repeat Step 4 on the `yearly` row.

| Field | Value |
|---|---|
| Offer ID | `yearly-trial` |
| Associated base plan | `yearly` |
| Eligibility | New customer acquisition → "The user has never had entitlement to this subscription" |
| Phase | Free trial, 7 days |

**Save** → **Activate**.

## Step 6 — Japanese localization

Play Console → **Grow users → Translations → Manage translations → Select languages → Japanese (Japan) (ja-JP) → Apply**.

Localize:

| Field | English (en-US) | Japanese (ja-JP) |
|---|---|---|
| Subscription Name | `Skeinly Pro` | `Skeinly Pro` (proper noun; kept in Latin script) |
| Benefit 1 | `Unlimited chart creation` | `無制限のチャート作成` |
| Benefit 2 | `Advanced pattern analysis` | `高度なパターン分析` |
| Benefit 3 | `Priority support` | `優先サポート` |

Japanese is **not in Google's free machine translation set** — enter the strings manually. The technical-writer reviews the JA copy at GA prep.

Authoritative reference: [Play Console Help — Translate and localize your app](https://support.google.com/googleplay/android-developer/answer/9844778).

## Step 7 — Publish to Internal Testing track

If Skeinly is not already released to a track, build and upload to Internal Testing. License tester purchases require the app to be published to at least one track — an unreleased draft will not accept purchases even for license testers.

This step has no IAP-specific UI work; it's the standard release upload flow covered in [release.md](release.md).

## Step 8 — Register License Testers

Play Console → sidebar **Setup → License testing**.

Under **Gmail accounts with testing access**, enter the Google account email addresses of all closed-beta testers, one per line (up to 2,000 addresses). Your own publisher account is always included automatically.

Click **Save changes**.

### Sandbox accelerated renewal times

When a license tester purchases a subscription, renewals run on an accelerated schedule (max 6 renewals per purchase):

| Production period | Accelerated test period |
|---|---|
| Free trial | 3 minutes |
| 1 month | 5 minutes |
| 1 year | 30 minutes |
| Grace period | 5 minutes |

This is enforced by Play automatically — no operator action required. Useful for verifying the renewal → RevenueCat webhook → `subscriptions` table row update path in minutes instead of months.

### Optional: Play Billing Lab

[Play Billing Lab](https://play.google.com/store/apps/details?id=com.google.android.apps.play.billingtestcompanion) is a companion app installed on the test device. It enables:
- Switching the Play country (test JPY pricing without a real JP Google account)
- Resetting trial eligibility on the same test account
- Triggering subscription state transitions (grace period, account hold) on demand

Configurations expire after 2 hours.

Authoritative reference: [Android Developers — Test in-app billing](https://developer.android.com/google/play/billing/test).

## Step 9 — Pub/Sub topic for RTDN

### 9a. Generate the topic via RevenueCat (recommended)

RevenueCat Dashboard → Skeinly Android app settings → under the service credentials section → click **Connect to Google**.

RevenueCat generates a Pub/Sub topic ID in the form `projects/<your_gcp_project_id>/topics/<auto_name>`. Copy this to clipboard.

If you set up RevenueCat's Play Console service credentials less than 36 hours ago, give it time — Google credentials need that warmup before the Connect flow works reliably.

### 9b. Or create the topic manually in GCP

Alternative path if you prefer manual setup. [Google Cloud Console → Pub/Sub → Topics → Create topic](https://console.cloud.google.com/cloudpubsub/topicList).

- Topic ID: e.g. `play-billing-notifications`
- Full resource name becomes: `projects/<your_gcp_project_id>/topics/play-billing-notifications`

### 9c. Enable Pub/Sub API if not already

Visit https://console.cloud.google.com/flows/enableapi?apiid=pubsub in the GCP project that hosts your Play service account → click **Enable**.

### 9d. Grant IAM Publisher permission

GCP Console → Pub/Sub → Topics → click your topic name → **Permissions** tab → **Add Principal**.

| Field | Value |
|---|---|
| New principals | `google-play-developer-notifications@system.gserviceaccount.com` |
| Role | **Pub/Sub Publisher** (`roles/pubsub.publisher`) |

Click **Save**.

**Gotcha — Domain Restricted Sharing:** if your GCP organization enforces Domain Restricted Sharing org policy, adding an external `@system.gserviceaccount.com` principal may be blocked. Add an exception for `system.gserviceaccount.com` in the org policy if needed. Symptom: the Add Principal flow returns "principal does not match domain restriction" or similar.

Authoritative reference: [Android Developers — Get ready for Play Billing Library](https://developer.android.com/google/play/billing/getting-ready) (RTDN configuration section).

## Step 10 — Configure RTDN in Play Console

Play Console → Skeinly app → sidebar **Monetize with Play → Monetization setup** → scroll to **Real-time developer notifications**.

| Field | Value |
|---|---|
| Enable real-time notifications | ✅ checked |
| Topic name | Paste the full topic name from Step 9: `projects/<gcp_project_id>/topics/<topic_name>` |
| Notification content | **Subscriptions, voided purchases, and all one-time products** (broadest coverage) |

Click **Send Test Message** to verify the connection.

### If the test message fails

Common causes:
- Topic name typo. Confirm exact string matches what's in GCP Console.
- IAM publisher permission hasn't propagated yet. Wait 5–10 minutes and retry.
- Pub/Sub API not enabled in the GCP project.

Click **Save changes** once the test passes.

## Step 11 — Verify RTDN in RevenueCat

RevenueCat Dashboard → Skeinly Android app settings → look for **Last received** timestamp confirming the test notification arrived.

Optionally enable **Track new purchases from server-to-server notifications** in RevenueCat's Purchase Tracking settings — this lets RevenueCat catch purchases the client SDK might miss (network issues, app uninstall/reinstall mid-flow).

Authoritative reference: [RevenueCat — Google Server Notifications](https://www.revenuecat.com/docs/google-server-notifications).

## Step 12 — Import products into RevenueCat (manual dashboard step)

**Required** — Play Console product creation does NOT auto-sync to RevenueCat even with service-account credentials and RTDN configured. The operator must trigger the import manually from RevenueCat's dashboard, the same way as the iOS side ([iap-setup-app-store-connect.md Step 10](iap-setup-app-store-connect.md)).

1. RevenueCat Dashboard → **Project Settings → Apps & providers** → click the Skeinly Android app → scroll to **Products**.
2. To the right of **+ New** there is an **Import** button. Click it.
3. RevenueCat fetches the current Play Console product catalog and offers to register them in your RevenueCat project. Confirm the import for both `io.github.b150005.skeinly.pro:monthly` and `io.github.b150005.skeinly.pro:yearly` (the colon-separated RC identifier format for post-Feb-2023 Google Play products).
4. After Import completes, both products appear in **Products** with the colon-separated RC identifiers.

The dashboard Import is the user-side action; the MCP path remains useful for the downstream binding work (attaching products to packages + entitlement) which the next session performs.

## Verification before handoff to RevenueCat product binding

Confirm in Play Console:

- [ ] Subscription Product `io.github.b150005.skeinly.pro` exists.
- [ ] Base plan `monthly` exists, status **Active**, marked **Use for deprecated billing methods**.
- [ ] Base plan `yearly` exists, status **Active**.
- [ ] Free Trial offer `monthly-trial` exists, status **Active**, 7-day phase, "Never had entitlement to this subscription" eligibility.
- [ ] Free Trial offer `yearly-trial` exists, status **Active**, same shape.
- [ ] Japanese (ja-JP) translation added for benefits.
- [ ] App is published to Internal Testing (or higher).
- [ ] At least one license tester registered (one US + one JP recommended).
- [ ] Pub/Sub topic created with Publisher IAM granted to `google-play-developer-notifications@system.gserviceaccount.com`.
- [ ] RTDN configured in Monetization setup; test message returned success.
- [ ] RevenueCat dashboard shows "Last received" timestamp.

## Common gotchas

| # | Gotcha | Detail |
|---|---|---|
| 1 | Subscription Product ID is permanent and non-reusable | Even archived IDs cannot be reused. Type carefully. |
| 2 | Subscription Product ID + Base Plan ID have different character sets | `io.github.b150005.skeinly.pro` (product, supports `_` `.`) vs `monthly` / `yearly` (base plan, only `a-z` `0-9` `-`). Skeinly's chosen names sidestep the issue (single-word base plans need no separator) but the constraint is real if you ever rename. |
| 3 | Only one base plan can be "backwards compatible" | Mark monthly. Older SDKs only see the marked one. |
| 4 | Benefits text cannot mention "free trial" or specific prices | Play policy. Surface those through paywall copy + StoreKit dialog only. |
| 5 | License tester needs app published to a track | Internal Testing minimum. Draft app won't accept purchases. |
| 6 | Free trial eligibility scope | "Has never had entitlement to this subscription" = once per user across both base plans. Protected. |
| 7 | GCP credentials need ~36 hr warmup | Set up RevenueCat's Play Console service credentials well before Pub/Sub Connect step. |
| 8 | Domain Restricted Sharing org policy may block IAM grant | Add `system.gserviceaccount.com` exception if grant fails. |
| 9 | RevenueCat identifier format for post-Feb-2023 products | `subscription_id:base_plan_id` colon-separated. For us: `io.github.b150005.skeinly.pro:monthly` + `io.github.b150005.skeinly.pro:yearly`. Shares the prefix `io.github.b150005.skeinly.pro` with the iOS product IDs (only the separator differs — `:` Android, `.` iOS). |
| 10 | Pricing in JPY rounded by Play Console's "charming" | Review auto-converted yen values; override if Play's rounding differs from your intent. |
| 11 | PBL version is RevenueCat's concern, not yours | We use RevenueCat KMP SDK; PBL version (currently 7.x inside `purchases-android` 17.55.x) is managed for us. |

## Where this fits in the wider Phase 39 pipeline

1. **App Store Connect setup** ([iap-setup-app-store-connect.md](iap-setup-app-store-connect.md) through its Step 9) → products in 「メタデータが不足」or higher
2. **ASC dashboard Import in RevenueCat** ([iap-setup-app-store-connect.md Step 10](iap-setup-app-store-connect.md)) → iOS products in RC
3. **Play Console setup** (this runbook through Step 11) → both base plans Active
4. **Play dashboard Import in RevenueCat** (this runbook Step 12) → Android products in RC
5. **RevenueCat MCP binding** (next session, agent-side) — attach all 4 products to packages + verify entitlement coverage
6. **End-to-end smoke test** — open paywall on a Play Internal Testing build with a license tester signed in, purchase, verify entitlement granted and `subscriptions` row written via `revenuecat-webhook`.
