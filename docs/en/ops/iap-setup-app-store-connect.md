# IAP Setup — App Store Connect (Skeinly Pro)

> Japanese translation: [docs/ja/ops/iap-setup-app-store-connect.md](../../ja/ops/iap-setup-app-store-connect.md)

## Goal

Create the two Skeinly Pro auto-renewable subscription products on the Apple side end-to-end:

- **`io.github.b150005.skeinly.pro.monthly`** — $3.99/month
- **`io.github.b150005.skeinly.pro.yearly`** — $24.99/year
- **7-day Free Trial** introductory offer on both
- **App Store Server Notifications V2** webhook wired to RevenueCat
- **Sandbox testers** registered so closed-beta testers can purchase without real charges

After this runbook completes, the operator hands off to the next session for the [RevenueCat side](../adr/016-phase-41-revenuecat-subscription.md) (product-to-package binding via the RevenueCat MCP).

Counterpart for Android: [iap-setup-play-console.md](iap-setup-play-console.md).

## Prerequisites

- Account Holder, Admin, or App Manager role in App Store Connect.
- Skeinly app record exists in App Store Connect (Bundle ID `io.github.b150005.skeinly`).
- RevenueCat project exists with iOS app registered under **Apps & providers**.
- Apple Developer In-App Purchase agreement is signed and Paid Apps agreement is active (Agreements, Tax, and Banking → check status is "Active", not "Action Required"). Subscriptions cannot be submitted for review until both agreements are active.

## Critical decisions (already locked in)

| Decision | Value | Rationale |
|---|---|---|
| Pricing | $3.99/mo + $24.99/yr | ADR-016 §50 |
| Free trial duration | 7 days | ADR-016 §50 |
| Subscription Group | one group, both products at the same level | Crossgrade behavior between monthly ↔ yearly takes effect at next renewal date; both products grant identical Pro access (same RevenueCat entitlement `entlaaca26b181`) |
| Notification version | **V2 (recommended)** | RevenueCat docs explicitly recommend V2 for auto-detected price changes; V1 still works but is not the preferred path |
| Base region for pricing | **United States (USD)** | Apple auto-generates equivalent prices for all 174 other storefronts; manual override per territory available |
| Yearly product billing structure | **「1年間前払い」(annual prepay)** — NOT「12か月契約の月額プラン」(annual contract paid monthly) | The latter option is iOS 26.4+ / SDK 26.5+ only. Skeinly's deployment target is iOS 17.0, so 26.4-gated surfaces would exclude the vast majority of users from purchasing the yearly plan. Annual prepay is also the industry-standard form, aligns with our existing "Save 40%+ vs monthly" marketing narrative ($24.99 one-shot vs 12 × $3.99 = $47.88), matches knitter-craft prepay subscription convention, and is the only form with parity to Play Console's base-plan model. Decided 2026-05-13 via Agent Team deliberation. |

## Order of operations

1. Create Subscription Group
2. Add Subscription Group localizations (EN + JA)
3. Create monthly product (Reference Name, Product ID, Duration, Price, Localizations)
4. Create yearly product (same)
5. Edit Subscription Group order — place both products at the same level
6. Add 7-day Free Trial introductory offer to monthly product
7. Add 7-day Free Trial introductory offer to yearly product
8. Configure App Store Server Notifications V2 URL (Production + Sandbox)
9. Register sandbox testers under Users and Access → Sandbox

Steps 3–7 are independent of each other; steps 8–9 are independent of everything else.

---

## Step 1 — Create Subscription Group

App Store Connect → Skeinly app → sidebar **Monetization → Subscriptions** → **+** at the top of the Subscription Groups list.

- **Reference Name:** `Skeinly Pro` (internal only; not user-facing)

Click **Create**.

