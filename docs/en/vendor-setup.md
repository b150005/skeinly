# Vendor Account Setup — Phase A0 Procedure

> Japanese translation: [docs/ja/vendor-setup.md](../ja/vendor-setup.md)

This is the sequential procedure for setting up vendor accounts and obtaining the artifacts (certs, keys, profiles, JSON files) that feed into [release-secrets.md](release-secrets.md). Phase A0 of the alpha1 release prep — must be complete before tagging `v1.0.0-alpha1`.

This doc is **scoped to Apple-side setup** (Apple Developer Portal + App Store Connect + Universal Links) because that is the critical path: Capability changes after Provisioning Profile generation force a profile regeneration cycle. Other vendor accounts (Google Play, Firebase, Sentry, PostHog) are setup-on-demand; their step-by-step OBTAIN procedures live inline in [release-secrets.md](release-secrets.md) per-secret.

## Skeinly constants

These values are used throughout this procedure. Substitute in vendor portal forms when prompted.

| Field | Value |
|---|---|
| Bundle ID (iOS) | `io.github.b150005.skeinly` |
| Application ID (Android) | `io.github.b150005.skeinly` |
| App Name | Skeinly |
| Default Language | English (U.S.) |
| Apple Developer Team ID | (your 10-char ID; populate after enrollment) |
| Privacy Policy URL | `https://b150005.github.io/skeinly/privacy-policy/` |
| Support URL | `https://github.com/b150005/skeinly/issues` |

## Prerequisites

