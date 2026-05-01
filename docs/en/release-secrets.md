# Release Secrets — Setup Guide

> Japanese translation: [docs/ja/release-secrets.md](../ja/release-secrets.md)
>
> **⚠️ Out-of-sync notice (2026-05-01)**: vendor modernization updates (Supabase Publishable key migration, Sentry **Organization** Auth Token clarification, PostHog free-tier 1-project consolidation, Firebase 2-project + GitHub Environment secrets layout, RevenueCat section, Edge Function `REVENUECAT_WEBHOOK_SECRET`) have landed in **JA only**. Refer to `docs/ja/release-secrets.md` for the current canonical guidance until this English file is synced. Tracking: separate follow-up PR.

This document is a step-by-step guide for obtaining, verifying, and registering every secret consumed by the release pipeline and the Supabase Edge Functions. It covers **19 GitHub Secrets** in 6 categories plus **4 Supabase Edge Function runtime secrets** in a 7th category:

**GitHub Secrets** (registered via `gh secret set`):
- **iOS code signing** (4) — Distribution cert + provisioning profile + Team ID
- **App Store Connect API** (3) — `.p8` API key + Key ID + Issuer ID
- **Android signing** (4) — keystore + passwords + alias
- **Supabase runtime** (2) — backend URL + anon key
- **Android FCM client** (1) — `google-services.json` for Push client SDK
- **Crash + error reporting** (3) — Sentry DSNs (iOS + Android) + Auth Token
- **Analytics** (2) — PostHog project API keys (prod + dev)

**Supabase Edge Function Secrets** (registered via `supabase secrets set`):
- **iOS Push** (2) — APNs `.p8` + Key ID
- **Android Push** (1) — Firebase Service Account JSON for FCM HTTP v1
- **Android IAP** (1) — Google Play Service Account JSON for receipt validation
- (App Store Connect API key is reused from GitHub Secrets — same `.p8` file, registered in both contexts.)

The release workflow ([`.github/workflows/release.yml`](../../.github/workflows/release.yml)) reads GitHub Secrets as `${{ secrets.* }}`. Missing or incorrect values fail the release silently for some (build still succeeds, just no upload) or loudly for others (signing fails). The Supabase Edge Function reads its own secrets via `Deno.env.get(...)`. This guide includes verification steps so you can confirm a value is correct **before** registering it.

## Table of contents

