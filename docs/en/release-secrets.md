# Release Secrets — Setup Guide

> Japanese translation: [docs/ja/release-secrets.md](../ja/release-secrets.md)

This document is a step-by-step guide for obtaining, verifying, and registering every GitHub Secret consumed by the release pipeline. It covers 13 secrets in 3 categories:

- **iOS** (7) — code signing + App Store Connect API authentication
- **Android** (4) — keystore signing
- **Runtime** (2) — Supabase backend credentials

The release workflow ([`.github/workflows/release.yml`](../../.github/workflows/release.yml)) reads these as `${{ secrets.* }}`. Missing or incorrect values fail the release silently for some (build still succeeds, just no upload) or loudly for others (signing fails). This guide includes verification steps so you can confirm a value is correct **before** registering it.

## Table of contents

- [Prerequisites](#prerequisites)
- [How to register a secret in GitHub](#how-to-register-a-secret-in-github)
- [iOS code signing (4 secrets)](#ios-code-signing-4-secrets)
- [App Store Connect API (3 secrets)](#app-store-connect-api-3-secrets)
- [Android signing (4 secrets)](#android-signing-4-secrets)
- [Supabase runtime (2 secrets)](#supabase-runtime-2-secrets)
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

All secret commands assume you are inside the repository root (`cd knit-note`).

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
   - Use an existing profile if one already covers `io.github.b150005.knitnote` for App Store distribution. Click it → **Download**.
   - Or click `+` → **App Store** under Distribution → select bundle ID `io.github.b150005.knitnote` → select the Apple Distribution cert from step 1 → name it (e.g. `Knit Note App Store`) → **Generate** → **Download**.
3. Base64-encode:

   ```bash
   base64 -i Knit_Note_App_Store.mobileprovision -o profile.base64
   ```

**VERIFY:**

```bash
# Decode the CMS envelope — output is XML plist with the profile metadata.
security cms -D -i Knit_Note_App_Store.mobileprovision | head -40
```

Confirm:
- `<key>Name</key>` value matches what you named the profile
- `<key>application-identifier</key>` ends with `io.github.b150005.knitnote`
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
   security cms -D -i Knit_Note_App_Store.mobileprovision \
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
   - **Name**: e.g. `knit-note CI`
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

### 13. `SUPABASE_ANON_KEY`

**WHAT**: The "anon" public API key. Designed to be embedded in client apps (RLS policies prevent unauthorized access at the database layer).

**OBTAIN:**

- Same Project Settings page → **API Keys** section → copy the **anon public** key (a long JWT-shaped string starting with `eyJ`).

**VERIFY**: Starts with `eyJ` (Base64-URL-encoded JWT). Decoding the middle segment (e.g. with [jwt.io](https://jwt.io)) shows `"role": "anon"` and `"ref": "<project-ref>"`.

**REGISTER:**

```bash
gh secret set SUPABASE_ANON_KEY
# paste long JWT, Ctrl+D
```

**Do NOT register the `service_role` key as a GitHub Secret for client builds**. The service-role key bypasses RLS and would be a critical leak if shipped in an APK.

## Bulk verification

After registering all 13 secrets, confirm with `gh`:

```bash
gh secret list
```

Expected output (names + last-updated timestamps; values are never shown):

```
APPLE_TEAM_ID                      Updated YYYY-MM-DD
APP_STORE_CONNECT_API_KEY_BASE64   Updated YYYY-MM-DD
APP_STORE_CONNECT_API_KEY_ID       Updated YYYY-MM-DD
APP_STORE_CONNECT_ISSUER_ID        Updated YYYY-MM-DD
APPLE_DISTRIBUTION_CERT_BASE64       Updated YYYY-MM-DD
APPLE_DISTRIBUTION_CERT_PASSWORD     Updated YYYY-MM-DD
APPLE_PROVISIONING_PROFILE_BASE64    Updated YYYY-MM-DD
KEYSTORE_BASE64                    Updated YYYY-MM-DD
KEYSTORE_PASSWORD                  Updated YYYY-MM-DD
KEY_ALIAS                          Updated YYYY-MM-DD
KEY_PASSWORD                       Updated YYYY-MM-DD
SUPABASE_ANON_KEY                  Updated YYYY-MM-DD
SUPABASE_URL                       Updated YYYY-MM-DD
```

13 entries. Anything missing or with a stale timestamp is suspect.

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
| `APP_STORE_CONNECT_API_KEY_*` | App Store Connect → Team Keys → revoke + generate new | Recommended every 12 months |
| `KEYSTORE_*` | **Do NOT rotate**. Losing the keystore breaks Play Store updates. Use Google Play's "App Signing by Google Play" key reset only as a last resort. | Never (under normal conditions) |
| `SUPABASE_*` | Supabase Dashboard → Project Settings → API Keys → reset anon key | On suspected leak |

After rotating, re-run `gh secret set` for each affected secret. The next CI run picks up the new value automatically.

## Security notes

- **Never commit decoded files** (`.p12`, `.p8`, `.mobileprovision`, `.jks`) to the repository. Treat them like passwords.
- **Never paste secret values into AI assistant chats**, screenshots, or screen recordings. The base64 encoding is **not** encryption — anyone who sees the string can decode it.
- **Verify before registering**: every section above includes a `VERIFY` step. Skipping verification means a typo lands in production and the failure surfaces only at tag push.
- **`.p8` and `.jks` files have one-time-only download semantics** — back up to encrypted storage (password manager, encrypted disk image) immediately after generation.
- **GitHub Secrets are encrypted at rest** but visible to any workflow running with `secrets.*` access. Limit the number of workflows that read sensitive secrets via `permissions:` and `if:` guards.
- **The 7 iOS secrets allow your CI to upload to TestFlight under your Apple Developer account.** A leak permits an attacker to push malicious builds to your TestFlight testers. Revoke `APP_STORE_CONNECT_API_KEY_*` first if a leak is suspected — it has the highest blast radius.
