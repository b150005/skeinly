# Auth Smoke Test Runbook

> Phase 26.8 (ADR-022 §6.7) — end-to-end manual verification of the
> Phase 26 auth surface (OAuth, MFA, biometric, linkIdentity merge) on
> real devices before alpha launch invites go out.
>
> Run this whenever any Phase 26.x slice ships AND before the first
> closed-beta tester invite. Re-run on every release-candidate build.
>
> **Audience**: project operator. **Not for testers.** This is the
> operator's pre-flight checklist — testers receive a separate beta
> rubric ([phase-39-beta-rubric.md](../phase/phase-39-beta-rubric.md)).

## Prerequisites

- iOS device on iOS 17+ (TestFlight build OR locally signed via Xcode).
- Android device on API 26+ (Play Internal Testing build OR locally
  signed debug APK).
- Apple Sandbox tester account (created at App Store Connect → Users
  and Access → Sandbox Testers). Note: this is a separate Apple ID
  from your personal one; sign in to **Settings → Developer →
  Sandbox Apple ID** on iOS, NOT to the main Settings → Apple ID
  screen — that would replace your real Apple ID at the device level.
- Google test account (a regular gmail.com account, not necessarily
  privileged; OAuth Sandbox is automatic).
- Authenticator app installed (Google Authenticator / 1Password / Authy
  — your choice).
- Supabase project with Apple + Google OAuth providers configured
  (Dashboard → Authentication → Providers). Both must show
  "Enabled" and have the matching client IDs / Services ID / Team ID
  values from `release-secrets.md`.

## 1. Email/password baseline (smoke test the baseline before OAuth)

Run on **both** iOS and Android.

1. Fresh install or clear app data.
2. Sign-up screen: enter `smoke-test-<timestamp>@example.com` + a
   16-char password. Tap "Sign up".
3. Verify: account creation succeeds, no error banner; arrival on
   ProjectList.
4. Sign out from Settings → Account → Sign out.
5. Sign in with the same credentials. Verify: arrival on ProjectList,
   email shown in Settings → Account.

**Expected outcome**: ✅ both platforms.

## 2. Apple Sign-In (iOS only)

1. Fresh install OR sign out from §1.
2. On Login screen, tap "Continue with Apple".
3. SignInWithAppleButton sheet appears. Choose your Sandbox tester
   Apple ID. On first sign-in, Apple prompts whether to share your
   email or use a private relay — choose **private relay** for this
   test to exercise the relay-disclosure copy in Settings.
4. Verify the **post-OAuth profile setup gate** appears:
    - Screen title: "What should we call you?"
    - Name field pre-filled with the name from Apple (only on first
      sign-in — subsequent runs skip this screen because Apple omits
      the `full_name` claim).
    - No avatar tile (Apple doesn't expose a picture URL).
    - Tap "Save". Lands on ProjectList.
5. Open Settings → Account row. Verify:
    - Email shown as `<random>@privaterelay.appleid.com`.
    - "Signed in via: Apple (using a private email relay)" copy.
6. Sign out. Sign in again with the same Apple ID. Verify: no profile
   setup gate (preference flag prevents re-prompt), arrival on
   ProjectList directly.

**Expected outcome**: ✅ iOS only (Apple Sign-In on Android goes through
the web-OAuth path tested in §3).

## 3. Apple Sign-In on Android (web-OAuth + Custom Tabs)

1. Fresh install on Android OR sign out.
2. Tap "Continue with Apple" on Login screen.
3. A Custom Tab opens to Supabase's hosted OAuth flow → Apple Sign-In
   web page → back to `skeinly://auth-callback`.
4. Verify the same profile setup gate as §2.4 (Android variant).
5. Verify Settings → Account row matches §2.5.

**Expected outcome**: ✅ Android only.

## 4. Google Sign-In

Run on **both** iOS and Android.

1. Fresh install or sign out.
2. Tap "Continue with Google".
3. Choose your Google test account.
4. Verify the **post-OAuth profile setup gate** appears:
    - Name field pre-filled from `user_metadata.name`.
    - **Avatar tile visible** with "Use this picture as your avatar?"
      label. Tap **Use this picture**. Verify: progress spinner →
      "Picture imported" copy + "Choose different" affordance.
5. Tap **Choose different**. Verify: hint copy appears: "You can change
   your picture anytime in Settings → Profile." (Phase 26.7 Tech Debt
   resolution.) The avatar-imported state resets; user can re-tap "Use
   this picture" to bring it back.
6. Tap **Save**. Lands on ProjectList.
7. Open Settings → Account row. Verify:
    - Email shown as your Google email.
    - "Signed in via: Google" copy.
8. Open Settings → Profile. Verify: imported avatar visible.

**Expected outcome**: ✅ both platforms.

## 5. linkIdentity merge flow (email/password + OAuth same email)

1. Fresh install. Sign up with email/password using `linktest@example.com`.
2. Sign out.
3. Tap "Continue with Google" using a Google account that has the
   email `linktest@example.com` (or use the §4 Google account and
   substitute the email here for the §1 step).
4. Verify: LinkIdentityRequired dialog/sheet appears with the message
   "This email is already used — sign in with your password to link
   Google to your existing account".
5. Enter the password from step 1. Tap "Link account".
6. Verify: arrival on ProjectList. Settings → Account now shows
   "Signed in via: Email and password" with a "Linked accounts"
   section listing "Google".

**Expected outcome**: ✅ both platforms.

## 6. TOTP MFA enrollment + challenge

Run on **one platform** (Phase 26.5 logic is in the shared module; the
UI surface is symmetric on both).

