# Release Secrets — Setup Guide

> Japanese translation: [docs/ja/release-secrets.md](../ja/release-secrets.md)

This document is a step-by-step guide for obtaining, verifying, and registering every secret consumed by the release pipeline and the Supabase Edge Functions. It covers **19 GitHub Secrets at Repository scope** + **3 Environment-scoped secrets** (`FIREBASE_GOOGLE_SERVICES_JSON_BASE64` and `FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64` × 2 environments, plus `GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64` × 1 environment = 5 Environment registrations) plus **5 Supabase Edge Function runtime secrets**.

**GitHub Secrets — Repository scope** (registered via `gh secret set`):
- **iOS code signing** (4) — Distribution cert + provisioning profile + Team ID
- **App Store Connect API** (3) — `.p8` API key + Key ID + Issuer ID
- **Android signing** (4) — keystore + passwords + alias
- **Supabase runtime** (2) — backend URL + Publishable key
- **Crash + error reporting** (3) — Sentry DSNs (iOS + Android) + Organization Auth Token
- **Analytics** (1) — PostHog Project API key (free-tier consolidated; `POSTHOG_HOST` is a Repository **Variable**, not a Secret)
- **Subscriptions** (2) — RevenueCat Public iOS / Android SDK Keys

**GitHub Secrets — Environment scope** (registered via `gh secret set --env <env>`):
- **Firebase client config** (2 names × 2 environments) — `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` (Android) + `FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64` (iOS), each registered against `production` (`Skeinly` Blaze project) and `development` (`Skeinly-Dev` Spark project)
- **Android release publishing** (1 × 1 environment = 1 registration) — `GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64` registered against `production` only; consumed by `gradle-play-publisher` from CI to upload AABs to Google Play Internal track

**Supabase Edge Function Secrets** (registered via `supabase secrets set`):
- **iOS Push** (2) — APNs `.p8` + Key ID
- **Android Push** (1) — Firebase Service Account JSON for FCM HTTP v1 (`firebase-adminsdk-fbsvc@...` SA)
- **Android IAP validation** (1) — Service Account JSON for Play Developer API receipt validation (`revenuecat@...` SA)
- **Subscriptions Webhook** (1) — RevenueCat Webhook Authorization secret
- (App Store Connect API key is reused from GitHub Secrets — same `.p8` file, registered in both contexts.)

> **SA naming convention**: `GOOGLE_PLAY_<ROLE>_SA_JSON[_BASE64]` pattern bakes the role into the secret name. `PUBLISHER` = release uploads (write capability, scoped to the Skeinly app); `IAP_VALIDATOR` = IAP receipt validation (read + order management, scoped to the developer account). The two SAs hold orthogonal permission sets so a leak of either has a genuinely separated blast radius (PoLP).

The release workflow ([`.github/workflows/release.yml`](../../.github/workflows/release.yml)) reads GitHub Secrets as `${{ secrets.* }}`. Missing or incorrect values fail the release silently for some (build still succeeds, just no upload) or loudly for others (signing fails). The Supabase Edge Function reads its own secrets via `Deno.env.get(...)`. This guide includes verification steps so you can confirm a value is correct **before** registering it.

## Table of contents