- **Apple Developer Program enrollment** — $99/yr at <https://developer.apple.com/programs/>. Required before any of Phase A0 below.
- **Mac with Xcode 26+** — for Keychain Access and `.p12` export. See [README Prerequisites](../../README.md#prerequisites).
- **`gh` CLI authenticated** — `gh auth login`.

## Phase A0a — Apple Developer Portal

### A0a-1: Create the App ID

1. <https://developer.apple.com/account> → **Certificates, Identifiers & Profiles** → **Identifiers** → **+**.
2. **App IDs** → Continue → **App** → Continue.
3. Description: `Skeinly`.
4. Bundle ID: **Explicit** → `io.github.b150005.skeinly`.
5. **Capabilities — enable all four**:
   - **Sign In with Apple** — click *Configure*, choose **Enable as a primary App ID**, Save.
   - **Push Notifications** — checkbox only; configuration happens at APNs key generation (Phase A0a-2).
   - **Associated Domains** — checkbox only; domain values are configured in the entitlements file at app level, not on the App ID.
   - **In-App Purchase** — checkbox only.
6. Continue → Register.

The other capabilities (HealthKit, CloudKit, Game Center, etc.) are **not required for alpha1**. Adding them later forces Provisioning Profile regeneration; only enable when a feature actually needs them.

### A0a-2: Generate the APNs Auth Key (`.p8`)

1. Same Certificates, Identifiers & Profiles area → **Keys** → **+**.
2. Name: `skeinly APNs`.
3. Enable: **Apple Push Notifications service (APNs)**.
4. Continue → Register.
5. **Download the `.p8` file IMMEDIATELY**. One-time download — Apple discards the private key on their side after generation. The downloaded file is named `AuthKey_<KEY_ID>.p8`.
6. Note the **10-char Key ID** (visible on the Keys list).
7. Save the `.p8` file and Key ID to a password manager.

The same APNs key authorizes pushes from Skeinly's Supabase Edge Function for **both production and TestFlight** builds — no separate keys needed per environment.

**Register**:
- Base64-encode → register as `APPLE_APNS_KEY_BASE64` Supabase Edge Function secret per [release-secrets.md](release-secrets.md#supabase-edge-function-secrets).
- Register `APPLE_APNS_KEY_ID` Supabase Edge Function secret (10-char value).

### A0a-3: Apple Distribution Certificate

See [release-secrets.md §1](release-secrets.md#1-apple_distribution_cert_base64). No alpha1-specific changes — the existing Distribution cert covers all 4 capabilities once the App ID has them enabled.

### A0a-4: Provisioning Profile

See [release-secrets.md §3](release-secrets.md#3-apple_provisioning_profile_base64).

**Order matters**: Generate the profile **after** A0a-1 (so all 4 capabilities are baked into the profile). Profiles generated before adding a capability do not include it; you must regenerate the profile and re-register `APPLE_PROVISIONING_PROFILE_BASE64`.

### A0a-5: App Store Connect API Key

See [release-secrets.md §5–7](release-secrets.md#5-app_store_connect_api_key_base64).

The same key is used for:
- TestFlight upload via fastlane (CI / GitHub Secrets context).
- IAP receipt validation server-side via App Store Server API (Supabase Edge Function context).

Register the key in **both** contexts: GitHub Secret `APP_STORE_CONNECT_API_KEY_BASE64` AND Supabase Edge Function secret `APP_STORE_CONNECT_API_KEY` (single key file, two registration places).

## Phase A0b — App Store Connect (App + IAP)

### A0b-1: Create the App

1. <https://appstoreconnect.apple.com> → **My Apps** → **+** → **New App**.
2. Platform: **iOS** (checkbox).
3. Name: `Skeinly`.
4. Primary Language: **English (U.S.)**.
5. Bundle ID: select `io.github.b150005.skeinly` (must already exist from Phase A0a-1).
6. SKU: `skeinly-001`.
7. User Access: **Full Access**.
8. Create.

### A0b-2: Subscription Group

1. App detail → **Monetization** → **Subscriptions** → **Create Subscription Group**.
2. Reference Name: `Skeinly Pro`.
3. Localizations:
   - English (U.S.): Display Name `Skeinly Pro`.
   - Japanese: Display Name `Skeinly Pro` (brand name kept identical across locales).

### A0b-3: IAP Products — two subscriptions in the Pro group

| Field | Monthly | Yearly |
|---|---|---|
| Product ID | `skeinly.pro.monthly` | `skeinly.pro.yearly` |
| Reference Name | Monthly Pro | Yearly Pro |
| Price (USD) | $3.99 | $24.99 |
| Price (JPY) | ¥600 | ¥3,800 |
| Subscription Duration | 1 month | 1 year |
| Free Trial | 7 days (Introductory Offer → Free → 1 week) | 7 days |
| Localized Display Name (EN) | Monthly Pro | Yearly Pro |
| Localized Display Name (JA) | 月額 Pro | 年額 Pro |
| Description (EN) | Unlimited projects, structured chart editing, share send, pull request creation. Renews monthly. | Unlimited projects, structured chart editing, share send, pull request creation. Renews yearly (about 48% off vs monthly). |
| Description (JA) | プロジェクト無制限、構造化チャート編集、共有送信、PR 作成。毎月自動更新。 | プロジェクト無制限、構造化チャート編集、共有送信、PR 作成。年額更新（月額より約 48% お得）。 |
| Family Sharing | Off (alpha) | Off (alpha) |
| App Review Information | Sandbox tester credentials provided to App Review at submission time | Sandbox tester credentials provided to App Review at submission time |

After both products are created, the Pro group will hold them and the StoreKit 2 SDK will surface both in `Product.products(for:)` calls.

### A0b-4: Sandbox Tester for IAP testing

1. App Store Connect → **Users and Access** → **Sandbox Testers** → **+**.
2. Create at least 2 sandbox testers with separate emails. These are used to test purchase + restore + cancel + renewal flows during alpha development.
3. Document the credentials in your password manager — alpha testers cannot use sandbox testers; they get real Pro grant via the `subscriptions.platform = 'alpha-grant'` sentinel.

### A0b-5: Privacy declaration

1. App detail → **App Privacy**.
2. Privacy Policy URL: `https://b150005.github.io/skeinly/privacy-policy/`.
3. **Data Types Collected** — declare these for alpha1 (matches Sentry + PostHog + Feedback scope):
   - **Identifiers**: User ID (Supabase auth UUID), Device ID (PostHog distinct_id, opt-in)
   - **User Content**: Other User Content (knitting project data, patterns, comments)
   - **Diagnostics**: Crash Data (Sentry), Performance Data (Sentry)
   - **Usage Data**: Product Interaction (PostHog, opt-in)
4. Each data type must answer:
   - Linked to user? → Yes (User ID is)
   - Used for tracking? → **No** (we do not share with third parties for ads)

The Phase 27a privacy policy disclosure already mentions these — verify alignment when updating to alpha1.

## Phase A0c — Universal Links (AASA)

### A0c-1: Decide on the AASA hosting strategy

Apple requires the AASA file at `https://<domain>/.well-known/apple-app-site-association` (or at the root `https://<domain>/apple-app-site-association`) over HTTPS, **with `Content-Type: application/json`** and **no redirects**. The `applinks:<domain>` entitlement on the app must point to this same `<domain>`.

**Skeinly's hosting situation**: GitHub Pages serves this project at `https://b150005.github.io/skeinly/` (a project page under user `b150005`). The AASA must be at the apex of the associated domain — `https://b150005.github.io/.well-known/apple-app-site-association`. That apex is the **GitHub user site** for `b150005`, which lives in a separate repo `b150005/b150005.github.io`. The project repo `b150005/skeinly` cannot serve AASA at that apex.

Three options, in order of recommendation:

**Option A — Create a `b150005/b150005.github.io` user site (free, recommended for alpha)**
- New repo `b150005.github.io` under user `b150005`, public.
- Place `.well-known/apple-app-site-association` and `.well-known/assetlinks.json` at the repo root.
- GitHub Pages auto-deploys from `main` branch root.
- Resolves at `https://b150005.github.io/.well-known/apple-app-site-association` immediately after first deploy.
- Add `applinks:b150005.github.io` to the app entitlements; restrict path matches to `/skeinly/share/*` etc. so the user site doesn't accidentally route other paths into the app.

**Option B — Custom domain (paid, recommended for v1.0)**
- Buy `skeinly.app` or similar (Cloudflare Registrar at-cost, ~$10/yr).
- Configure GitHub Pages CNAME → custom domain.
- AASA at `https://skeinly.app/.well-known/apple-app-site-association`.
- Cleaner branding for production launch.

**Option C — Defer Universal Links to v1.0 (no domain investment for alpha)**
- Stay on `skeinly://share/<token>` URI scheme only for alpha.
- Accept that share URLs sent via SMS / email do not auto-open the app for alpha testers.
- Universal Links Capability stays disabled on the App ID until v1.0 (Provisioning Profile regenerates at that point — one-time cost).

**Recommendation for alpha1**: Option A. Cost is zero, hosting is unified with the rest of `b150005`'s projects, and the migration path to Option B is changing the CNAME later.

### A0c-2: AASA file content

Once the hosting target is chosen, place this file at `<host>/.well-known/apple-app-site-association`:

```json
{
  "applinks": {
    "details": [
      {
        "appIDs": ["TEAMID.io.github.b150005.skeinly"],
        "components": [
          { "/": "/skeinly/share/*", "comment": "Direct share invite deep link" },
          { "/": "/skeinly/pull-request/*", "comment": "PR open/comment/merge deep link" },
          { "/": "/skeinly/shared-content/*", "comment": "Shared pattern/project deep link" }
        ]
      }
    ]
  }
}
```

Replace `TEAMID` with your 10-char Apple Developer Team ID. Adjust the `/skeinly/...` prefix if you choose Option B (drop the prefix on a custom domain).

### A0c-3: assetlinks.json (Android App Links mirror)

Place at `<host>/.well-known/assetlinks.json`:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "io.github.b150005.skeinly",
    "sha256_cert_fingerprints": [
      "<sha256 of upload-keystore.jks SHA-256 fingerprint>"
    ]
  }
}]
```

Get the SHA-256 fingerprint via `keytool -list -v -keystore upload-keystore.jks` (see [release-secrets.md §8](release-secrets.md#8-keystore_base64) for the keystore generation procedure).

### A0c-4: Verification after deploy

```bash
# AASA must respond 200, JSON, no redirect, Content-Type: application/json
curl -I https://b150005.github.io/.well-known/apple-app-site-association

# assetlinks.json same constraints
curl -I https://b150005.github.io/.well-known/assetlinks.json

# Apple's own validator (alternative)
# https://search.developer.apple.com/appsearch-validation-tool
```

## Phase A0 verification checklist

Before tagging `v1.0.0-alpha1`:

- [ ] Apple Developer App ID `io.github.b150005.skeinly` exists with 4 capabilities enabled (Sign In with Apple, Push Notifications, Associated Domains, In-App Purchase)
- [ ] APNs Auth Key `.p8` downloaded and saved to password manager
- [ ] APPLE_APNS_KEY_BASE64 + APPLE_APNS_KEY_ID registered as Supabase Edge Function secrets
- [ ] Provisioning Profile regenerated after capabilities were added
- [ ] APPLE_PROVISIONING_PROFILE_BASE64 (re-)registered as GitHub Secret
- [ ] App Store Connect app `Skeinly` created with bundle ID `io.github.b150005.skeinly`
- [ ] Subscription Group `Skeinly Pro` created
- [ ] 2 IAP products created: `skeinly.pro.monthly` ($3.99/¥600) and `skeinly.pro.yearly` ($24.99/¥3,800), both with 7-day free trial
- [ ] At least 2 Sandbox Testers created
- [ ] App Privacy declaration submitted with Sentry + PostHog + Feedback data types
- [ ] AASA hosting decision made (Option A / B / C) and AASA + assetlinks.json deployed if Option A or B

## Cross-references

- Per-secret OBTAIN/VERIFY/REGISTER procedures: [release-secrets.md](release-secrets.md)
- Branch protection + CI requirements: [repo-policy.md](repo-policy.md)
- Privacy policy source: [docs/public/privacy-policy/](../public/privacy-policy/)
- Phase 39 alpha rubric: [phase/phase-39-beta-rubric.md](phase/phase-39-beta-rubric.md)