1. Sign in. Settings → Security → "Enable two-factor authentication".
2. Scan the QR code (or copy the base32 string) into your authenticator
   app. Save the 16-char recovery code shown on screen — you will need
   it in step 8.
3. Enter the current 6-digit code from your authenticator. Tap "Verify".
4. Verify: Settings → Security row now shows "Two-factor authentication:
   Enabled".
5. Sign out. Sign in with email/password.
6. Verify the **TOTP challenge screen** appears. Enter the 6-digit code.
   Verify: arrival on ProjectList.
7. Sign out. Sign in again. This time tap "Use recovery code instead".
   Enter the recovery code saved in step 2.
8. Verify: arrival on ProjectList. Settings → Security row now shows
   "Two-factor authentication: Disabled" (recovery code consumed,
   factor unenrolled).
9. Re-enroll TOTP per step 1.

**Expected outcome**: ✅ at least one platform; second platform spot-
checked.

## 7. Biometric re-auth

Run on **both** iOS and Android. Requires a device with Face ID /
Touch ID (iOS) or fingerprint / face (Android) enrolled in OS Settings.

1. Sign in. Settings → Security → "Biometric authentication".
2. Toggle ON. Verify: OS biometric prompt fires immediately for
   confirmation. After success, the toggle stays ON and the threshold
   picker (1 min / 5 min / 15 min / 1 hour) appears.
3. Set threshold to 1 minute.
4. Background the app. Wait ≥ 70 seconds. Foreground the app.
5. Verify: biometric prompt fires with the "Confirm it's you to
   re-enter Skeinly" copy. Cancel the prompt.
6. Verify: app signs you out (per ADR-022 §3.8 — cancel-on-resume
   degrades to sign-out so the user re-authenticates from Login).
7. Sign in again. Walk through Settings → Security → "Disable two-
   factor authentication".
8. Verify: biometric prompt fires with the "Confirm it's you to disable
   two-factor authentication" copy (sensitive-action gate, separate
   from the foreground re-auth gate).
9. Walk through Settings → Account → Delete account.
10. Verify: biometric prompt fires with the "Confirm it's you to delete
    your account" copy. Cancel — account is NOT deleted. Re-attempt
    and accept — account is deleted, lands on Login.

**Expected outcome**: ✅ both platforms.

## 8. Biometric availability refresh after OS Settings round-trip

Phase 26.7 Tech Debt verification.

1. Sign in on a device with **no** biometric / PIN enrolled in OS
   Settings (clear the enrollment via Settings → Face ID & Passcode →
   Turn Passcode Off on iOS, or Settings → Security → Screen Lock →
   None on Android).
2. Open Settings → Security → Biometric authentication.
3. Verify: status row shows "No biometric or PIN enrolled in OS
   settings"; toggle is disabled.
4. Switch to OS Settings, enroll a biometric / PIN, return to Skeinly.
5. Verify: the screen automatically re-queries availability on
   `onAppear` (iOS) / `LaunchedEffect` (Android) and the toggle becomes
   enabled. Status row updates to "Disabled" (= toggle ready to flip on).

**Expected outcome**: ✅ both platforms.

## 9. Account deletion cascade

1. Sign in with the email/password account from §1. Enroll TOTP from
   §6. Enable biometric from §7.
2. Settings → Account → Delete account → accept biometric prompt →
   confirm deletion.
3. Verify on the Supabase Dashboard:
    - `auth.users` row gone.
    - `profiles` row gone (ON DELETE CASCADE).
    - `device_tokens` rows gone (ON DELETE CASCADE).
    - `mfa_recovery_codes` row gone (ON DELETE CASCADE).
    - `subscriptions` row gone (ON DELETE CASCADE).
4. Try to sign in with the same email — should fail with "User not
   found".

**Expected outcome**: ✅ verified via Supabase Dashboard.

## Test result template

Paste this into the operator notes after running:

```
Auth smoke test — <YYYY-MM-DD> — Build <git short SHA>
- §1 email/password: PASS / FAIL <notes>
- §2 Apple iOS:      PASS / FAIL
- §3 Apple Android:  PASS / FAIL
- §4 Google:         PASS / FAIL (iOS + Android)
- §5 linkIdentity:   PASS / FAIL
- §6 TOTP MFA:       PASS / FAIL
- §7 biometric:      PASS / FAIL
- §8 availability refresh: PASS / FAIL
- §9 cascade:        PASS / FAIL
```

## A0d-6 Data Safety re-submission recipe

After all of §1-§9 PASS, update Play Console Data Safety:

1. Play Console → All apps → Skeinly → App content → Data safety →
   Manage.
2. Page 3 "User data types collected":
    - **Photos and videos** → check "Photos" (was: unchecked; flips on
      because OAuth avatar import lands a user photo in Storage). NOT
      "Videos" (Phase 28 not shipped yet).
    - **Personal info** → check "Name" (always collected for OAuth +
      Profile; was likely already checked).
    - **Personal info** → check "Email" (was already checked).
3. Page 4 "Account-creation methods":
    - Check "OAuth" (newly added in Phase 26.1 + 26.2).
    - Check "Username and password and other authentication" (the
      Japanese label for MFA — Phase 26.5 shipped TOTP, so this is
      now applicable).
4. Save + submit. The new Data Safety form ships with the next Play
   release; review status moves from "Active" to "In review" for
   1-3 days then back to "Active".

Apple App Store Connect privacy nutrition facts — the parallel surface
is "App Privacy" → Edit → make the same updates. Apple's review path
is faster (typically same day).