Authoritative reference: [Apple — Offer auto-renewable subscriptions](https://developer.apple.com/help/app-store-connect/manage-subscriptions/offer-auto-renewable-subscriptions/).

## Step 2 — Subscription Group localizations

Inside the new `Skeinly Pro` group, scroll to **App Store Localizations**. The group display name is what users see in iOS's "Manage Subscriptions" screen as the heading above the product list.

Add **English (U.S.)**:
- Subscription Group Display Name: `Skeinly Pro`

Add **Japanese (Japan)**:
- Subscription Group Display Name: `Skeinly Pro`

(JA display name kept identical because "Skeinly Pro" is a proper noun in both locales — confirm with the technical-writer if a katakana variant is preferred at GA.)

## Step 3 — Create the Monthly product

Inside the group, click **Create** (or **+**).

| Field | Value | Notes |
|---|---|---|
| Reference Name | `Skeinly Pro Monthly` | Internal only |
| Product ID | `io.github.b150005.skeinly.pro.monthly` | **PERMANENT — cannot be changed after creation. Triple-check before saving.** |

Click **Create**, then on the product detail page:

- **Subscription Duration:** 1 Month → **Save**
- **Subscription Prices:**
  - **Add Subscription Price** → **United States** → **$3.99** → **Next**
  - Review the auto-generated price table for all 174 other storefronts. For Japan (JPY), Apple proposes a rounded yen value (typically ¥600 range). Accept the proposal unless overriding for a specific reason — Japan convention is rounded yen.
  - Click **Confirm**.
- **Availability:** confirm all territories are selected (Skeinly targets global distribution at this stage).

### Monthly product localizations

Scroll to **App Store Localizations** within the product detail page.

Add **English (U.S.)**:
- Display Name: `Skeinly Pro Monthly` (30-char limit; this fits at 19)
- Description: `Unlock all Pro features. Auto-renews monthly.` (45 chars — limit is 55 regardless of language)

Add **Japanese (Japan)**:
- Display Name: `Skeinly Pro 月額プラン` (15 chars)
- Description: `Skeinly Pro の全機能を解放。毎月自動更新。` (22 chars)

Click **Save**.

> **Description 55-char limit:** Apple's Description field enforces a hard cap of 55 characters per locale. The cap is the same in EN and JA. Stay safely under 55 — there is no soft-warning state, just a save-time rejection.

Authoritative reference: [Apple — Manage pricing for auto-renewable subscriptions](https://developer.apple.com/help/app-store-connect/manage-subscriptions/manage-pricing-for-auto-renewable-subscriptions/).

## Step 4 — Create the Yearly product

Same procedure as Step 3 with these values:

| Field | Value |
|---|---|
| Reference Name | `Skeinly Pro Yearly` |
| Product ID | `io.github.b150005.skeinly.pro.yearly` |
| Subscription Duration | **1 Year** |
| Base Price (USD) | **$24.99** |
| EN Display Name | `Skeinly Pro Yearly` (18 chars; 30-char limit) |
| EN Description | `Unlock all Pro features. Auto-renews yearly. Save 40%+.` (55 chars — exactly at the limit) |
| JA Display Name | `Skeinly Pro 年額プラン` (15 chars) |
| JA Description | `Skeinly Pro の全機能を解放。毎年自動更新、40% お得。` (30 chars) |

For Japan (JPY), Apple will propose somewhere in the ¥3,600–¥4,000 range. Accept unless overriding.

## Step 5 — Assign subscription levels

Inside the `Skeinly Pro` group → **Edit Order** at the top of the product list.

Both `Skeinly Pro Monthly` and `Skeinly Pro Yearly` should appear. Place **both at Level 1** (same level). Click **Save**.

**Why same level**: monthly and yearly grant identical Pro access (same entitlement). Putting yearly at a higher level would imply it unlocks more content, which is incorrect for Skeinly. Same-level products are treated as a **crossgrade**: a switch between them takes effect at the next renewal date, with prorated logic handled by Apple.

## Step 6 — Configure 7-day Free Trial introductory offer (Monthly)

Inside the `Skeinly Pro Monthly` product detail page → **Subscription Prices** section → **View all Subscription pricing** → **Set up Introductory Offer**.

| Field | Value |
|---|---|
| Countries or regions | Select all (matches product availability) |
| Start date | Today (or leave blank) |
| End date | **Leave blank** — permanent offer with no expiry |
| Offer Type | **Free Trial** (NOT "Pay as You Go" — that's discounted recurring, not zero-cost) |
| Duration | **1 Week** (= 7 days) — Apple's duration picker label may show as "1 Week" or "1 or 2 Weeks" depending on the selection panel. Confirm the duration shown on the confirmation screen is exactly 7 days before saving. |
| Price | $0.00 / ¥0 (automatic for Free Trial type) |

Click **Confirm**.

### Critical constraint

**Introductory offers cannot be edited once created.** If you need to change duration or type, you must **delete** the offer and create a new one. Both operations are accessible from the same "View all Subscription pricing" screen.

### Per-group eligibility (do not configure — Apple enforces automatically)

Apple automatically enforces "one introductory offer redemption per customer per subscription group". A user who redeems the free trial on the monthly plan is **not eligible** for the free trial on the yearly plan (and vice versa). This is the desired behavior — it protects against double-trial cost. No operator action required.

Authoritative reference: [Apple — Set up introductory offers for auto-renewable subscriptions](https://developer.apple.com/help/app-store-connect/manage-subscriptions/set-up-introductory-offers-for-auto-renewable-subscriptions/).

## Step 7 — Configure 7-day Free Trial introductory offer (Yearly)

Repeat Step 6 for `Skeinly Pro Yearly` with the same field values.

## Step 8 — App Store Server Notifications V2

### 8a. Find the RevenueCat webhook URL

RevenueCat Dashboard → **Apps & providers** → click the Skeinly iOS app → scroll to **Apple Server to Server notification settings** → copy the URL under **Apple Server Notification URL**.

The URL is project-specific (contains your RevenueCat project token). There is no generic public template — you must read it from your dashboard.

### 8b. Enter the URL in App Store Connect

App Store Connect → Skeinly app → sidebar **General → App Information** → scroll to **App Store Server Notifications**.

**Production Server URL:**
- Click **Set Up URL** under Production Server URL.
- Paste the RevenueCat URL.
- Notification Version: **Version 2**.
- Click **Save**.

**Sandbox Server URL:**
- Click **Set Up URL** under Sandbox Server URL.
- Paste the **same RevenueCat URL**.
- Notification Version: **Version 2**.
- Click **Save**.

Setting both URLs explicitly (rather than relying on Apple's default routing of sandbox to production) keeps the configuration visible and self-documenting in App Store Connect.

### 8c. Optional automated alternative

RevenueCat's "Apple Server to Server notification settings" panel exposes an **Apply in App Store Connect** button that configures both URLs in App Store Connect directly via OAuth. If you prefer automation, use this. The manual steps above are equally valid and useful if the automation flow has any hiccup.

Authoritative references:
- [Apple — Enter server URLs for App Store Server Notifications](https://developer.apple.com/help/app-store-connect/configure-in-app-purchase-settings/enter-server-urls-for-app-store-server-notifications/)
- [RevenueCat — Apple App Store Server Notifications](https://www.revenuecat.com/docs/platform-resources/server-notifications/apple-server-notifications)

## Step 9 — Register sandbox testers

App Store Connect → **Users and Access** (from the App Store Connect home, NOT from inside an app) → **Sandbox** tab in the top navigation.

Click **+** (or **Create Test Accounts** for the first tester). For each tester:

| Field | Notes |
|---|---|
| First Name / Last Name | Any value; cannot be edited after creation |
| Email Address | **Must NEVER have been used as a real Apple ID** or for any past App Store purchase. Use a fresh address. Plus-subaddressing (`yourbase+jp@gmail.com`) is supported so one inbox can back many testers. Cannot be edited after creation. |
| Password | Set by you. Must meet Apple's strong-password requirements. Cannot be edited after creation. |
| App Store Country or Region | Initial storefront. **This field IS editable later** if you need to switch a tester between US and Japan storefronts. |

Click **Create**.

### Using sandbox testers on device

On the iOS test device: Settings → [Apple ID] → **Media & Purchases** → **Sign Out**. Do NOT sign out of iCloud at the top-level Apple ID. When the app initiates a purchase, iOS prompts for an Apple ID at the StoreKit dialog — sign in with sandbox credentials at that prompt.

Authoritative reference: [Apple — Create a Sandbox Apple Account](https://developer.apple.com/help/app-store-connect/test-in-app-purchases/create-a-sandbox-apple-account/).

## Verification before handoff to RevenueCat side

Confirm in App Store Connect:

- [ ] Subscription Group `Skeinly Pro` exists with EN + JA localizations.
- [ ] `io.github.b150005.skeinly.pro.monthly` exists, status "Ready to Submit" or later, 1 Month duration, $3.99 USD base price, EN + JA localizations.
- [ ] `io.github.b150005.skeinly.pro.yearly` exists, status "Ready to Submit" or later, 1 Year duration, $24.99 USD base price, EN + JA localizations.
- [ ] Both products are at Level 1 in the group order.
- [ ] Free Trial introductory offer (7 days) is configured on both products.
- [ ] Production Server URL is set with Version 2.
- [ ] Sandbox Server URL is set with Version 2.
- [ ] At least one sandbox tester is registered (one US, one JP recommended for closed beta).

Allow ~1 hour for metadata propagation before testing in sandbox — newly-created products are not immediately available in StoreKit sandbox.

## Common gotchas

| # | Gotcha | Detail |
|---|---|---|
| 1 | Product ID is permanent | Cannot be changed after creation. Triple-check spelling. |
| 2 | Subscription duration cannot change after App Review | Pick correctly (1 Month / 1 Year) before submitting. |
| 3 | Introductory offer cannot be edited | Only deletable and recreatable. |
| 4 | Per-group introductory offer eligibility | One redemption per customer per group — both products share the count. Free Trial cost protected. |
| 5 | Notification Version must be V2 | V1 still works but RevenueCat recommends V2; new integrations should always use V2. |
| 6 | RevenueCat URL is project-specific | No generic template; copy from RevenueCat dashboard each time. |
| 7 | Sandbox tester email must be unused | Plus-subaddress trick avoids needing multiple real inboxes. |
| 8 | Metadata propagation delay | ~1 hour after product creation/edit before sandbox sees the change. |
| 9 | Paid Apps agreement must be active | Otherwise subscriptions cannot be submitted for review. |
| 10 | Apple ID sign-in flow | On iOS device, sign out of Media & Purchases — NOT iCloud — before sandbox testing. |
| 11 | Description char limit | ASC UI currently enforces **55 characters per locale** (operator-confirmed empirically 2026-05-13). Apple's public docs ([promoting-in-app-purchases](https://developer.apple.com/app-store/promoting-in-app-purchases/) + [help/app-store-connect/reference/in-app-purchase-information](https://developer.apple.com/help/app-store-connect/reference/in-app-purchase-information/)) still state **45 characters** — Apple raised the cap without updating docs. To stay safe under either limit, keep copy ≤45 chars; the runbook's current copy is calibrated to 55 (yearly EN sits at exactly 55). Display Name caps at 30. No soft warning — over-limit is rejected at save time. |
| 12 | Description content compliance | Apple's content requirement on Description is "clearly distinguish the benefits of each offering" — the in-depth feature enumeration and price/auto-renew/free-trial disclosure obligations live on the **in-app sign-up screen** (paywall), NOT the Description field, per [App Store Review Guidelines §3.1.2(c)](https://developer.apple.com/app-store/review/guidelines/) + [Apple Subscriptions page](https://developer.apple.com/app-store/subscriptions/). "All Pro features" + savings claim style is compliant. Comparative savings claims ("Save 40%+") are explicitly permitted per the Subscriptions page "Billing amount" section, provided they sit subordinate to the total billing amount in the purchase flow. |

## Where this fits in the wider Phase 39 pipeline

This runbook is one of two stores you complete before the RevenueCat product-to-package binding step. Sequence:

1. **App Store Connect setup** (this runbook) → status "Ready to Submit"
2. **Play Console setup** ([iap-setup-play-console.md](iap-setup-play-console.md)) → both base plans Active
3. **RevenueCat product registration** (next session) — Claude uses the RevenueCat MCP to:
   - Import iOS products `io.github.b150005.skeinly.pro.monthly` + `.yearly`
   - Import Android products `skeinly_pro:pro-monthly` + `skeinly_pro:pro-yearly`
   - Attach all four to the existing `$rc_monthly` / `$rc_annual` packages
   - Confirm Skeinly Pro entitlement `entlaaca26b181` covers all four
4. **End-to-end smoke test** — open paywall on a TestFlight build with a sandbox tester signed in, purchase, verify entitlement granted and `subscriptions` row written via `revenuecat-webhook`.