- [Prerequisites](#prerequisites)
- [How to register a secret in GitHub](#how-to-register-a-secret-in-github)
- [iOS code signing (4 secrets)](#ios-code-signing-4-secrets)
- [App Store Connect API (3 secrets)](#app-store-connect-api-3-secrets)
- [Android signing (4 secrets)](#android-signing-4-secrets)
- [Supabase runtime (2 secrets)](#supabase-runtime-2-secrets)
- [Firebase client (2 secrets × 2 environments = 4 registrations)](#firebase-client-2-secrets--2-environments--4-registrations)
- [Crash + error reporting — Sentry (3 secrets)](#crash--error-reporting--sentry-3-secrets)
- [Analytics — PostHog (1 secret)](#analytics--posthog-1-secret)
- [Subscriptions — RevenueCat (2 secrets)](#subscriptions--revenuecat-2-secrets)
- [Android release publishing (1 secret × 1 environment = 1 registration)](#android-release-publishing-1-secret--1-environment--1-registration)
- [Supabase Edge Function Secrets (5 secrets)](#supabase-edge-function-secrets-5-secrets)
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

### 3. `APPLE_PROVISIONING_PROFILE_BASE64` — DEPRECATED 2026-05-11

> **DEPRECATED**: As of Path Y (Fastfile commit `0956d3f`, 2026-05-11),
> CI no longer reads this secret. The provisioning profile is fetched
> from Apple Developer Portal at runtime via `sigh` (using the App
> Store Connect API key — secrets 5–7). Capability changes propagate
> by re-Generating the profile in Portal Web UI once; the next CI run
> picks up the updated profile automatically with no GitHub Secret
> update needed.
>
> **Remove this secret** to reduce the surface of unused credentials:
>
> ```bash
> gh secret delete APPLE_PROVISIONING_PROFILE_BASE64
> ```
>
> Note: the profile itself must still exist in Apple Developer Portal
> — sigh fetches it from there. Only the GitHub Secret carrying the
> base64-encoded bytes is obsolete. The previous OBTAIN/VERIFY/REGISTER
> procedure is preserved below for historical reference only.

<details>
<summary>Historical procedure (pre-Path-Y, kept for reference)</summary>

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

</details>

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

## Firebase client (2 secrets × 2 environments = 4 registrations)

These secrets are the Firebase client-side configuration for the iOS + Android apps. They are read at build time and embedded in the binaries so the Crashlytics / FCM / Analytics / Performance / Remote Config SDKs can register with Firebase. The values are restricted in Firebase by package name + signing certificate SHA-1 (Android) or Bundle ID (iOS), so leaking them is low-blast-radius — but we still git-ignore them and route per-environment swaps through GitHub Environments.

### Firebase project layout (official guidance: separate project per environment)

Firebase officially recommends one Firebase project **per environment**:

> "Firebase recommends using a separate Firebase project for each environment in your development workflow."

Skeinly's layout:

| Project name | Plan | Purpose | Config files |
|---|---|---|---|
| **`Skeinly`** | **Blaze** + $5/month budget alert | production (release) | `google-services.json` + `GoogleService-Info.plist` (1 each) |
| **`Skeinly-Dev`** | **Spark** (free) | development (debug builds + local + CI) | `google-services.json` + `GoogleService-Info.plist` (1 each) |

A total of **4 files** are base64-encoded and registered under **GitHub Environments** (`production` / `development`) under the same secret name in each environment (no suffix needed).

### GitHub Environments setup (prerequisite)

```bash
gh api -X PUT "repos/{owner}/{repo}/environments/development" -f wait_timer=0
gh api -X PUT "repos/{owner}/{repo}/environments/production" \
  -f deployment_branch_policy[protected_branches]=true \
  -f deployment_branch_policy[custom_branch_policies]=false
# production deploys only from main, wait_timer 0
```

Or via the GitHub UI: Settings → Environments → New environment → on `production`, set the Deployment branch policy to allow only `main`.

### 14. `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` (Android)

**WHAT**: Base64-encoded `google-services.json` file downloaded from the Firebase Console for the Android app. Registered **once per Environment** (`production` / `development`) under the same secret name.

**OBTAIN (repeat per environment):**

1. Sign in to [Firebase Console](https://console.firebase.google.com).
2. Select the project (`Skeinly` or `Skeinly-Dev`; if not yet created: **Add project** → **Disable Google Analytics** (we use PostHog) → Create).
3. Inside the project: **Project Overview** → **Add app** → Android icon.
4. Android package name:
   - `Skeinly` → `io.github.b150005.skeinly`
   - `Skeinly-Dev` → `io.github.b150005.skeinly.dev` (Application ID suffix `.dev` selected via build variant)
5. App nickname: `Skeinly Android` / `Skeinly Dev Android`.
6. Signing certificate SHA-1:
   - `Skeinly` (prod): release keystore SHA-1 (`keytool -list -v -keystore upload-keystore.jks`)
   - `Skeinly-Dev`: debug keystore SHA-1 (`~/.android/debug.keystore`, default password `android`)
7. Continue → Continue → **Download `google-services.json`** → Continue → skip the SDK setup step (we wire SDKs via Gradle separately).
8. Base64-encode:

   ```bash
   base64 -i google-services.json -o google-services.base64
   ```

**VERIFY:**

```bash
cat google-services.json | python3 -m json.tool | head -10
```

Confirm:
- `project_info.project_id` matches the Firebase project (`skeinly` / `skeinly-dev`)
- `client[0].client_info.android_client_info.package_name` matches the registered package name
- `client[0].api_key[0].current_key` is a long alphanumeric string (restricted to package + SHA-1)

**REGISTER (per Environment, same secret name):**

```bash
# Register the Skeinly project's value to the production Environment
gh secret set FIREBASE_GOOGLE_SERVICES_JSON_BASE64 \
  --env production < google-services-prod.base64

# Register the Skeinly-Dev project's value to the development Environment
gh secret set FIREBASE_GOOGLE_SERVICES_JSON_BASE64 \
  --env development < google-services-dev.base64
```

The workflow only needs to declare `environment: production` / `environment: development` at the job level; the matching value resolves automatically.

**LOCAL PLACEMENT** (post-decode): the Gradle plugin (`com.google.gms.google-services`, applied in Phase 24.2e) selects the variant-specific `google-services.json` based on Android build type:

```text
androidApp/src/release/google-services.json   ← Skeinly project (Blaze, prod)
                                                package: io.github.b150005.skeinly
androidApp/src/debug/google-services.json     ← Skeinly-Dev project (Spark)
                                                package: io.github.b150005.skeinly.dev
                                                (the .dev suffix is added by
                                                applicationIdSuffix in
                                                androidApp/build.gradle.kts
                                                debug buildType)
```

Decode commands:

```bash
# Production (release builds)
gh secret list --env production    # confirm FIREBASE_GOOGLE_SERVICES_JSON_BASE64 is registered
# Then on a machine with the secret available (your laptop after gh auth):
gh secret get FIREBASE_GOOGLE_SERVICES_JSON_BASE64 --env production \
  | base64 -d > androidApp/src/release/google-services.json

# Development (debug builds — local dev + Maestro E2E + ci.yml debug-build job)
gh secret get FIREBASE_GOOGLE_SERVICES_JSON_BASE64 --env development \
  | base64 -d > androidApp/src/debug/google-services.json
```

> **Why dev/prod split**: separates dev push deliveries from prod analytics, isolates different SHA-1 signatures (debug keystore vs release keystore), and lets debug+release builds coexist on a single Android device thanks to the `.dev` `applicationIdSuffix`. The `Skeinly-Dev` Firebase project (Spark plan) is intentionally **not** registered as a separate Play Console app — `gradle-play-publisher` only publishes the release variant; dev builds reach devices via `adb install` (local) and emulator install (CI Maestro), never via the Play Internal track.

> **CI auto-decode** (Phase 24.2e): `ci.yml` debug-build job + `e2e.yml` will decode `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` from the `development` environment into `androidApp/src/debug/google-services.json`. `release.yml` will decode the same secret name from the `production` environment into `androidApp/src/release/google-services.json`. Both files are git-ignored (see project root `.gitignore` Firebase block).

**ROTATE**: Re-download from the Firebase Console only if you change the package name or signing SHA-1. Day-to-day no rotation is needed.

### 14b. `FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64` (iOS)

**WHAT**: Base64-encoded `GoogleService-Info.plist` file downloaded from the Firebase Console for the iOS app. Registered **once per Environment** (`production` / `development`) under the same secret name.

**OBTAIN (repeat per environment):**

1. Firebase Console → the project → **Project Overview** → **Add app** → iOS icon.
2. Bundle ID:
   - `Skeinly` → `io.github.b150005.skeinly`
   - `Skeinly-Dev` → `io.github.b150005.skeinly.dev` (selected via Xcode Debug Configuration)
3. App nickname: `Skeinly iOS` / `Skeinly Dev iOS`.
4. App Store ID: leave blank (add later once the app is registered on App Store Connect).
5. Register App → **Download `GoogleService-Info.plist`** → Continue → skip SDK setup.
6. Base64-encode:

   ```bash
   base64 -i GoogleService-Info.plist -o google-service-info.base64
   ```

**VERIFY:**

```bash
plutil -p GoogleService-Info.plist | head -10
# BUNDLE_ID, PROJECT_ID, GOOGLE_APP_ID etc. should appear
```

Confirm:
- `BUNDLE_ID` matches the registered Bundle ID
- `PROJECT_ID` matches `skeinly` / `skeinly-dev`
- `GOOGLE_APP_ID` is in `1:<sender-id>:ios:<hash>` form

**REGISTER (per Environment, same secret name):**

```bash
gh secret set FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64 \
  --env production < google-service-info-prod.base64

gh secret set FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64 \
  --env development < google-service-info-dev.base64
```

The iOS release workflow decodes this at build time into `iosApp/iosApp/GoogleService-Info.plist` (git-ignored).

**ROTATE**: Re-download from the Firebase Console only on Bundle ID change.

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

**WHAT**: An **Organization Auth Token** that lets CI upload dSYMs (iOS) and mapping files (Android) to Sentry after each release build, so stack traces are symbolicated automatically. **Not a User Auth Token** — User Tokens are tied to a specific user account and silently expire if that user is removed from the organization, which is a real operational risk for CI. Sentry's documentation explicitly recommends Organization Auth Tokens for CI:

> "To upload source maps you have to configure an Organization Token."
>
> "Organization tokens are designed to be used in CI environments and with Sentry CLI."

**OBTAIN:**

1. Sentry → bottom of left sidebar: **Settings** → **Organization Settings** → **Auth Tokens**.
   - ⚠️ **Do NOT use User Settings → Auth Tokens.**
2. **Create New Organization Token**.
3. Name: `skeinly CI`.
4. Scopes: Organization Auth Tokens **automatically include** the scopes CI needs (`project:releases`, `project:write`, `org:read`) — no manual scope selection like the User Token flow.
5. Create → copy the token immediately (one-time view).

**Token type comparison:**

| Type | CI suitability | Recommendation | Expiry risk |
|---|---|---|---|
| **Organization Auth Token** | ✅ | **Recommended** | Valid as long as the org exists |
| User Auth Token (formerly Personal Token) | ⚠️ | Not recommended | Expires immediately when the creating user leaves the org |
| Internal Integration token | △ | Advanced integrations only | n/a |

**Relationship to per-environment Sentry projects**: Even with the two-project layout (`skeinly-ios` + `skeinly-android`), `SENTRY_AUTH_TOKEN` is a **single secret** — Org Tokens act across every project in the org. `SENTRY_PROJECT` / `SENTRY_ORG` are switched per CI Job via `env:`.

**VERIFY:**

```bash
# Token shape: sntrys_<base64>... (Org Token)
brew install getsentry/tools/sentry-cli  # if not installed
sentry-cli --auth-token <token> info
# Should show the org name + project list.
```

**REGISTER:**

```bash
gh secret set SENTRY_AUTH_TOKEN
# paste token, Ctrl+D
```

**ROTATE**: Organization Settings → Auth Tokens → revoke + recreate. Update the GitHub Secret.

Reference: [Sentry Auth Tokens](https://docs.sentry.io/account/auth-tokens/)

## Analytics — PostHog (1 secret)

This secret wires the PostHog SDK on iOS + Android. **PostHog's free tier caps at 1 Project**, so splitting prod / dev into separate Projects is only possible after upgrading to the paid PAYG plan (which permits up to 6 Projects). The current free-tier setup:

- **One Project (`Skeinly`)** receives connections from both iOS + Android.
- Platform identification uses the SDK's automatic `$os` super property (a custom `platform` super property may be added if needed).
- **Skip `posthog.init` in DEBUG builds** so dev events don't pollute the prod dataset.
- `auto_capture: false` plus an opt-in toggle (Settings → "Allow usage data collection", default OFF) gate event ingestion per the Phase 27a privacy policy.

PostHog's official documentation also explicitly **discourages** splitting projects per platform:

> "PostHog strongly recommends keeping your apps and marketing website on the same production project"

### 18. `POSTHOG_PROJECT_API_KEY`

**WHAT**: The Project API Key for the PostHog project. Embedded in release builds. Format: `phc_<43-char-base62>`.

**Important fact**: PostHog Project API Keys are designed as **"write-only keys"** intended to be embedded in client binaries shipped via SDKs. The official docs:

> "Safer than other API key types for client-side use"

So they are not "secrets" in the traditional sense — leaking one has a small blast radius. We still register it as a GitHub Secret for **operational hygiene** (easy rotation, grep-ability, prevention of accidental commits), not for confidentiality.

**OBTAIN:**

1. Sign in to [PostHog](https://us.posthog.com) or [EU cloud](https://eu.posthog.com) (EU for GDPR data residency).
2. If no organization exists: create one named `skeinly`.
3. **Settings** → **Projects** → **Create Project** → Name: `Skeinly`.
4. Inside the project: **Settings** → **Project** → **General** → **Project API Key** → copy the value (starts with `phc_`).

**VERIFY**: Starts with `phc_`, exactly 47 chars total (`phc_` + 43-char base62), case-sensitive.

**REGISTER:**

```bash
gh secret set POSTHOG_PROJECT_API_KEY
# paste, Ctrl+D
```

**(Future) per-env split after PAYG**: If you later upgrade to PAYG and want to separate dev / prod, choose one of:
- **(A) Recommended**: Create a `Skeinly-Dev` PostHog Project, then leverage GitHub Repository **Environments** (`development` / `production`) and register `POSTHOG_PROJECT_API_KEY` under each Environment with the matching project's value (no name suffix needed).
- (B) Use PostHog's Environments feature (sub-scopes inside a single Project).

**ROTATE**: PostHog → Settings → Project → reset the Project API Key. Update the GitHub Secret. Old events stay attributed to the project; only new ingestion shifts.

### `POSTHOG_HOST` (Repository Variable, NOT a secret)

**WHAT**: The PostHog backend host URL, e.g. `https://us.i.posthog.com` (US cloud) or `https://eu.i.posthog.com` (EU cloud, GDPR-preferred). This is a **non-confidential public URL**, so it is registered as a Repository **Variable** (not a Secret) and read from workflows as `vars.POSTHOG_HOST`.

**REGISTER (GitHub UI recommended)**: Repo → Settings → Secrets and variables → Actions → **Variables** tab → New repository variable → Name: `POSTHOG_HOST` / Value: `https://us.i.posthog.com` (or your preferred region URL).

**Or via `gh`:**

```bash
gh variable set POSTHOG_HOST --body "https://us.i.posthog.com"
gh variable list  # confirm a POSTHOG_HOST row appears
```

**Note**: If unset, `vars.POSTHOG_HOST` resolves to an empty string and the PostHog SDK initialization may fail with `init failed: invalid host`. Always register this variable to prevent CI flakes.

References: [PostHog: Multi-environment tutorial](https://posthog.com/tutorials/multiple-environments) / [PostHog Pricing](https://posthog.com/pricing)

## Subscriptions — RevenueCat (2 secrets)

These secrets wire the RevenueCat SDK on iOS + Android. **Public SDK Keys** are designed to be embedded in client binaries (passed to `Purchases.configure()`). The `Secret Key (sk_...)` is **server-only and must NEVER be embedded in a client**. RevenueCat issues **separate Public SDK Keys for iOS / Android per Project** (we run a single Project named `Skeinly` that hosts both iOS + Android App Configurations).

### 19. `REVENUECAT_API_KEY_IOS`

**WHAT**: The Public iOS SDK Key. Embedded in the iOS release binary.

**OBTAIN:**

1. Sign in to the [RevenueCat Dashboard](https://app.revenuecat.com) → select Project (`Skeinly`).
2. **Project Settings** → **Apps** → click the **iOS** App entry (Bundle ID `io.github.b150005.skeinly`).
3. **API Keys** tab → copy the **Public iOS SDK Key** (starts with `appl_`).

**VERIFY**: Starts with `appl_`. Confirm it is NOT a Secret Key (`sk_...`).

**REGISTER:**

```bash
gh secret set REVENUECAT_API_KEY_IOS
# paste appl_..., Ctrl+D
```

**ROTATE**: RevenueCat → Project Settings → Apps → iOS → API Keys → revoke + issue new. The new key takes effect from the next release after the GitHub Secret update.

### 20. `REVENUECAT_API_KEY_ANDROID`

**WHAT**: The Public Android SDK Key. Embedded in the Android release AAB.

**OBTAIN**: Same procedure as #19 but select the **Android** App entry and copy the Public Android SDK Key (starts with `goog_`).

**VERIFY**: Starts with `goog_`.

**REGISTER:**

```bash
gh secret set REVENUECAT_API_KEY_ANDROID
# paste goog_..., Ctrl+D
```

**About the Webhook secret**: RevenueCat's Webhook signature-verification secret is **Edge Function only** (not needed by the client). See [EF-5 `REVENUECAT_WEBHOOK_SECRET`](#ef-5-revenuecat_webhook_secret) at the end of this document.

**RevenueCat ↔ Apple/Google IAP wiring** (registering App Store Connect API Key + Google Play Service Account in RevenueCat, which is a prerequisite for Public SDK Key issuance) is documented in [vendor-setup.md](vendor-setup.md) under the RevenueCat section.

Reference: [RevenueCat API Keys & Authentication](https://www.revenuecat.com/docs/projects/authentication)

## Android release publishing (1 secret × 1 environment = 1 registration)

Service Account JSON used by `gradle-play-publisher` from CI to upload AABs to the Google Play Internal track. **Registered against the `production` Environment only** (debug builds never publish to Internal track, so `development` does not need it).

### 21. `GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64`

**WHAT**: Base64-encoded Service Account JSON with write access to Google Play Developer API release tracks. Consumed by `gradle-play-publisher` plugin (or fastlane `supply` equivalent) at AAB upload time.

**SA**: `google-play-publisher@<project-ref>.iam.gserviceaccount.com` (separate from the IAP-validation SA `revenuecat@...` — orthogonal permission sets, PoLP-aligned).

**OBTAIN:**

1. [Google Cloud Console](https://console.cloud.google.com) → IAM & Admin → **Service Accounts**.
2. Confirm `google-play-publisher@<project-ref>.iam.gserviceaccount.com` exists (created in the `Skeinly` project per the vendor-setup `A0c-3 RevenueCat` prerequisites).
3. Click the SA → **Keys** tab → **Add Key** → **Create new key** → JSON → Download.
4. Base64-encode locally:

   ```bash
   base64 -i ~/path/to/google-play-publisher-xxxx.json | pbcopy
   ```

**Play Console permission grant** (one-time):

5. [Play Console](https://play.google.com/console) → **Users and permissions** → **Invite new users**.
6. Email: `google-play-publisher@<project-ref>.iam.gserviceaccount.com`.
7. Access expiry: none.
8. **App permissions** tab → select Skeinly app → check **only**:
   - **Release to testing tracks**
   - **Manage testing tracks and edit tester lists**
   - (auto: View app information read-only, View app quality information read-only)
9. **Account permissions** tab: leave everything unchecked.
10. Send invitation.

> **Permissions to NEVER check**: Release apps to production, View financial data, Manage orders, Manage store presence, Reply to reviews, Policy, Deep links, Admin (all permissions). The CI pipeline must be structurally incapable of pushing to the production track — restrict release permissions to "testing tracks" only.
>
> **App-level vs account-level**: `google-play-publisher` should be **app-level** (Skeinly only). The CI publisher will only ever upload Skeinly AABs, so app-level scoping eliminates the risk of accidentally affecting other apps if any are added later.

**VERIFY**: Play Console **Users and permissions** lists `google-play-publisher@...` with green checkmarks on the 2 granted permissions, scoped to the Skeinly app.

**REGISTER:**

```bash
gh secret set GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64 \
  --env production \
  --body "$(base64 -i ~/path/to/google-play-publisher-xxxx.json)"
```

> Note `--env production` — this is an **Environment-scoped secret**. Do not register it at Repository scope or in the `development` Environment.

**CONSUMED BY**: the `build-android` job in `.github/workflows/release.yml` (runs on tag push only). The job decodes `${{ secrets.GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64 }}` and exports it via the `ANDROID_PUBLISHER_CREDENTIALS` env var (per gradle-play-publisher 4.x README), then `./gradlew :androidApp:publishBundle` uploads the AAB. The plugin's `play { }` block in `androidApp/build.gradle.kts` pins `track = "internal"` + `releaseStatus = DRAFT` — the AAB lands in Play Console as a Draft release on the Internal track, and the user manually clicks "Send to testers" to start distribution. Pre-tag local verification: `make release-aab-local` builds the AAB without uploading.

**ROTATE**: Cloud Console → IAM → Service Accounts → `google-play-publisher@...` → Keys → revoke old + create new JSON → re-run the REGISTER step above. Play Console permissions persist across key rotations because they are scoped to the SA email, which is unchanged.

## Supabase Edge Function Secrets (5 secrets)

These secrets are consumed by Supabase Edge Functions (`notify-on-write` for collaboration push — Phase 24.1 (this slice) wires the shell; Phase 24.3 wires the actual APNs / FCM sends; `revenuecat-webhook` for IAP webhook ingestion — Phase 39 prep, 2026-05-08). They are **not** GitHub Secrets — they are registered against the Supabase project via the Supabase CLI:

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

### EF-4. `GOOGLE_PLAY_IAP_VALIDATOR_SA_JSON` (DEPRECATED 2026-05-09)

> **DEPRECATED 2026-05-09**: was registered for the now-deleted `verify-receipt` Edge Function (agent-team deliberation found no complementary role given RevenueCat's coverage of Apple/Google receipt validation; `revenuecat-webhook` Edge Function delegates to RevenueCat which validates server-side). The underlying SA `revenuecat@<project-ref>.iam.gserviceaccount.com` continues to be uploaded to RevenueCat dashboard for receipt validation — that registration is unaffected. The Supabase secret is no longer consumed by any Edge Function; safe to `supabase secrets unset GOOGLE_PLAY_IAP_VALIDATOR_SA_JSON` to reduce credential surface. Section retained for rotation reference and in case Phase H' (or equivalent) needs an independent server-side validator post-beta.

**WHAT**: Service Account JSON for the Google Play Developer API, intended for Android IAP receipts server-side validation and subscription state polling. Originally consumed by `verifyGoogleReceipt` in `supabase/functions/verify-receipt/index.ts` (deleted 2026-05-09).

**SA**: `revenuecat@<project-ref>.iam.gserviceaccount.com` (same SA as the one uploaded to RevenueCat dashboard — single source of truth).

> **2026-05-08 migration**: renamed from `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` and re-pointed from the `google-play-publisher@...` SA to `revenuecat@...` (PoLP separation). The `google-play-publisher@...` SA is now exclusively used for CI release publishing (see [#21 `GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64`](#21-google_play_publisher_sa_json_base64)). Migration steps at the end of this section.

**OBTAIN:**

The `revenuecat@...` SA is expected to exist in Cloud Console with a JSON key already issued (created during vendor-setup A0c-3 RevenueCat wiring). Re-issue the key only if needed:

1. [Google Cloud Console](https://console.cloud.google.com) → IAM & Admin → **Service Accounts** → click `revenuecat@...`.
2. **Keys** tab → **Add Key** → **Create new key** → JSON → Download.
3. (Optional) **Disable** + **Delete** the old key.

**VERIFY**: JSON has `"type": "service_account"`, `client_email` is `revenuecat@<project-ref>.iam.gserviceaccount.com`. The Play Console **Users and permissions** page lists the same email with these permissions checked:
- View revenue data, orders, churn survey responses (**View financial data**)
- Manage orders and subscriptions (**Manage orders**)
- View app information / bulk reports download — read-only (auto)

> **Permission scope**: app-level or account-level both work. While Skeinly is the only app, the functional difference is zero. If the developer account ever manages multiple apps, scoping to Skeinly only would be tighter PoLP.

**REGISTER:**

```bash
# Register under the new secret name
supabase secrets set GOOGLE_PLAY_IAP_VALIDATOR_SA_JSON="$(cat ~/path/to/revenuecat-sa.json)"
supabase secrets list | grep GOOGLE_PLAY_IAP_VALIDATOR
```

**Migration steps** (if the legacy `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` secret is registered with the `google-play-publisher@...` JSON):

```bash
# 1. Delete the old secret
supabase secrets unset GOOGLE_PLAY_SERVICE_ACCOUNT_JSON

# 2. Register under the new name with the new SA
supabase secrets set GOOGLE_PLAY_IAP_VALIDATOR_SA_JSON="$(cat ~/path/to/revenuecat-sa.json)"

# 3. Confirm
supabase secrets list | grep GOOGLE_PLAY
# Only GOOGLE_PLAY_IAP_VALIDATOR_SA_JSON should appear
```

> Edge Function `verify-receipt` was deleted on 2026-05-09. The migration steps above remain useful only if the secret was already registered under the legacy name — `unset` of either name is now equally valid.

**ROTATE**: Cloud Console → IAM → Service Accounts → `revenuecat@...` → Keys → revoke old + create new JSON. **Re-register the secret in 3 places**:
1. Supabase Edge Function: `GOOGLE_PLAY_IAP_VALIDATOR_SA_JSON`.
2. RevenueCat dashboard: Project Settings → Apps → Skeinly → re-upload Service Account Credentials.
3. (If applicable) any other consumer of the same JSON.

### EF-5. `REVENUECAT_WEBHOOK_SECRET`

**WHAT**: A shared secret used **server-side** to verify Webhook deliveries from RevenueCat to the `revenuecat-webhook` Edge Function. The value entered into RevenueCat's **Webhooks → Authorization header** field is matched on the Edge Function side against the incoming `Authorization: Bearer <value>` header (constant-time comparison).

**Consumed by**: [`supabase/functions/revenuecat-webhook/index.ts`](../../supabase/functions/revenuecat-webhook/index.ts) — activated at Phase 39 closed beta launch.

**OBTAIN + Webhook URL setup (single end-to-end flow):**

1. Generate a strong random value (**you choose this** — RevenueCat just stores and forwards whatever you give it):

   ```bash
   openssl rand -hex 32
   # e.g. 7f3a9c2d8e1b5a4f6c9d2e7b8a1f3c5d9e2b4a6c8f1d3e5a7b9c2d4e6f8a1b3c
   ```

2. **Register the secret in Supabase Edge Function secrets BEFORE configuring the dashboard webhook URL** (so any test event fired during deploy reaches a function that can already 401-reject mismatches):

   ```bash
   supabase secrets set REVENUECAT_WEBHOOK_SECRET="<value-from-step-1>"
   supabase secrets list | grep REVENUECAT
   ```

3. **Deploy the Edge Function**:

   ```bash
   supabase functions deploy revenuecat-webhook
   ```

   > **JWT verification disabled (load-bearing)**: `supabase/config.toml`
   > carries `[functions.revenuecat-webhook] verify_jwt = false` so the
   > `supabase functions deploy` invocation picks it up automatically.
   > If an older CLI ignores the flag, pass it explicitly:
   >
   > ```bash
   > supabase functions deploy revenuecat-webhook --no-verify-jwt
   > ```
   >
   > Without this, Supabase's API gateway 401-rejects every webhook
   > request before our handler runs (`UNAUTHORIZED_INVALID_JWT_FORMAT`),
   > because RevenueCat does not (and cannot) carry a Supabase JWT.

4. Note the deploy URL (e.g. `https://<project-ref>.supabase.co/functions/v1/revenuecat-webhook`). Visible in Supabase Dashboard → Edge Functions → revenuecat-webhook → Details.

5. [RevenueCat Dashboard](https://app.revenuecat.com) → Project (`Skeinly`) → **Integrations** → **Webhooks** → **Add Webhook**:
   - **Webhook URL**: paste the URL from step 4
   - **Authorization header value**: paste **`Bearer <value-from-step-1>`**
     (the literal word `Bearer`, a single space, then the hex secret).
     RevenueCat sends this value verbatim as the `Authorization` HTTP
     header; our handler strips the `Bearer ` prefix and compares the
     remainder against the Supabase secret. **Pasting just the hex value
     without the `Bearer ` prefix produces a 401** at our handler
     (handler requires `startsWith("Bearer ")`).
   - **Environment filter**: **leave empty** (sandbox + production both flow through; closed beta is sandbox-dominated, post-Phase 40 GA also takes production. The function writes `event.environment` to the `subscriptions.environment` column so downstream analytics filter via `WHERE environment = 'production'`.)
6. **Save**.

7. In the same row, click **"Send test event"**. A green checkmark (HTTP 200 + body `{"status":"ok","note":"test_event_acknowledged"}`) confirms end-to-end success.

**VERIFY**: 64-char hex string. Confirm the value on the RevenueCat side matches the Supabase secret exactly. `Send test event` returns a green checkmark.

**curl smoke tests** (alternative to dashboard button after step 6):

```bash
WEBHOOK_URL="https://<project-ref>.supabase.co/functions/v1/revenuecat-webhook"
SECRET="<value-from-step-1>"

curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "Authorization: Bearer $SECRET" \
  -H "Content-Type: application/json" \
  -d '{"api_version":"1.0","event":{"id":"smoke-test-1","type":"TEST","event_timestamp_ms":1700000000000}}'
```

Expected: `HTTP 200` + body `{"status":"ok","note":"test_event_acknowledged"}`.

Verify 401 on bad auth:

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "Authorization: Bearer wrong-secret" \
  -H "Content-Type: application/json" \
  -d '{"event":{"id":"x","type":"TEST","event_timestamp_ms":1700000000000}}'
```

Expected: `HTTP 401` + body `{"error":"unauthorized"}`.

**ROTATE**: Edit the webhook in the RevenueCat dashboard → update the Authorization header to a new value → re-register the Supabase secret. RevenueCat retries failed webhook deliveries up to 5 times automatically, so the rotation window has built-in tolerance for missed events.

> **Dependency chain**: this secret is one of three pieces that together make webhook-driven subscription state work: (a) the deployed Edge Function itself, (b) [migration 023 `upsert_subscription_from_webhook` RPC](../../supabase/migrations/023_revenuecat_webhook_helper.sql), (c) the KMP-side [`RevenueCatAuthBridge.kt`](../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/subscription/RevenueCatAuthBridge.kt) which calls `Purchases.logIn(userId)` so webhook events carry the Skeinly UUID as `app_user_id`. Full Phase 39 closed beta sandbox tester setup walkthrough: [docs/en/ops/beta-testing.md](ops/beta-testing.md).

Reference: [RevenueCat Webhooks](https://www.revenuecat.com/docs/integrations/webhooks)

### EF-6. `SKEINLY_DATABASE_WEBHOOK_SECRET`

**WHAT**: Shared secret used to authenticate Supabase Database Webhook deliveries to the `notify-on-write` Edge Function (Phase 24.1, ADR-017). Generated by the maintainer (`openssl rand -hex 32`) and registered both as a Supabase Edge Function secret AND as the value of the `Authorization: Bearer <secret>` HTTP header on each of the 3 Database Webhooks via Dashboard. The Edge Function verifies every delivery via constant-time string compare.

> **Authentication shape**: per the [Supabase Database Webhooks doc](https://supabase.com/docs/guides/database/webhooks), Database Webhooks do NOT auto-sign payloads — the Dashboard UI only exposes Method / URL / Timeout / HTTP Headers / HTTP Parameters, with no signing-secret field and no `x-supabase-webhook-signature` header. The Authorization Bearer header is the supported authentication boundary. Mirrors the `revenuecat-webhook` pattern (EF-5).

> **Naming note**: the `SKEINLY_` prefix is load-bearing. Per the [Supabase Edge Function limits doc](https://supabase.com/docs/guides/functions/limits#secrets), env-var names starting with `SUPABASE_` are reserved by the platform and `supabase secrets set` rejects them. Phase 24.1 originally proposed `SUPABASE_DATABASE_WEBHOOK_SECRET`; renamed to `SKEINLY_DATABASE_WEBHOOK_SECRET` before deployment to avoid the registration error.

**CONSUMED BY**: [`supabase/functions/notify-on-write/index.ts`](../../supabase/functions/notify-on-write/index.ts) — verifies the `Authorization: Bearer <value>` header on every webhook delivery. Three Database Webhooks fire this function (see [`docs/en/ops/webhooks.md`](ops/webhooks.md)).

**OBTAIN**: generated locally with `openssl rand -hex 32` (any cryptographically random 32-byte hex string works; Supabase does not impose a length minimum but 256 bits aligns with established secret-rotation discipline across the project's webhook secrets).

**REGISTER (Supabase Edge Function side)**:

```bash
SKEINLY_DATABASE_WEBHOOK_SECRET="$(openssl rand -hex 32)"
echo "$SKEINLY_DATABASE_WEBHOOK_SECRET"   # save this value — needed for Dashboard config below
supabase secrets set SKEINLY_DATABASE_WEBHOOK_SECRET="$SKEINLY_DATABASE_WEBHOOK_SECRET"
supabase secrets list | grep SKEINLY_DATABASE_WEBHOOK_SECRET
```

**REGISTER (Database Webhook side)**: open Dashboard → Database → Webhooks → for each of the 3 webhooks, select `Supabase Edge Functions` as the type (NOT `HTTP Request`) → pick `notify-on-write` from the function dropdown → in the HTTP Headers section **overwrite the auto-populated `Authorization` header value** (which is the project anon key — public, embedded in the mobile app) with `Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET value>`. The auto-populated anon key would let any app user spoof webhook deliveries directly to the Edge Function URL. Full step-by-step walkthrough including the rationale: [`docs/en/ops/webhooks.md`](ops/webhooks.md).

**ROTATE**: generate a new value with `openssl rand -hex 32`, re-register the Supabase secret, then update the `Authorization` HTTP header on each of the 3 Database Webhooks via Dashboard. The webhook system does not auto-retry on auth failure (the Edge Function returns 401 and Supabase records the failure but does NOT re-deliver), so plan rotation during a quiet window or briefly tolerate missed pushes.

> **Dependency chain**: this secret is one of three pieces that together make collaboration push work: (a) the deployed Edge Function itself, (b) the 3 Database Webhooks configured per [`docs/en/ops/webhooks.md`](ops/webhooks.md), (c) `device_tokens` table populated by Phase 24.2's `PushTokenRegistrar`. Phase 24.1 wires (a) + (b); Phase 24.2 adds the client side; Phase 24.3 enables real APNs / FCM sends; Phase 24.4–24.6 expand the event matrix + add deep link routing + privacy policy update.

### EF-7. `SKEINLY_BUGREPORT_APP_ID` / `SKEINLY_BUGREPORT_INSTALLATION_ID` / `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM`

**WHAT**: GitHub App credential trio used by the `submit-bug-report` Edge Function (Phase 39 W5, ADR-020) to authenticate as the "Skeinly Feedback" GitHub App and create Issues on `b150005/skeinly` on the tester's behalf. Replaces Phase 39.5's client-side URL prefill flow.

- `SKEINLY_BUGREPORT_APP_ID` — numeric App ID, shown at the top of the App's settings page after creation.
- `SKEINLY_BUGREPORT_INSTALLATION_ID` — numeric Installation ID, appears in the post-install URL `github.com/settings/installations/<id>`.
- `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM` — full PEM body of the `.pem` downloaded from the App's "Private keys" section. Includes `-----BEGIN ... PRIVATE KEY-----` / `-----END ... PRIVATE KEY-----` lines verbatim. Both PKCS#1 (`BEGIN RSA PRIVATE KEY`) and PKCS#8 (`BEGIN PRIVATE KEY`) are accepted by the Edge Function.

> **Naming note**: the `SKEINLY_` prefix is load-bearing (same rationale as EF-6 / EF-3).

**CONSUMED BY**: [`supabase/functions/submit-bug-report/index.ts`](../../supabase/functions/submit-bug-report/index.ts) (reads env vars at request time) + [`supabase/functions/submit-bug-report/github_app.ts`](../../supabase/functions/submit-bug-report/github_app.ts) (RS256 JWT signing + installation token exchange).

**OBTAIN**:

1. Open https://github.com/settings/apps/new
2. **GitHub App name**: `Skeinly Feedback`
3. **Homepage URL**: `https://b150005.github.io/skeinly/`
4. **Webhook → Active**: uncheck (no webhooks needed)
5. **Repository permissions → Issues**: Read & write. All other permissions: No access.
6. **Where can this GitHub App be installed?**: Only on this account
7. Click **Create GitHub App**. Note the **App ID** displayed at the top of the reloaded settings page.
8. Scroll to **Private keys** → **Generate a private key**. A `.pem` file downloads.
9. Open the App's **Install App** page from the left sidebar, click **Install** next to your account name, select **Only select repositories** → `b150005/skeinly`. Confirm.
10. The post-install browser URL is `github.com/settings/installations/<INSTALLATION_ID>`. Note the **Installation ID** number.

**REGISTER**:

```bash
supabase secrets set SKEINLY_BUGREPORT_APP_ID=<app-id-from-step-7>
supabase secrets set SKEINLY_BUGREPORT_INSTALLATION_ID=<install-id-from-step-10>
supabase secrets set SKEINLY_BUGREPORT_PRIVATE_KEY_PEM="$(cat /path/to/downloaded.pem)"
supabase secrets list | grep SKEINLY_BUGREPORT
```

**DEPLOY**:

```bash
git checkout main && git pull
supabase functions deploy submit-bug-report
```

**SMOKE TEST** (after deploy):

```bash
ANON=<supabase project anon key>
curl -i \
  -X POST "https://<project>.supabase.co/functions/v1/submit-bug-report" \
  -H "apikey: ${ANON}" \
  -H "Content-Type: application/json" \
  -d '{"title":"[Beta] smoke test","body":"This is a smoke test."}'
```

Expect HTTP 200 with `{"ok":true,"issue_number":<n>,"html_url":"..."}`. Visit the returned URL to verify, then close the Issue manually.

**ROTATE**: App settings → Generate new private key → download `.pem` → `supabase secrets set SKEINLY_BUGREPORT_PRIVATE_KEY_PEM="$(cat new.pem)"` → `supabase functions deploy submit-bug-report` → App settings → revoke the old key. App ID and Installation ID do not change.

> **Dependency chain**: Phase 39 W5a wires the Edge Function (this entry). Phase 39.5's client-side URL prefill remains the active submission path until Phase 39 W5b deletes `BugSubmissionLauncher` (expect/actual) and routes via a new `BugReportProxyClient`. Until W5b lands, this Edge Function is deployed but unused by client builds — keep it deployed so the W5b commit can flip the client-side switch without coordinated deploy timing.

Reference: [GitHub Apps documentation](https://docs.github.com/en/apps/creating-github-apps)

### Reused: App Store Connect API key (DEPRECATED 2026-05-09 for Edge Functions)

> **DEPRECATED 2026-05-09 for Edge Function consumption**: was registered for the now-deleted `verify-receipt` Edge Function (iOS branch, JWS signing for App Store Server API calls). RevenueCat handles iOS receipt validation server-side, so the Supabase-side registration of these three secrets is no longer consumed by any Edge Function. Safe to:
>
> ```bash
> supabase secrets unset APP_STORE_CONNECT_API_KEY
> supabase secrets unset APP_STORE_CONNECT_KEY_ID
> supabase secrets unset APP_STORE_CONNECT_ISSUER_ID
> ```
>
> The same App Store Connect API key remains REQUIRED as GitHub Secrets §5–§7 for the iOS release pipeline (TestFlight upload via `xcrun altool`).

Historical: when `verify-receipt` was the IAP validation path, this Supabase Edge Function registration was required as a single source of truth alongside the GitHub Secret.

## Bulk verification

After registering all GitHub Secrets, confirm with `gh`:

```bash
# Repository scope
gh secret list

# Environment scope
gh secret list --env production
gh secret list --env development
```

Expected **Repository scope** output (18 entries — values shared across all environments;
`APPLE_PROVISIONING_PROFILE_BASE64` was 19th before Path Y deprecation 2026-05-11):

```
APPLE_DISTRIBUTION_CERT_BASE64        Updated YYYY-MM-DD
APPLE_DISTRIBUTION_CERT_PASSWORD      Updated YYYY-MM-DD
APPLE_TEAM_ID                         Updated YYYY-MM-DD
APP_STORE_CONNECT_API_KEY_BASE64      Updated YYYY-MM-DD
APP_STORE_CONNECT_API_KEY_ID          Updated YYYY-MM-DD
APP_STORE_CONNECT_ISSUER_ID           Updated YYYY-MM-DD
KEY_ALIAS                             Updated YYYY-MM-DD
KEY_PASSWORD                          Updated YYYY-MM-DD
KEYSTORE_BASE64                       Updated YYYY-MM-DD
KEYSTORE_PASSWORD                     Updated YYYY-MM-DD
POSTHOG_PROJECT_API_KEY               Updated YYYY-MM-DD
REVENUECAT_API_KEY_ANDROID            Updated YYYY-MM-DD
REVENUECAT_API_KEY_IOS                Updated YYYY-MM-DD
SENTRY_AUTH_TOKEN                     Updated YYYY-MM-DD
SENTRY_DSN_ANDROID                    Updated YYYY-MM-DD
SENTRY_DSN_IOS                        Updated YYYY-MM-DD
SUPABASE_PUBLISHABLE_KEY              Updated YYYY-MM-DD
SUPABASE_URL                          Updated YYYY-MM-DD
```

> If `APPLE_PROVISIONING_PROFILE_BASE64` still appears in `gh secret list`,
> it is safe to delete — Path Y migrated CI to fetch the profile from
> Apple Developer Portal at runtime. See §3 above.

Expected **`production` Environment scope** output (3 entries — Firebase prod ×2 + Android release publishing ×1):

```
FIREBASE_GOOGLE_SERVICES_JSON_BASE64        Updated YYYY-MM-DD
FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64   Updated YYYY-MM-DD
GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64        Updated YYYY-MM-DD
```

Expected **`development` Environment scope** output (2 entries — `Skeinly-Dev` Firebase project values):

```
FIREBASE_GOOGLE_SERVICES_JSON_BASE64        Updated YYYY-MM-DD
FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64   Updated YYYY-MM-DD
```

Total: 19 Repository + Environment scope (production: 3 + development: 2) = **24 registrations**. Anything missing or with a stale timestamp is suspect.

> **Legacy name cleanup**: When migrating from a prior layout, delete the following with `gh secret delete`:
> - `SUPABASE_ANON_KEY` (→ fully migrated to `SUPABASE_PUBLISHABLE_KEY`)
> - `POSTHOG_PROJECT_API_KEY_PROD` / `POSTHOG_PROJECT_API_KEY_DEV` (→ consolidated into `POSTHOG_PROJECT_API_KEY`)
> - The old Repository-scope `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` (→ moved to Environment scope)
>
> Edge Function legacy name cleanup:
> - `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (→ renamed to `GOOGLE_PLAY_IAP_VALIDATOR_SA_JSON` and re-pointed at the `revenuecat@...` SA, 2026-05-08). Delete with `supabase secrets unset GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`.

For Supabase Edge Function secrets (registered via `supabase secrets set`):

```bash
supabase secrets list
```

Expected (9 entries after Phase 39 W5a `SKEINLY_BUGREPORT_*` registration; was 6 after Phase 24.1; was 5 after 2026-05-09 `verify-receipt` deletion):

```
APPLE_APNS_KEY_ID                           # for notify-on-write (Phase 24.3 wires the actual send)
APPLE_APNS_KEY_P8                           # for notify-on-write (Phase 24.3 wires the actual send)
APPLE_TEAM_ID                               # for notify-on-write (Phase 24.3 wires the actual send)
FIREBASE_SERVICE_ACCOUNT_JSON               # for notify-on-write (Phase 24.3 wires the actual send)
REVENUECAT_WEBHOOK_SECRET                   # for revenuecat-webhook (Phase 39 prep)
SKEINLY_BUGREPORT_APP_ID                    # for submit-bug-report (Phase 39 W5a)
SKEINLY_BUGREPORT_INSTALLATION_ID           # for submit-bug-report (Phase 39 W5a)
SKEINLY_BUGREPORT_PRIVATE_KEY_PEM           # for submit-bug-report (Phase 39 W5a)
SKEINLY_DATABASE_WEBHOOK_SECRET             # for notify-on-write Database Webhook signing (Phase 24.1)
```

If the legacy 4 secrets (`APP_STORE_CONNECT_API_KEY` + `APP_STORE_CONNECT_KEY_ID` + `APP_STORE_CONNECT_ISSUER_ID` + `GOOGLE_PLAY_IAP_VALIDATOR_SA_JSON`) are still registered, they are now dormant — see the deprecation notes in EF-4 + "Reused: App Store Connect API key" sections above for the `unset` commands.

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
| `APPLE_PROVISIONING_PROFILE_BASE64` (DEPRECATED 2026-05-11) | No GitHub Secret rotation needed — sigh fetches the profile from Apple Developer Portal at every CI run. Manual re-Generate in Portal Web UI required only on capability additions / annual expiry. | N/A on GitHub side; Portal re-Generate on capability change or annual expiry |
| `APPLE_TEAM_ID` | Cannot change without changing teams | N/A |
| `APP_STORE_CONNECT_API_KEY_*` | App Store Connect → Team Keys → revoke + generate new (Supabase Edge Function side dormant since 2026-05-09 `verify-receipt` deletion — re-register only if Phase H' or equivalent revives a server-side validator) | Recommended every 12 months |
| `KEYSTORE_*` | **Do NOT rotate**. Losing the keystore breaks Play Store updates. Use Google Play's "App Signing by Google Play" key reset only as a last resort. | Never (under normal conditions) |
| `SUPABASE_PUBLISHABLE_KEY` | Supabase Dashboard → Project Settings → API Keys → Generate new Publishable key | On suspected leak |
| `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` (Environment scope) | Re-download from Firebase Console only on package or signing SHA-1 change (per environment) | Effectively never |
| `FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64` (Environment scope) | Re-download from Firebase Console only on Bundle ID change (per environment) | Effectively never |
| `SENTRY_DSN_*` | Sentry → Settings → Project → Client Keys → revoke + create new | On suspected leak |
| `SENTRY_AUTH_TOKEN` | **Organization Settings** → Auth Tokens → revoke + recreate | Annual or on incident |
| `POSTHOG_PROJECT_API_KEY` | PostHog → Settings → Project → reset Project API Key | On suspected leak |
| `REVENUECAT_API_KEY_*` | RevenueCat → Project Settings → Apps → Public SDK Key revoke + issue new | On suspected leak |
| Edge Function `APPLE_APNS_KEY_*` | Apple Developer → Keys → revoke + generate new + re-register Supabase secret | Annual or on incident |
| Edge Function `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase Console → Service Accounts → revoke + new key | Annual or on incident |
| Edge Function `GOOGLE_PLAY_IAP_VALIDATOR_SA_JSON` (DEPRECATED 2026-05-09) | Supabase secret dormant since `verify-receipt` deletion. Cloud Console → IAM → Service Accounts → `revenuecat@...` → revoke + new key → re-upload to RevenueCat dashboard (the live consumer of this SA) | Annual or on incident |
| Environment `GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64` | Cloud Console → IAM → Service Accounts → `google-play-publisher@...` → revoke + new key → re-run `gh secret set --env production` | Annual or on incident |
| Edge Function `REVENUECAT_WEBHOOK_SECRET` | `openssl rand -hex 32` for new value → update RevenueCat Webhook Authorization header → re-register Supabase secret | Annual or on incident |
| Edge Function `SKEINLY_DATABASE_WEBHOOK_SECRET` | `openssl rand -hex 32` for new value → re-register Supabase secret + update Authorization HTTP header on each of the 3 Database Webhooks via Dashboard | Annual or on incident |
| Edge Function `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM` | GitHub App settings → Generate new private key → download `.pem` → `supabase secrets set SKEINLY_BUGREPORT_PRIVATE_KEY_PEM="$(cat new.pem)"` → `supabase functions deploy submit-bug-report` → revoke old key on App settings page. `SKEINLY_BUGREPORT_APP_ID` + `SKEINLY_BUGREPORT_INSTALLATION_ID` do not rotate (immutable for the App). | Annual or on incident |

After rotating, re-run `gh secret set` for each affected secret. The next CI run picks up the new value automatically.

## Security notes

- **Never commit decoded files** (`.p12`, `.p8`, `.mobileprovision`, `.jks`) to the repository. Treat them like passwords.
- **Never paste secret values into AI assistant chats**, screenshots, or screen recordings. The base64 encoding is **not** encryption — anyone who sees the string can decode it.
- **Verify before registering**: every section above includes a `VERIFY` step. Skipping verification means a typo lands in production and the failure surfaces only at tag push.
- **`.p8` and `.jks` files have one-time-only download semantics** — back up to encrypted storage (password manager, encrypted disk image) immediately after generation.
- **GitHub Secrets are encrypted at rest** but visible to any workflow running with `secrets.*` access. Limit the number of workflows that read sensitive secrets via `permissions:` and `if:` guards.
- **The 7 iOS secrets allow your CI to upload to TestFlight under your Apple Developer account.** A leak permits an attacker to push malicious builds to your TestFlight testers. Revoke `APP_STORE_CONNECT_API_KEY_*` first if a leak is suspected — it has the highest blast radius.