- [Prerequisites](#prerequisites)
- [How to register a secret in GitHub](#how-to-register-a-secret-in-github)
- [iOS code signing (4 secrets)](#ios-code-signing-4-secrets)
- [App Store Connect API (3 secrets)](#app-store-connect-api-3-secrets)
- [Android signing (4 secrets)](#android-signing-4-secrets)
- [Supabase runtime (2 secrets)](#supabase-runtime-2-secrets)
- [Android FCM client (1 secret)](#android-fcm-client-1-secret)
- [Crash + error reporting — Sentry (3 secrets)](#crash--error-reporting--sentry-3-secrets)
- [Analytics — PostHog (2 secrets)](#analytics--posthog-2-secrets)
- [Supabase Edge Function Secrets (4 secrets)](#supabase-edge-function-secrets-4-secrets)
- [Bulk verification](#bulk-verification)
- [Rotation and revocation](#rotation-and-revocation)
- [Security notes](#security-notes)

## Prerequisites

| Tool | Purpose | Install |
|---|---|---|
| `gh` (GitHub CLI) | Register secrets without exposing values to clipboard | `brew install gh` then `gh auth login` |
| `base64` | Encode binary files for storage as text | Pre-installed on macOS / Linux |
| `openssl` | Verify `.p12` and `.p8` content | Pre-installed on macOS |
| `security` (macOS) | Verify `.mobileprovision` content | Pre-installed |
| `keytool` (JDK) | Verify Android `.jks` keystore content | Bundled with JDK 17+ |

Authenticate `gh` once before you start:

```bash
gh auth login
gh auth status   # should show: Logged in to github.com as <you>
```

All secret commands assume you are inside the repository root (`cd skeinly`).

## How to register a secret in GitHub

Two methods. Prefer `gh secret set` because the value never enters your shell history or clipboard.

**Method A — `gh secret set` from a file:**

```bash
gh secret set APPLE_DISTRIBUTION_CERT_BASE64 < distribution.p12.base64
# verify it landed (shows name + last-updated, never the value)
gh secret list
```

**Method B — `gh secret set` interactive:**

```bash
gh secret set APPLE_TEAM_ID
# paste the value, press Ctrl+D to terminate
```

**Method C — GitHub UI:**

Repository → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**. Paste the value. Click **Add secret**.

Method A is the safest for binary blobs (long base64 strings). Method B for short string values (Team ID, Key ID, Issuer ID). Method C as fallback if `gh` is unavailable.

To remove a secret:

```bash
gh secret delete <SECRET_NAME>
```

## iOS code signing (4 secrets)

These four cryptographic assets allow CI to sign the iOS app as your Apple Developer team. They have nothing to do with App Store Connect API authentication — that comes next.

### 1. `APPLE_DISTRIBUTION_CERT_BASE64`

**WHAT**: Base64-encoded `.p12` file containing your **Apple Distribution** certificate AND its private key.

**OBTAIN:**

1. Open **Keychain Access** (macOS).
2. Set the left sidebar to **default keychains** → **login** → **My Certificates**.
3. Find a certificate named like `Apple Distribution: Your Name (TEAMID)`.
   - If it does not exist: open Xcode → **Settings** → **Accounts** → select your Apple ID → **Manage Certificates** → click `+` → **Apple Distribution**. The new cert appears in Keychain Access.
4. Right-click the cert → **Export "Apple Distribution: …"** → save as `distribution.p12`.
5. **Set a password** when prompted (any string, e.g. an `openssl rand -hex 16` value). Save this password — it becomes `APPLE_DISTRIBUTION_CERT_PASSWORD`.
6. Base64-encode:

   ```bash
   base64 -i distribution.p12 -o distribution.p12.base64
   ```

**VERIFY** (before registering):

```bash
# Inspect the .p12 — should show issuer "Apple Distribution: Your Name"
# When prompted, enter the password you set in step 5.
openssl pkcs12 -in distribution.p12 -info -nokeys -legacy
```

You should see lines like:

```
issuer=/CN=Apple Worldwide Developer Relations Certification Authority/...
subject=/UID=.../CN=Apple Distribution: Your Name (TEAMID)/...
```

**REGISTER:**

```bash
gh secret set APPLE_DISTRIBUTION_CERT_BASE64 < distribution.p12.base64
```

**ROTATE**: Apple Developer → **Certificates, Identifiers & Profiles** → **Certificates** → revoke the old one, then repeat from step 3.

### 2. `APPLE_DISTRIBUTION_CERT_PASSWORD`

**WHAT**: The password you set during `.p12` export in step 5 above.

**OBTAIN**: You set it yourself. Generate a strong one if you didn't:

```bash
openssl rand -hex 16
```

Use that string at `.p12` export time. Re-export the cert if you forgot the password.

**VERIFY**: The `openssl pkcs12 -info` command above accepting the password is the verification.

**REGISTER:**

```bash
gh secret set APPLE_DISTRIBUTION_CERT_PASSWORD
# paste password, Ctrl+D
```

### 3. `APPLE_PROVISIONING_PROFILE_BASE64`

**WHAT**: Base64-encoded `.mobileprovision` App Store provisioning profile.

**OBTAIN:**

1. Sign in to [Apple Developer](https://developer.apple.com/account) → **Certificates, Identifiers & Profiles** → **Profiles**.
2. Either:
   - Use an existing profile if one already covers `io.github.b150005.skeinly` for App Store distribution. Click it → **Download**.
   - Or click `+` → **App Store** under Distribution → select bundle ID `io.github.b150005.skeinly` → select the Apple Distribution cert from step 1 → name it (e.g. `Skeinly App Store`) → **Generate** → **Download**.
3. Base64-encode:

   ```bash
   base64 -i Skeinly_App_Store.mobileprovision -o profile.base64
   ```

**VERIFY:**

```bash
# Decode the CMS envelope — output is XML plist with the profile metadata.
security cms -D -i Skeinly_App_Store.mobileprovision | head -40
```

Confirm:
- `<key>Name</key>` value matches what you named the profile
- `<key>application-identifier</key>` ends with `io.github.b150005.skeinly`
- `<key>ExpirationDate</key>` is in the future (typically 1 year out)
- `<key>TeamIdentifier</key>` matches your Team ID (next secret)

**REGISTER:**

```bash
gh secret set APPLE_PROVISIONING_PROFILE_BASE64 < profile.base64
```

**ROTATE**: Profiles expire 1 year after creation. Mark your calendar 11 months out. Renewal procedure is identical to step 2.

### 4. `APPLE_TEAM_ID`

**WHAT**: Your 10-character alphanumeric Apple Developer Team ID.

**OBTAIN:**

- [Apple Developer](https://developer.apple.com/account) → **Membership** → **Team ID** field.
- Or, extracted from the `.mobileprovision` you just downloaded:

   ```bash
   security cms -D -i Skeinly_App_Store.mobileprovision \
     | grep -A1 'TeamIdentifier' | tail -1 \
     | sed -E 's/.*<string>([^<]+)<\/string>.*/\1/'
   ```

**VERIFY**: 10 characters, A–Z and 0–9. Anything shorter or with lowercase or symbols is wrong.

**REGISTER:**

```bash
gh secret set APPLE_TEAM_ID
# paste, Ctrl+D
```

## App Store Connect API (3 secrets)

These three credentials authorize CI to upload builds to TestFlight via the App Store Connect REST API. They are independent from code signing — even if signing succeeds, missing/wrong API credentials only block the upload step.

### 5. `APP_STORE_CONNECT_API_KEY_BASE64`

**WHAT**: Base64-encoded `.p8` private key file (PKCS#8 format).

**OBTAIN:**

1. Sign in to [App Store Connect](https://appstoreconnect.apple.com).
2. **Users and Access** → **Integrations** tab → **Team Keys**.
3. Click **Generate API Key** (or `+` if keys already exist).
4. Set:
   - **Name**: e.g. `skeinly CI`
   - **Access**: at minimum **App Manager** for TestFlight uploads. **Developer** is insufficient. **Admin** works but is over-privileged for CI.
5. Click **Generate**.
6. **Download the `.p8` file IMMEDIATELY**. This is your only chance — Apple discards the private key on their side after generation. The downloaded file is named `AuthKey_<KEY_ID>.p8`.
7. Base64-encode:

   ```bash
   base64 -i AuthKey_XYZ1234567.p8 -o p8.base64
   ```

**VERIFY:**

```bash
# Should print "-----BEGIN PRIVATE KEY-----"
head -1 AuthKey_XYZ1234567.p8

# Or after base64 encoding — should round-trip back to the same first line
base64 -d p8.base64 | head -1
```

**REGISTER:**

```bash
gh secret set APP_STORE_CONNECT_API_KEY_BASE64 < p8.base64
```

**SECURE BACKUP**: Move `AuthKey_XYZ1234567.p8` to a password-managed location (1Password, etc.) immediately after registering the GitHub Secret. If the GitHub Secret is ever deleted, you cannot regenerate the same key — you would need to generate a new key (and revoke the old one).

**ROTATE**: App Store Connect → Users and Access → Integrations → Team Keys → revoke the old key, then repeat steps 3–7. Update both `APP_STORE_CONNECT_API_KEY_BASE64` and `APP_STORE_CONNECT_API_KEY_ID` GitHub Secrets.

### 6. `APP_STORE_CONNECT_API_KEY_ID`

**WHAT**: The 10-character Key ID associated with the `.p8` you just downloaded.

**OBTAIN:**

- Same page (Team Keys). The **Key ID** column shows the value next to the key name.
- Also embedded in the `.p8` filename: `AuthKey_<KEY_ID>.p8`.

**VERIFY**: 10 characters, alphanumeric. Must match the filename suffix of the `.p8`.

**REGISTER:**

```bash
gh secret set APP_STORE_CONNECT_API_KEY_ID
# paste 10-char ID, Ctrl+D
```

### 7. `APP_STORE_CONNECT_ISSUER_ID`

**WHAT**: A UUID identifying your App Store Connect account organization.

**OBTAIN:**

- Same Team Keys page. The **Issuer ID** is displayed at the top of the page, above the active keys table.
- Format: `8-4-4-4-12` hex digits separated by dashes (UUID v4 format).

**VERIFY**: Looks like `69a6de70-03db-47e3-e053-5b8c7c11a4d1`. 36 characters total including dashes.

**REGISTER:**

```bash
gh secret set APP_STORE_CONNECT_ISSUER_ID
# paste UUID, Ctrl+D
```

## Android signing (4 secrets)

These four credentials sign the Android Release APK so Google Play accepts it. The signing keystore is **non-rotatable for the lifetime of the app on Play** — losing it means publishing as a new app under a different ID. Treat it accordingly.

### 8. `KEYSTORE_BASE64`

**WHAT**: Base64-encoded Android signing keystore (`.jks` file).

**OBTAIN — first time:**

```bash
keytool -genkey -v \
  -keystore upload-keystore.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias upload
```

You will be prompted for:
- **Keystore password** — becomes `KEYSTORE_PASSWORD`
- **Distinguished Name** — first/last name, organizational unit, organization, locality, state, country code (any reasonable values; this metadata is embedded in every signed APK and visible to anyone who inspects it)
- **Key password** — separate from keystore password (commonly set to the same value for simplicity); becomes `KEY_PASSWORD`

**OBTAIN — already exists:**

Locate the existing `.jks` file. Common locations:
- `~/.android/upload-keystore.jks`
- A team's password manager / shared secrets vault

**ENCODE:**

```bash
base64 -i upload-keystore.jks -o keystore.base64
```

**VERIFY:**

```bash
# Lists all aliases in the keystore — should include `upload` (or whatever alias you chose)
keytool -list -v -keystore upload-keystore.jks
# Enter keystore password when prompted.
```

You should see entries with:
- `Alias name: upload`
- `Valid from: ... until: ...` (10000 days = ~27 years)
- `SHA-256: AB:CD:EF:...` fingerprint

**REGISTER:**

```bash
gh secret set KEYSTORE_BASE64 < keystore.base64
```

**SECURE BACKUP**: Same rule as `.p8` — move to a password manager immediately. **A lost upload keystore cannot be recovered**; Google Play offers a "key reset" via support but it is a multi-week, app-disrupting process. Maintain at least two encrypted backups (e.g. password manager + offline encrypted USB).

### 9. `KEYSTORE_PASSWORD`

**WHAT**: The "keystore password" you set during `keytool -genkey`.

**OBTAIN**: You set it yourself. If forgotten, the keystore is unrecoverable — see Backup above.

**REGISTER:**

```bash
gh secret set KEYSTORE_PASSWORD
# paste, Ctrl+D
```

### 10. `KEY_ALIAS`

**WHAT**: The alias passed to `-alias` during `keytool -genkey` (defaults to `upload` in the example above).

**VERIFY:**

```bash
keytool -list -keystore upload-keystore.jks
# the "Alias name" column shows valid aliases.
```

**REGISTER:**

```bash
gh secret set KEY_ALIAS
# paste alias, Ctrl+D
```

### 11. `KEY_PASSWORD`

**WHAT**: The "key password" you set during `keytool -genkey` (separate from keystore password). Often set to the same value as `KEYSTORE_PASSWORD` to simplify CI configuration.

**REGISTER:**

```bash
gh secret set KEY_PASSWORD
# paste, Ctrl+D
```

## Supabase runtime (2 secrets)

These secrets are baked into the Android APK and iOS IPA at build time so the app can reach the Supabase backend. They are **not** signing secrets — they are runtime configuration.

### 12. `SUPABASE_URL`

**WHAT**: The public URL of your Supabase project.

**OBTAIN:**

- [Supabase Dashboard](https://supabase.com/dashboard) → select your project → **Project Settings** → **Data API** → **Project URL**.
- Format: `https://<project-ref>.supabase.co`.

**VERIFY**: Starts with `https://`, ends with `.supabase.co`, contains the 20-char project ref.

**REGISTER:**

```bash
gh secret set SUPABASE_URL
# paste, Ctrl+D
```

### 13. `SUPABASE_PUBLISHABLE_KEY`

> **2025-11-01 full migration**: legacy `SUPABASE_ANON_KEY` (`eyJ...` JWT) has been fully retired in this codebase and replaced by `SUPABASE_PUBLISHABLE_KEY` across every consumer (Kotlin `expect/actual val publishableKey`, iOS `Info.plist` key, Gradle env, all CI workflows). See `docs/ja/release-secrets.md` for the canonical guidance.

**WHAT**: The "Publishable" public API key (`sb_publishable_…`). Designed to be embedded in client apps (RLS policies prevent unauthorized access at the database layer). Opaque token, NOT a JWT.

**OBTAIN:**

1. Supabase Dashboard → project (`Skeinly`) → **Project Settings** → **API Keys**
2. **Publishable key** tab → copy the existing key or **Generate new key** (starts with `sb_publishable_`)

**VERIFY**: Starts with `sb_publishable_`. Not the legacy JWT (`eyJ...`).

**REGISTER:**

```bash
gh secret set SUPABASE_PUBLISHABLE_KEY
# paste sb_publishable_..., Ctrl+D
```

**Cleanup of legacy `SUPABASE_ANON_KEY`** (after migration):

```bash
gh secret delete SUPABASE_ANON_KEY
```

**Do NOT register the `Secret key` (`sb_secret_…`, the successor of `service_role`) as a GitHub Secret for client builds**. The Secret key bypasses RLS and would be a critical leak if shipped in an APK.

## Android FCM client (1 secret)

This secret is the Firebase project's client-side configuration for the Android app. It is read at build time and embedded in the APK so the FCM SDK can register with Firebase. The value is restricted to the app's package name + signing certificate SHA-1 in Firebase, so leaking it is low-blast-radius — but we still keep it git-ignored to avoid clutter and to support per-environment swaps later.

### 14. `FIREBASE_GOOGLE_SERVICES_JSON_BASE64`

**WHAT**: Base64-encoded `google-services.json` file from the Firebase Console for the Android app.

**OBTAIN:**

1. Sign in to [Firebase Console](https://console.firebase.google.com).
2. If no Firebase project exists for Skeinly: **Add project** → Name `skeinly` → **Disable Google Analytics** (we use PostHog) → Create.
3. Inside the project: **Project Overview** → **Add app** → Android icon.
4. Android package name: `io.github.b150005.skeinly`.
5. App nickname: `Skeinly Android`.
6. Signing certificate SHA-1: run `keytool -list -v -keystore upload-keystore.jks` and copy the **SHA-1** fingerprint (release keystore — debug builds use a separate auto-generated keystore that does not need registration here for alpha; add the debug SHA-1 later if FCM debug testing is needed).
7. Continue → Continue → **Download `google-services.json`** → Continue → skip the SDK setup step (we wire SDKs via Gradle separately).
8. Base64-encode:

   ```bash
   base64 -i google-services.json -o google-services.base64
   ```

**VERIFY:**

```bash
# Should print a JSON object with "project_info" and "client" keys.
cat google-services.json | python3 -m json.tool | head -10
```

Confirm:
- `project_info.project_id` matches your Firebase project name (`skeinly`)
- `client[0].client_info.android_client_info.package_name` is `io.github.b150005.skeinly`
- `client[0].api_key[0].current_key` is a long alphanumeric string (the Android API key, restricted to the package + SHA-1)

**REGISTER:**

```bash
gh secret set FIREBASE_GOOGLE_SERVICES_JSON_BASE64 < google-services.base64
```

The Android Gradle build decodes this at build time into `androidApp/google-services.json` (git-ignored).

**ROTATE**: Re-download from the Firebase Console only if you change the package name (Phase 28 already settled this) or the signing SHA-1 (which means losing the upload keystore — see [§8 Backup](#8-keystore_base64) for why this is unrecoverable). Day-to-day no rotation is needed.

## Crash + error reporting — Sentry (3 secrets)

These secrets wire the Sentry SDK on iOS + Android and authorize CI to upload debug symbols (dSYM for iOS, mapping files for Android) so stack traces are symbolicated automatically in the Sentry dashboard.

### 15. `SENTRY_DSN_IOS`

**WHAT**: The Data Source Name (DSN) for the iOS Sentry project. A URL-shaped string identifying the Sentry project + auth.

**OBTAIN:**

1. Sign in to [Sentry](https://sentry.io). Create an organization for Skeinly if not already.
2. **Projects** → **Create Project** → Platform: **Apple iOS** → Project Name: `skeinly-ios` → Create.
3. After creation: **Settings** → **Projects** → `skeinly-ios` → **Client Keys (DSN)** → copy the DSN.
4. Format: `https://<32-char-public-key>@<org>.ingest.sentry.io/<project-id>`.

**VERIFY**: URL parses as HTTPS, contains `@`, contains `.ingest.sentry.io`, ends with a numeric project ID.

**REGISTER:**

```bash
gh secret set SENTRY_DSN_IOS
# paste DSN, Ctrl+D
```

**ROTATE**: Sentry → Settings → Projects → `skeinly-ios` → Client Keys → revoke old key + create new. Update GitHub Secret.

### 16. `SENTRY_DSN_ANDROID`

**WHAT**: DSN for the Android Sentry project. Same shape as `SENTRY_DSN_IOS` but a separate Sentry project so iOS and Android crashes filter independently.

**OBTAIN**: Repeat the procedure for #15, but choose Platform: **Android** and name the project `skeinly-android`.

**REGISTER:**

```bash
gh secret set SENTRY_DSN_ANDROID
# paste DSN, Ctrl+D
```

### 17. `SENTRY_AUTH_TOKEN`

**WHAT**: A user auth token that lets CI upload dSYMs (iOS) and mapping files (Android) to Sentry after each release build, so stack traces are symbolicated automatically.

**OBTAIN:**

1. Sentry → click your avatar (top-left) → **User Settings** → **Auth Tokens**.
2. **Create New Token**.
3. Name: `skeinly CI`.
4. Scopes (minimum):
   - `project:releases` — create releases + upload artifacts
   - `org:read` — list orgs/projects (required by sentry-cli to resolve the project from a slug)
5. Create → copy the token immediately (one-time view).

**VERIFY:**

```bash
# Token shape: 64-char hex; sentry-cli can verify connectivity.
brew install getsentry/tools/sentry-cli  # if not installed
sentry-cli --auth-token <token> info
# Should show your org name + project list.
```

**REGISTER:**

```bash
gh secret set SENTRY_AUTH_TOKEN
# paste token, Ctrl+D
```

**ROTATE**: User Settings → Auth Tokens → revoke + recreate. Update GitHub Secret.

## Analytics — PostHog (2 secrets)

These secrets wire the PostHog SDK on iOS + Android. Unlike Sentry, we use the **same project key** for both platforms but separate **production / development** PostHog projects so dev events don't pollute the prod dataset. Both keys are configured with `auto_capture: false` and gated behind an opt-in toggle (Settings → Allow usage analytics, default OFF) per Phase 27a privacy policy.

### 18. `POSTHOG_PROJECT_API_KEY_PROD`

**WHAT**: The Project API Key for the production PostHog project. Embedded in release builds. Format: `phc_<43-char-base62>`.

**OBTAIN:**

1. Sign in to [PostHog](https://eu.posthog.com) (use the **EU cloud** for GDPR data residency).
2. If no organization exists: create one named `skeinly`.
3. **Settings** → **Projects** → **Create Project** → Name: `skeinly-prod`.
4. Inside the project: **Settings** → **Project** → **General** → **Project API Key** → copy the value (starts with `phc_`).

**VERIFY**: Starts with `phc_`, exactly 47 chars total (`phc_` + 43-char base62), case-sensitive.

**REGISTER:**

```bash
gh secret set POSTHOG_PROJECT_API_KEY_PROD
# paste, Ctrl+D
```

**ROTATE**: PostHog → Settings → Project → reset the Project API Key. Update GitHub Secret. Old events stay attributed to the project; only new ingestion shifts.

### 19. `POSTHOG_PROJECT_API_KEY_DEV`

**WHAT**: Project API Key for the development PostHog project. Embedded in debug builds so developer churn doesn't show up in the prod dataset.

**OBTAIN**: Repeat #18 but create a **separate** project named `skeinly-dev` and copy that project's API key.

**REGISTER:**

```bash
gh secret set POSTHOG_PROJECT_API_KEY_DEV
# paste, Ctrl+D
```

## Supabase Edge Function Secrets (4 secrets)

These secrets are consumed by Supabase Edge Functions (`notify-on-write` for Push, `verify-receipt` for IAP receipt validation). They are **not** GitHub Secrets — they are registered against the Supabase project via the Supabase CLI:

```bash
supabase login                                # one-time
supabase link --project-ref <your-project-ref>
supabase secrets list                         # verify what is currently registered
```

Edge Functions read these as `Deno.env.get("APPLE_APNS_KEY_P8")` etc. at runtime.

### EF-1. `APPLE_APNS_KEY_P8`

**WHAT**: Raw text content of the APNs `.p8` Auth Key file generated in [vendor-setup.md A0a-2](vendor-setup.md#a0a-2-generate-the-apns-auth-key-p8). NOT base64-encoded — Supabase secrets accept the raw multi-line PEM body.

**OBTAIN**: See [vendor-setup.md A0a-2](vendor-setup.md#a0a-2-generate-the-apns-auth-key-p8). The downloaded file `AuthKey_<KEY_ID>.p8` is what you register, raw.

**REGISTER:**

```bash
supabase secrets set APPLE_APNS_KEY_P8="$(cat AuthKey_XYZ1234567.p8)"
supabase secrets list | grep APPLE_APNS
```

### EF-2. `APPLE_APNS_KEY_ID`

**WHAT**: 10-char Key ID from the Apple Developer Keys page. Same value embedded in the `.p8` filename.

**REGISTER:**

```bash
supabase secrets set APPLE_APNS_KEY_ID=XYZ1234567
```

The Edge Function also needs `APPLE_TEAM_ID` (same 10-char value as the GitHub Secret) so it can construct JWT tokens for APNs:

```bash
supabase secrets set APPLE_TEAM_ID=ABCDE12345
```

(The Bundle ID `io.github.b150005.skeinly` is hard-coded in the Edge Function source, not in secrets.)

### EF-3. `FIREBASE_SERVICE_ACCOUNT_JSON`

**WHAT**: Service Account JSON for sending pushes via FCM HTTP v1 API. The Edge Function uses this to mint short-lived OAuth 2.0 access tokens for FCM.

**OBTAIN:**

1. [Firebase Console](https://console.firebase.google.com) → your project (`skeinly`) → **Project Settings** → **Service Accounts**.
2. **Firebase Admin SDK** tab → **Generate new private key** → confirm → JSON downloads.
3. Default-shipped role is **Firebase Admin SDK Administrator Service Agent** which includes Cloud Messaging — no extra IAM tweaking needed.

**VERIFY**: JSON contains `"type": "service_account"`, `project_id` matches your Firebase project, and `client_email` ends with `@<project-id>.iam.gserviceaccount.com`.

**REGISTER:**

```bash
supabase secrets set FIREBASE_SERVICE_ACCOUNT_JSON="$(cat firebase-admin-sdk.json)"
```

**ROTATE**: Firebase Console → Project Settings → Service Accounts → Manage all service accounts → click the SA → Keys tab → revoke old + add new JSON.

### EF-4. `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`

**WHAT**: Service Account JSON for the Google Play Developer API, used to validate Android IAP receipts server-side and observe subscription state changes (renewal, cancellation, refund).

**OBTAIN:**

1. [Google Play Console](https://play.google.com/console) → **Setup** → **API access**.
2. If not already linked: **Choose a project to link** → use the same Google Cloud project as Firebase (or a separate one — both are fine; reuse keeps billing simpler).
3. **Service accounts** section → **Create new service account** → opens Google Cloud Console.
4. Service Account Name: `skeinly-play-publisher`.
5. Skip role grants in Cloud Console (Play Console handles role grants in its own UI).
6. Done → back in Cloud Console: click the SA → **Keys** → **Add Key** → **Create new key** → JSON → Download.
7. Back in Play Console **API access** → click the new SA → **Grant access**.
8. Permissions: **View financial data**, **Manage orders**, **Manage store presence** at minimum (covers receipt validation).
9. Apply.

**VERIFY**: JSON has `"type": "service_account"`. The Play Console **API access** page shows the SA listed under **Service accounts** with green checkmarks on the granted permissions.

**REGISTER:**

```bash
supabase secrets set GOOGLE_PLAY_SERVICE_ACCOUNT_JSON="$(cat play-developer-api.json)"
```

**ROTATE**: Cloud Console → IAM → Service Accounts → click SA → Keys → revoke old + create new JSON. Update Supabase Edge Function secret.

### Reused: App Store Connect API key

The Edge Function `verify-receipt` (iOS branch) calls the App Store Server API and needs the same App Store Connect API key already registered as GitHub Secrets §5–§7. Register it with Supabase too (single source of truth, two registration places):

```bash
# Same .p8 file as APP_STORE_CONNECT_API_KEY_BASE64 GitHub Secret, but raw not base64.
supabase secrets set APP_STORE_CONNECT_API_KEY="$(cat AuthKey_XYZ1234567.p8)"
supabase secrets set APP_STORE_CONNECT_KEY_ID=XYZ1234567
supabase secrets set APP_STORE_CONNECT_ISSUER_ID=69a6de70-03db-47e3-e053-5b8c7c11a4d1
```

When you rotate the GitHub Secret per [§5 ROTATE](#5-app_store_connect_api_key_base64), also re-register the Supabase Edge Function secret with the new key.

## Bulk verification

After registering all 19 GitHub Secrets, confirm with `gh`:

```bash
gh secret list
```

Expected output (names + last-updated timestamps; values are never shown):

```
APPLE_DISTRIBUTION_CERT_BASE64        Updated YYYY-MM-DD
APPLE_DISTRIBUTION_CERT_PASSWORD      Updated YYYY-MM-DD
APPLE_PROVISIONING_PROFILE_BASE64     Updated YYYY-MM-DD
APPLE_TEAM_ID                         Updated YYYY-MM-DD
APP_STORE_CONNECT_API_KEY_BASE64      Updated YYYY-MM-DD
APP_STORE_CONNECT_API_KEY_ID          Updated YYYY-MM-DD
APP_STORE_CONNECT_ISSUER_ID           Updated YYYY-MM-DD
FIREBASE_GOOGLE_SERVICES_JSON_BASE64  Updated YYYY-MM-DD
KEY_ALIAS                             Updated YYYY-MM-DD
KEY_PASSWORD                          Updated YYYY-MM-DD
KEYSTORE_BASE64                       Updated YYYY-MM-DD
KEYSTORE_PASSWORD                     Updated YYYY-MM-DD
POSTHOG_PROJECT_API_KEY_DEV           Updated YYYY-MM-DD
POSTHOG_PROJECT_API_KEY_PROD          Updated YYYY-MM-DD
SENTRY_AUTH_TOKEN                     Updated YYYY-MM-DD
SENTRY_DSN_ANDROID                    Updated YYYY-MM-DD
SENTRY_DSN_IOS                        Updated YYYY-MM-DD
SUPABASE_PUBLISHABLE_KEY              Updated YYYY-MM-DD
SUPABASE_URL                          Updated YYYY-MM-DD
```

19 entries. Anything missing or with a stale timestamp is suspect.

For Supabase Edge Function secrets (registered via `supabase secrets set`):

```bash
supabase secrets list
```

Expected (8 entries — 4 unique to Edge Function + 4 reused values from App Store Connect API):

```
APP_STORE_CONNECT_API_KEY
APP_STORE_CONNECT_ISSUER_ID
APP_STORE_CONNECT_KEY_ID
APPLE_APNS_KEY_ID
APPLE_APNS_KEY_P8
APPLE_TEAM_ID
FIREBASE_SERVICE_ACCOUNT_JSON
GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
```

The first end-to-end verification of the iOS pipeline only happens at tag push — the iOS release job is gated on tag triggers, not regular pushes. Before the first alpha/beta tag push, you can:

- Run `make release-ipa-local` locally to verify the iOS signing chain (uses your local Mac keychain, not the CI secrets — but proves the cert + profile are valid).
- For the Android pipeline, push any commit to `main` to trigger CI which exercises the keystore in the regular `assembleDebug` path (not exactly the release path but close).

## Rotation and revocation

Rotate when:
- A team member with secret access leaves
- A laptop containing the original `.p12` / `.p8` is lost or stolen
- An automation key is suspected of leak (e.g. a public log accidentally echoed it)
- Annual hygiene (recommended every 12 months for API keys, profiles)

Specifically:

| Secret | Rotation procedure | Frequency |
|---|---|---|
| `APPLE_DISTRIBUTION_CERT_BASE64` + password | Apple Developer → Certificates → revoke + create new + re-export `.p12` | Annual or on incident |
| `APPLE_PROVISIONING_PROFILE_BASE64` | Apple Developer → Profiles → regenerate (same cert) | Forced annually by Apple |
| `APPLE_TEAM_ID` | Cannot change without changing teams | N/A |
| `APP_STORE_CONNECT_API_KEY_*` | App Store Connect → Team Keys → revoke + generate new (also re-register Supabase Edge Function secret) | Recommended every 12 months |
| `KEYSTORE_*` | **Do NOT rotate**. Losing the keystore breaks Play Store updates. Use Google Play's "App Signing by Google Play" key reset only as a last resort. | Never (under normal conditions) |
| `SUPABASE_*` | Supabase Dashboard → Project Settings → API Keys → reset anon key | On suspected leak |
| `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` | Re-download from Firebase Console only on package or signing SHA-1 change | Effectively never |
| `SENTRY_DSN_*` | Sentry → Settings → Project → Client Keys → revoke + create new | On suspected leak |
| `SENTRY_AUTH_TOKEN` | User Settings → Auth Tokens → revoke + recreate | Annual or on incident |
| `POSTHOG_PROJECT_API_KEY_*` | PostHog → Settings → Project → reset Project API Key | On suspected leak |
| Edge Function `APPLE_APNS_KEY_*` | Apple Developer → Keys → revoke + generate new + re-register Supabase secret | Annual or on incident |
| Edge Function `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase Console → Service Accounts → revoke + new key | Annual or on incident |
| Edge Function `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Cloud Console → IAM → Service Accounts → revoke + new key | Annual or on incident |

After rotating, re-run `gh secret set` for each affected secret. The next CI run picks up the new value automatically.

## Security notes

- **Never commit decoded files** (`.p12`, `.p8`, `.mobileprovision`, `.jks`) to the repository. Treat them like passwords.
- **Never paste secret values into AI assistant chats**, screenshots, or screen recordings. The base64 encoding is **not** encryption — anyone who sees the string can decode it.
- **Verify before registering**: every section above includes a `VERIFY` step. Skipping verification means a typo lands in production and the failure surfaces only at tag push.
- **`.p8` and `.jks` files have one-time-only download semantics** — back up to encrypted storage (password manager, encrypted disk image) immediately after generation.
- **GitHub Secrets are encrypted at rest** but visible to any workflow running with `secrets.*` access. Limit the number of workflows that read sensitive secrets via `permissions:` and `if:` guards.
- **The 7 iOS secrets allow your CI to upload to TestFlight under your Apple Developer account.** A leak permits an attacker to push malicious builds to your TestFlight testers. Revoke `APP_STORE_CONNECT_API_KEY_*` first if a leak is suspected — it has the highest blast radius.
