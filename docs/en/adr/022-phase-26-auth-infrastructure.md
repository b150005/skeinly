# ADR-022 — Phase 26: Auth Infrastructure (OAuth + MFA + Biometric Re-auth)

> **Status**: Accepted (2026-05-14)
> **Phase**: 26 (pre-alpha launch HARD-GATE)
> **Supersedes**: none
> **Superseded by**: none
> **Related**: ADR-005 (account deletion RPC — the cascade path that any new identity / MFA / biometric state must survive), ADR-017 (push notifications — established the `expect/actual` KMP-native + Supabase-Edge-Function pattern this ADR reuses for OAuth ID-token exchange), ADR-021 (UGC moderation — Settings → Privacy section that hosts the new MFA + Blocked Users + biometric controls), ADR-016 (subscriptions — the future-marketplace context that motivates building auth-security before any monetary value accrues).
> **Tracking**: HARD-GATE for alpha-launch tester invites. 7 sub-slices per §6 below. Sub-slice 26.0 (this ADR + CLAUDE.md promotion to active roadmap) — no code. Sub-slices 26.1 → 26.7 each ship independently and must ALL ship before TestFlight + Play Internal Testing tester invites go out.

JA summary: [../../ja/adr/022-phase-26-auth-infrastructure.md](../../ja/adr/022-phase-26-auth-infrastructure.md) (cut alongside this ADR).

## 1. Context

Skeinly's auth surface as of 2026-05-14 is **email/password only**, mediated by supabase-kt 3.6 `Auth` module:

- `AuthRepository` (commonMain) exposes `signInWithEmail` / `signUpWithEmail` / `signOut` / `deleteAccount` / `sendPasswordResetEmail` / `updatePassword` / `updateEmail` only. No `signInWithIdToken` call site. No `mfa.enroll` / `challenge` / `verify`. Zero biometric integration.
- `AuthRepositoryImpl` instantiates Supabase Auth with `FlowType.PKCE` and a `SettingsSessionManager` backed by encrypted `Settings` (Android `EncryptedSharedPreferences` per Pre-alpha A14 / iOS Keychain).
- `AuthViewModel` exposes a 5-event sealed `AuthEvent` (`UpdateEmail` / `UpdatePassword` / `ToggleMode` / `Submit` / `ClearError` / `DismissEmailConfirmation`) — no OAuth-button / MFA-challenge / biometric-prompt events.
- `iosApp/iosApp/iosApp.entitlements` carries `aps-environment = production` + `com.apple.developer.associated-domains` only. The Apple Sign-In capability was set up at the App ID + Distribution Provisioning Profile level (vendor-setup A0a-1 / A0a-4) but the **`com.apple.developer.applesignin` entitlement key is absent from the app bundle** — Apple Sign-In is structurally non-functional today.
- No Google Sign-In wiring exists on Android (no `androidx.credentials` Credential Manager import, no `play-services-auth` dep, no Google client IDs registered).
- The only "remembered identity" path is the supabase-kt refresh token persisted in encrypted Settings; lock-screen-style re-auth does not exist.

**User policy shift 2026-05-14**: Phase 26 was originally framed as a post-Phase-39-beta polish item. Operator surfaced two converging pressures while filling Play Console A0d-6 Data Safety (account-creation methods sub-question):

1. **A0d-6 forces a declarative answer.** "アカウント作成方法" expects the operator to list which provider modalities are supported. Email/password alone is honest today, but locking the alpha to email/password forecloses on adding OAuth declarations later without re-submitting Data Safety + Privacy Policy at the moment when alpha testers are most aligned with the original product story.
2. **Future user-to-user pattern marketplace.** Operator's strategic direction (2026-05-14) targets a planned marketplace where buyer / seller / creator accounts hold real monetary value (purchase history, payout balance, listing revenue). **Retrofitting auth-security into an established user base post-marketplace launch is significantly harder than building it from the start** — existing users resist mandatory MFA enrollment after the fact, and any account-takeover incidents during the retrofit window damage trust during the most-fragile market-formation phase.

The agent-team deliberation that surfaced from those two pressures (recorded in CLAUDE.md "### Planned — Phase 26 Auth Infrastructure", 2026-05-14) settled the framing: **ship the full auth surface — OAuth providers AND MFA AND biometric re-auth — before alpha launch**, with the explicit recognition that current asset value does not warrant MFA. The decision is **cost-of-now vs. cost-of-later**: every line of auth code that lands before any user account exists is decisively cheaper than the same line landing after.

**Vendor pillars are already prepared** (no Apple Developer Portal / Google Cloud / Supabase Dashboard work needed for Apple Sign-In; partial work needed for Google Sign-In + Supabase provider config):

- iOS Bundle ID `io.github.b150005.skeinly` has the **Sign In with Apple capability** enabled in the App ID (vendor-setup A0a-1) and on the Distribution Provisioning Profile (A0a-4). The Phase 26.1 work is adding the entitlement key to the app bundle + wiring `AuthenticationServices` — no Portal changes.
- Apple `.p8` + Team ID + Key ID are already registered as `APPLE_APNS_KEY_P8` / `APPLE_APNS_KEY_ID` / `APPLE_TEAM_ID` for Phase 24 push. **Apple Sign-In needs a SEPARATE `.p8`** (different "service" purpose on Apple's side) — the existing APNs `.p8` is not reusable. Phase 26.1 adds Apple Sign-In secrets.
- Firebase project `Skeinly` (Blaze) has the FCM SDK SHA-1s registered for both debug + release signing keys, which means the same Firebase project surfaces are reachable for Google Sign-In via `androidx.credentials` Credential Manager — but **the Google OAuth client IDs themselves (Web + iOS + Android) are not yet provisioned in Google Cloud Console** under the corresponding GCP project. Phase 26.2 adds these.
- Supabase Dashboard → Authentication → Providers currently has only Email enabled. Phase 26.1 and 26.2 enable Apple + Google providers respectively, requiring the new secrets above plus the Services ID / Web client ID per provider.

**What's missing for Phase 26 to land**:

- `AuthRepository` surface extension: `signInWithApple` / `signInWithGoogle` / `linkIdentity` / `enrollMfaTotp` / `verifyMfaChallenge` / `disableMfa` / `regenerateRecoveryCode` / `requireBiometric` (8 new methods).
- KMP `expect/actual` `OAuthClient` for the platform-side identity-token retrieval (iOS `ASAuthorizationAppleIDProvider` + `GIDSignIn`; Android Credential Manager `GetCredentialRequest`).
- KMP `expect/actual` `BiometricAuthenticator` (iOS `LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)`; Android `androidx.biometric:biometric` `BiometricPrompt`).
- `iosApp.entitlements`: add `com.apple.developer.applesignin` key with `["Default"]` value.
- Android: `androidx.credentials:credentials` + `androidx.credentials:credentials-play-services-auth` + `com.google.android.libraries.identity.googleid:googleid` deps; or `com.google.android.gms:play-services-auth` for older API support.
- Android: `androidx.biometric:biometric` dep.
- iOS `LoginScreen.swift` + Compose `LoginScreen.kt`: OAuth-button block above the existing email/password form, platform-conventional ordering.
- New `MfaEnrollmentScreen` + `MfaChallengeScreen` + `MfaRecoveryCodeScreen` + `BlockedUsersScreen-like BiometricSettingsScreen` (Compose + SwiftUI mirrors).
- Supabase Auth Dashboard: Apple provider config (Services ID + Team ID + Key ID + `.p8` blob) + Google provider config (Web client ID + Web client secret + Android client ID + iOS client ID).
- 6 new release-secrets entries (3 for Apple Sign-In, 3 for Google Sign-In) — keystore-level secrets for the Dashboard provider config only; not embedded in any client bundle.
- Privacy policy `<h3>` subsections for OAuth provider identity data, MFA enrollment artifacts, biometric data handling.
- A0d-6 Data Safety re-submission after Phase 26.7 closes — account-creation methods updated EN: email/password → email/password + OAuth (Apple, Google) + MFA. JA mirror.

This ADR locks in the auth surface architecture, the 8 substantive design decisions, and the integration boundary before any code lands. Same precedent shape as ADR-013 / ADR-014 / ADR-015 / ADR-016 / ADR-017 / ADR-018 / ADR-021.

## 2. Decisions (high-level)

1. **Cross-platform symmetric OAuth coverage**: Apple Sign-In + Google Sign-In **on both platforms**. Apple Sign-In is mandatory on iOS per App Store Review Guideline §4.8 once any third-party SSO is offered; Apple-on-Android is offered for cross-platform identity portability. Google-on-iOS satisfies the §4.8 symmetric-availability expectation. Email/password retained as third option.
2. **Supabase Auth integration via `signInWithIdToken(provider, idToken, nonce?)`** for both OAuth providers; native `auth.mfa.enroll({factorType: "totp"})` / `auth.mfa.challenge({factorId})` / `auth.mfa.verify({factorId, challengeId, code})` for TOTP. **Biometric is a session-gate, NOT a primary identity factor** — Supabase Auth has no native binding of platform biometric to identity, so reimplementing it would require client-side cryptographic refresh-token unlock with custodial complexity that exceeds its value.
3. **Account-merge semantics = explicit-link via `linkIdentity`**: when OAuth ID-token email matches an existing email/password user, surface a "this email is already used; sign in with your password first to link" screen. Auto-merge rejected (silent identity attach is the OWASP-classic account-takeover vector — an attacker who obtains an OAuth token for the victim's address could attach their own provider identity to the victim's account). First-claim-wins rejected (rebooting users from an existing account is a worse UX outcome than a single "link to existing account" extra step).
4. **OAuth-first user identity flow**: respect Apple "private relay" emails (relay email surfaces in Supabase auth.users.email column, never the real email; user keeps the relay-forever bond intentionally — relay revocation is OS-mediated). Google sends real email + name + (sometimes) avatar URL. **Display name source = OAuth-provided name on first sign-in, prompt for confirmation in onboarding**, then user-editable via Profile screen. Avatar = OAuth-provided picture URL as default if present; user can override via existing Profile avatar upload (Phase 19).
5. **Sign In button placement = platform-conventional first, secondary provider below**: iOS shows Sign in with Apple primary, "Continue with Google" secondary, then email/password. Android shows Sign in with Google primary (via Credential Manager bottom sheet), "Continue with Apple" secondary, then email/password. **Cross-platform symmetric availability per §4.8 but conventional primacy per HIG / Material**.
6. **MFA = opt-in for alpha; future-marketplace seller/creator role = mandatory at marketplace launch (NOT Phase 40 GA)**. Alpha rationale: TOTP adoption rate at <10 testers carries zero signal; alpha tester friction from mandatory TOTP would actively harm the feedback loop the alpha exists to gather. Marketplace cutover plan: enforce on user-role transitions ("upgrade to seller" prompts TOTP enrollment as part of seller-onboarding); enforce globally NEVER (forcing existing users to enroll TOTP retroactively is the exact retrofit pattern Phase 26 exists to avoid, but the right escape valve is role-based, not global).
7. **TOTP recovery code UX = one-time display with screenshot-recommend warning + lost-device email-rebinding**. One regeneration path: enter current TOTP → server returns fresh recovery code → old code invalidated. Lost-device flow: send magic-link to original email, on click re-enroll TOTP from scratch. **Lost both device AND email = irrecoverable by design**. No admin-side break-glass: Skeinly has no admin support model that would justify a "Skeinly support resets your MFA" path; adding one creates a social-engineering attack surface that outweighs the rescued-account count at <10K users.
8. **Biometric scope = re-auth gate after background-threshold + sensitive-action gate**. Re-auth threshold default 5 min (Settings configurable); applies to all signed-in sessions opt-in via Settings → Privacy → Biometric. Sensitive-action gates (alpha): account deletion, MFA disable. Sensitive-action gates (future marketplace): purchase confirm, payout-method change. **Primary biometric login deferred** because supabase-kt has no native API to bind biometric template to refresh token; reimplementing would require either client-side encryption of refresh token with biometric-protected key (Android Keystore `setUserAuthenticationRequired(true)` + iOS Keychain `kSecAccessControlBiometryCurrentSet`) OR an Edge Function that issues short-lived JWTs gated by a biometric-attestation header. The former is doable in 26.6 (~200 LOC) but the value-add over "user has to re-enter password every X days when refresh expires" is small at alpha; deferred to a future ADR if real signal surfaces.

## 3. Decisions (detailed)

### 3.1 OAuth provider scope

**Agent-team deliberation**:

- **product-manager**: alpha launch needs frictionless onboarding. Apple Sign-In on iOS is the lowest-friction modality on iOS (one tap, no email-confirmation round trip, no password to remember). Google Sign-In on Android is the equivalent. Marketplace lens: a buyer who originally signed up via Apple-on-iOS should be able to recover their account on a new Android device without proof of an iOS device — cross-platform OAuth is necessary, not optional, for portability.
- **architect**: implementation surface is symmetric between Apple-on-iOS + Apple-on-Android, and between Google-on-iOS + Google-on-Android. The platform-conventional path uses native SDKs (`ASAuthorizationAppleIDProvider` / Credential Manager); the cross-platform path uses each provider's web-OAuth flow inside a `WKWebView` / `Custom Tabs`. The `signInWithIdToken` call into Supabase is the same regardless of which path produced the ID token — the seam is at ID-token acquisition only.
- **security-reviewer**: every OAuth provider added is a new account-takeover lateral path. Apple Sign-In is the strongest of the four (Apple enforces 2FA on every Apple ID; provider-side compromise rate is the lowest in the industry); Google Sign-In is second. The added attack surface from adding Google-on-iOS (vs Apple-on-iOS alone) is bounded — the user already trusts Google with their primary email in most cases. Apple-on-Android via web-OAuth is the highest-friction-but-not-significantly-weaker path (the user must authenticate to Apple via web; Apple still enforces 2FA). Symmetric coverage is the right call.
- **ui-ux-designer**: HIG demands Apple Sign-In primary on iOS once any other SSO is offered. Material Design doesn't have an equivalent demand for Google on Android, but Credential Manager's bottom-sheet UX with Google primary is the de facto standard. Showing 3 providers (Apple + Google + email/password) on each platform is the right cap — 4+ providers fragment attention.
- **implementer**: Apple-on-Android via web-OAuth requires a Services ID configured in Apple Developer Portal as a "web auth" service (different from the App ID-bound iOS Sign-In). The `redirect_url` must be set to Supabase's `auth/v1/callback` endpoint. Concrete dependency: `androidx.browser:browser` for Custom Tabs. Google-on-iOS via `GIDSignIn` SDK is a `google-signin-ios` SwiftPM package — a real new dep.
- **knitter**: knitters skew slightly older than median tech-savvy demographic (Pew 2024 craft-app survey: median 38, vs general mobile-app median 32). Older users are MORE likely to recognize and trust an "Apple" or "Google" button than a password form — OAuth lowers friction for our target demographic more than it lowers it generically. Three providers per platform is not too many.

**Decision**: Apple Sign-In + Google Sign-In **on both platforms** + email/password retained. iOS uses native Apple flow + GIDSignIn for Google. Android uses native Credential Manager for Google + web-OAuth (Custom Tabs) for Apple.

Rationale:
- Cross-platform identity portability is a non-negotiable for the marketplace context — users must be able to switch platforms without losing their account.
- §4.8 compliance is preserved (Apple offered when any other SSO is offered, on iOS).
- The implementation surface is bounded by the "ID token in, Supabase session out" seam: 4 paths converge to 1 backend call (`signInWithIdToken`).
- Provider-conventional primacy preserves familiar UX on each platform without sacrificing portability.

### 3.2 Supabase Auth integration path

**Agent-team deliberation**:

- **architect**: Supabase Auth supports OAuth via `signInWithIdToken(provider, idToken, nonce?)` (no in-app redirect; the client retrieves the ID token via the native SDK and posts it directly to Supabase) AND via `signInWithOAuth(provider)` (Supabase-hosted redirect via in-app browser). The ID-token path is preferable because it avoids the in-app browser hop and the associated deep-link routing (which would interact with Phase 39 W5b Universal Links infrastructure). MFA is `auth.mfa.enroll({factorType: "totp"})` returning a `secret` + `qr_code_svg`; verification is two-step (`challenge` returns a challenge_id, `verify` accepts code).
- **security-reviewer**: Supabase Auth's MFA implementation stores the TOTP secret in the auth.mfa_factors table encrypted at rest with a Supabase-managed key. The recovery-code path is a `auth.mfa.listFactors` + `auth.mfa.unenroll` sequence — we wrap that as `regenerateRecoveryCode` in our domain layer. Biometric never touches Supabase — it's purely client-side LocalAuthentication / BiometricPrompt outcome that gates whether we surface UI past the lock screen.
- **implementer**: `signInWithIdToken` signature on supabase-kt 3.6: `client.auth.signInWith(IDToken) { idToken = "..."; provider = Provider.Apple; nonce = "..." }`. The `nonce` is required for Apple (Apple includes it in the JWT claims; we generate it client-side, hash it for the request, and Supabase verifies). For Google, nonce is optional but recommended. MFA endpoints surface on `client.auth.mfa.*` — supabase-kt 3.6 has them GA.
- **product-manager**: relying on Supabase Auth native MFA means we don't run our own TOTP secret store. If Supabase ever changes MFA pricing or sunsets MFA, we migrate; until then the maintenance cost is zero.
- **knitter**: TOTP via Supabase is invisible to the user — they just see "scan this QR with Google Authenticator / 1Password / Authy" and "enter the 6-digit code". Provider-agnostic.

**Decision**: `signInWithIdToken` for OAuth + `auth.mfa.{enroll,challenge,verify,unenroll,listFactors}` for TOTP. Biometric stays client-only.

### 3.3 Account-merge semantics

**Agent-team deliberation**:

- **security-reviewer**: silent auto-merge is the canonical account-takeover vector. Threat model: attacker registers `victim@gmail.com` on Google with a fresh Google account (Google permits this if the victim's Google account doesn't exist yet OR the attacker steals the victim's session via phishing), then signs in to Skeinly with that Google account → if we auto-merge by email, the attacker now has access to the victim's email-password Skeinly account. This is well-documented (Microsoft 2021 LinkedIn report). Hard veto on silent auto-merge.
- **architect**: Supabase Auth's default on email-collision in `signInWithIdToken` is to reject with `{ "error": "user_already_exists" }`. We catch that error in `AuthRepositoryImpl` and surface a new domain-state `AuthState.LinkIdentityRequired(email, providerToLink)` — the LoginScreen reacts by routing to a "this email already exists; sign in with your password first" form. After password sign-in succeeds, we call `client.auth.linkIdentity(provider)` which posts the previously-collected ID token + current session JWT to link the identity. Supabase verifies the ID token's email matches the session's email server-side, preventing the takeover.
- **product-manager**: this is a one-time-per-account friction point — once linked, the user can sign in with either provider seamlessly. The alternative (silent auto-merge) is a non-starter on security grounds; the alternative-alternative (first-claim-wins, where OAuth simply creates a separate account if email is taken) is a UX dead-end (user creates 2 accounts, doesn't know which one has their data).
- **ui-ux-designer**: the friction is bounded by clear copy. "Your email is already used. Sign in with your password to link your Apple ID to that account." The user pays the friction once.
- **knitter**: this is the kind of friction that knitters tolerate when the security narrative is clear ("we want to make sure no one else can access your patterns"). Plain-language copy matters.

**Decision**: explicit-link via `linkIdentity`. New domain state `AuthState.LinkIdentityRequired(email, pendingIdToken, provider)` carries the pending ID token across the password sign-in step. LoginScreen routes to an inline "link identity" form on this state; after password sign-in succeeds AND `linkIdentity(pendingIdToken)` succeeds, the user is signed in with both identities attached.

### 3.4 Display name + email handling for OAuth-first users

**Agent-team deliberation**:

- **security-reviewer**: Apple Sign-In uses **private email relay** by default — the address surfaced to Supabase is `<random>@privaterelay.appleid.com`, NOT the user's real email. Apple proxies emails sent to that address to the user's real address. **The relay is forever** — even if we wanted to display the real email later, we cannot, because Apple does not reveal it. Implications: (a) email-verification emails from Supabase work (Apple proxies them); (b) account recovery via "enter your email" doesn't work for users who only entered a relay (they don't know the relay address). Mitigation: surface the relay clearly in Settings → Account ("Signed in as Apple ID — recovery via Apple") so the user knows.
- **architect**: `signInWithIdToken` Apple-path response includes `user.user_metadata.name` (first name + last name) ONLY on first sign-in — Apple deliberately omits the name on subsequent sign-ins. We must persist it server-side on first sign-in, OR prompt the user in onboarding if Apple omitted it. Google sends `user.user_metadata.name` + `user.user_metadata.picture` + `user.user_metadata.email_verified` on every sign-in, but the auth.users.email column is set once.
- **ui-ux-designer**: post-OAuth onboarding flow:
  1. OAuth ID-token exchange succeeds → Supabase session created.
  2. Client reads `user.user_metadata.name` (Apple first-sign-in only / Google always).
  3. If present, pre-fill display name in onboarding "What should we call you?" screen.
  4. If absent (Apple subsequent sign-in case, but on a fresh client install — i.e. iOS device reset), prompt user to enter display name.
  5. Avatar: if `user.user_metadata.picture` URL is present (Google), offer to import it as initial avatar; if absent (Apple), default avatar applies.
- **product-manager**: this is the kind of detail that distinguishes "tech-polish" apps from "tech-rough" apps. Pre-filling the display name from OAuth save the user 5 seconds and signals attention to detail.
- **knitter**: the avatar bit matters disproportionately to knitters — many users have an existing avatar on their Google account they're comfortable with; offering to import it removes friction.

**Decision**:
- Display name source: OAuth-provided name on first sign-in (`user.user_metadata.name`), prompt for confirmation in onboarding, then user-editable via Profile screen. If absent (Apple subsequent), prompt the user.
- Email handling: store whatever Supabase gives us (relay for Apple-relay users, real for Google + real-email Apple). Surface the relay status in Settings → Account ("Signed in via Apple — using private email relay").
- Avatar: OAuth-provided picture URL imported as initial avatar if present (with one-tap "use this avatar" / "use a different one" choice in onboarding). User can replace via existing Phase 19 avatar upload flow.

### 3.5 Sign In button placement + ordering

**Agent-team deliberation**:

- **ui-ux-designer**: Apple HIG demands Sign in with Apple be **prominent and at least as prominent as other sign-in options** on iOS. The conventional placement is at the top of the sign-in form. Material Design 3 doesn't have an equivalent prominence demand, but Google's Credential Manager bottom sheet is the canonical Android pattern — surfacing Google-on-Android via a Material `OutlinedButton` styled with the Google G logo is the second-best. Cross-platform symmetric availability per §4.8 means iOS shows Google as a second option, but Apple stays primary.
- **implementer**: on iOS, `SignInWithAppleButton` SwiftUI component handles the HIG-conformant rendering (corner radius, text styling, system black/white variants). For "Continue with Google" on iOS we use a custom `Button` styled per Google's branding guidelines (white background, Roboto Medium text, G logo). On Android, Credential Manager surfaces Google sign-in via its own bottom-sheet — we trigger it via `androidx.credentials:credentials` `getCredential(request)` on button tap, where the button is a custom Material `OutlinedButton`. "Continue with Apple" on Android uses Custom Tabs + Supabase web-OAuth flow.
- **security-reviewer**: the button order itself doesn't affect security. The matters: every OAuth button must be visually distinguishable from email/password (so users don't accidentally type a Google password into the Skeinly password field) AND each OAuth flow must NOT auto-fire (no "tap once, immediately Apple-popup" — the user must explicitly tap each one). Both are standard UI practices.
- **product-manager**: order also reflects which provider we want users to gravitate toward. Apple-first on iOS reinforces Apple's trust + privacy positioning (relay email, no Google data sharing); Google-first on Android reinforces frictionlessness (Credential Manager). Both align with Skeinly's "we care about your craft + your privacy" positioning.
- **knitter**: knitters trust Apple more for privacy and Google more for convenience — provider primacy aligned with platform matches the mental model.

**Decision**:

| Platform | Primary | Secondary | Tertiary |
|---|---|---|---|
| iOS | Sign in with Apple (HIG-conformant `SignInWithAppleButton`, top of form) | Continue with Google (custom-styled `Button`) | Email/Password form (collapsed under "or sign in with email" toggle by default) |
| Android | Continue with Google (Credential Manager-triggering `OutlinedButton`, top of form) | Continue with Apple (Custom Tabs + Supabase web-OAuth) | Email/Password form (same toggle) |

The email/password form being collapsed under a toggle nudges users toward OAuth without making email/password feel hidden — it remains discoverable in the same visual hierarchy.

### 3.6 MFA opt-in vs mandatory

**Agent-team deliberation**:

- **product-manager**: alpha = opt-in. Forcing 5-10 testers to enroll TOTP before they can post a PR comment will cause 100% drop-off on the MFA enrollment screen — testers signed up to evaluate the chart editor + collaboration loop, not the auth security model. Friction that doesn't map to the alpha's question burns goodwill.
- **security-reviewer**: alpha asset value is near-zero — no monetary value, no PII beyond email + display name + chart contents (knitting patterns aren't sensitive PII in any jurisdiction). Mandatory MFA at alpha is over-protective. **Future marketplace seller / creator roles handle real money** — mandatory at the role-transition boundary is the right enforcement point. Refusing to enforce globally at Phase 40 GA is the correct call because (a) Phase 40 GA users are still NOT sellers yet (general consumers buying patterns are buyer-only), (b) globally mandating TOTP would force every existing alpha + beta tester to enroll on GA-day, which is the retrofit pattern Phase 26 exists to avoid.
- **architect**: role-based MFA enforcement is a domain-layer concern. The seller-onboarding flow (Phase 50+ marketplace) calls into `AuthRepository.requireMfa()` which checks `auth.mfa.listFactors()` returns non-empty AND verified; if not, routes the user to `MfaEnrollmentScreen` and blocks seller-onboarding completion. Phase 26 ships the enrollment + verification mechanics; Phase 50+ wires the requirement gate on role transition.
- **ui-ux-designer**: opt-in entry surface = Settings → Privacy → 2 要素認証 (matches the Phase 25 friend-only + Phase 26.6 biometric Settings → Privacy grouping). Disabled state shows "Off — tap to enable"; enabled state shows "On — tap to manage".
- **knitter**: most knitters won't enable opt-in MFA at alpha. That's fine — the feature exists for the subset who care about it (operator + security-conscious testers), and the infrastructure to make it mandatory at marketplace-time is already in place.

**Decision**: opt-in for alpha + Phase 40 GA general users; mandatory at role-transition for marketplace seller/creator roles (Phase 50+, separate ADR). Settings → Privacy → 2 要素認証 is the alpha entry surface.

### 3.7 TOTP recovery code UX

**Agent-team deliberation**:

- **security-reviewer**: recovery code is a one-time-use code that bypasses TOTP if the user loses their authenticator device. Industry standard: 8-12 chars alphanumeric, displayed once at enrollment, MUST be screenshot/recorded by the user. Display-only-once is non-negotiable — storing it client-side defeats the purpose; emailing it post-hoc creates an email-compromise → account-compromise path. Regeneration MUST require current TOTP (so a stolen recovery code can't be used to revoke the legitimate user's other recovery codes).
- **implementer**: Supabase Auth's MFA model has one recovery mechanism — `auth.mfa.unenroll(factorId)` which removes the TOTP factor entirely. There's no built-in "recovery code" type. We wrap our own recovery layer: at enrollment, generate a recovery code client-side (16 chars base32), POST it to a new `mfa_recovery_codes` table via a SECURITY DEFINER RPC `register_mfa_recovery_code(p_code_hash TEXT)` that stores ONLY the bcrypt hash (NOT plaintext). At recovery-code-use, the client posts the plaintext to a `consume_mfa_recovery_code(p_code TEXT)` RPC that bcrypt-verifies, deletes the row, then calls `auth.mfa.unenroll` server-side. Single-use enforced.
- **architect**: the recovery code surface is a small new table + 2 RPCs. The table inherits ON DELETE CASCADE from auth.users so ADR-005 account-deletion cleanup is automatic. RLS: own-row SELECT/INSERT/DELETE only.
- **product-manager**: lost-device flow = re-bind via email. User taps "I lost my authenticator" → server sends magic-link to original auth.users.email → on click re-enroll TOTP from scratch. **Lost both device AND email = irrecoverable**. The decision to not provide an admin-side break-glass is explicit: Skeinly has no support team that would justify the social-engineering attack surface of "Skeinly support resets your MFA". The rescued-account count at <10K users is approximately zero; the attack surface from operator-side reset is high (an attacker who compromises the operator's GitHub credentials could revoke any user's MFA).
- **ui-ux-designer**: enrollment flow UI:
  1. Settings → Privacy → 2 要素認証 → "Enable".
  2. QR code displayed + manual-entry secret displayed (for users without a camera).
  3. User scans + enters first 6-digit code → server verifies via `mfa.challenge` + `mfa.verify`.
  4. On success: full-screen "Save your recovery code" with the 16-char code in a copyable text field + "I've saved this" CTA (CTA disabled for 5 seconds to force the user to actually look at the code) + "Take a screenshot" hint with iOS / Android instruction.
  5. Recovery-code screen is non-skippable; back button is disabled.
  6. On dismiss, recovery code is unrecoverable until next regeneration.
- **knitter**: the "save your recovery code" friction is fine for the subset of users who opt-in to MFA. They're already security-aware.

**Decision**:
- TOTP recovery code: 16-char base32, client-generated at enrollment, hash stored server-side via `register_mfa_recovery_code` RPC, plaintext displayed ONCE at enrollment in a non-skippable screen.
- Regeneration path: requires current TOTP, returns a new recovery code, invalidates the old one.
- Lost-device path: magic-link to original email re-enrolls TOTP from scratch (uses Supabase's existing `auth.signInWithOtp(email)` flow + post-sign-in MFA-disable RPC).
- Lost-both-device-AND-email = irrecoverable, no admin-side break-glass.

### 3.8 Biometric scope

**Agent-team deliberation**:

- **security-reviewer**: the biometric attack surface is bounded — biometric is a OS-mediated gate that confirms "the same human who set up Face ID / fingerprint is at the device". It does NOT identify the user; it doesn't unlock cryptographic material; it just returns Bool (`success` / `failure`) to the app. The attack vector is "attacker holds the device under the user's nose while sleeping" — a real but bounded risk that doesn't get worse with biometric than without. **Biometric only meaningfully protects against shoulder-surfers and lost-device-with-no-PIN scenarios**, not against attackers with full device access.
- **architect**: `LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)` returns `Bool` async (returns a `Result<Void, Error>` via callback in classic API; via async/await on iOS 13+). `BiometricPrompt` on Android returns via `BiometricPrompt.AuthenticationCallback`. Both fall back to device PIN/passcode if biometric fails (configurable; we default to "allow PIN fallback" so users who can't enroll biometric can still use the feature).
- **implementer**: re-auth gate threshold lives in `Settings` (multiplatform-settings preference), keyed `biometric_reauth_threshold_seconds`, default 300 (5 min). On app foreground from background, `BiometricGuardian` checks `(now - lastBackgroundedAt) > threshold` AND `biometricEnabled = true` → fire biometric prompt; on success allow normal navigation; on failure or cancel return user to LoginScreen with current session invalidated.
- **ui-ux-designer**: opt-in surface = Settings → Privacy → 生体認証 → toggle. Default off; first enable shows a confirmation dialog explaining what it does. Sensitive-action gates (account deletion, MFA disable) fire biometric on the action button tap, not on screen entry — the user reads the warning, decides to proceed, then authenticates.
- **product-manager**: primary biometric login (= biometric unlocks a cached refresh token, no password needed) is what most users associate with "biometric" — but Supabase Auth doesn't natively bind biometric to identity, and reimplementing it requires either client-side encryption of refresh token with a biometric-protected key (Android Keystore `setUserAuthenticationRequired(true)` / iOS Keychain `kSecAccessControlBiometryCurrentSet`) OR an Edge Function that issues short-lived JWTs gated by biometric attestation. The Android path is well-trodden; the iOS Keychain path is well-trodden; the Edge Function path is bespoke. **Estimated 200-300 LOC + significant test surface**. Deferred from Phase 26.6 because (a) the value-add at alpha is low — refresh tokens already persist across foregrounding, so "biometric login" only matters when the refresh token expires (default 7 days, configurable in Supabase); (b) the failure modes (biometric template revoked OS-side / Keystore key invalidated by lock screen change / iOS Keychain wipe on passcode change) are well-known biometric-binding footguns that need dedicated test coverage.
- **knitter**: biometric re-auth on resume is a nice-to-have for knitters who use Skeinly in public (coffee shops, public transit). Sensitive-action gates are over-protective for alpha (account deletion is a confirmation-dialog gate today, which is sufficient) — but they're the gates that DO matter at marketplace time (purchase confirm, payout-method change), and shipping them now in alpha is the cost-of-now-vs-cost-of-later principle in action.

**Decision**:
- Re-auth gate on foreground past 5-min threshold (Settings configurable), opt-in via Settings → Privacy → 生体認証.
- Sensitive-action gates (alpha scope): account deletion (gates the existing ADR-005 RPC call), MFA disable (gates `auth.mfa.unenroll`).
- Sensitive-action gates (future marketplace scope): purchase confirmation, payout-method change. Wired in Phase 50+ via the same `BiometricGuardian.require(action: SensitiveAction)` API.
- Primary biometric login DEFERRED — separate ADR if real signal surfaces. The mechanism (Android Keystore biometric-protected key wrapping the refresh token, iOS Keychain with `kSecAccessControlBiometryCurrentSet`) is well-understood but the value-add at alpha is low.
- Fallback policy: allow PIN/passcode fallback by default (`.deviceOwnerAuthentication` on iOS, `BiometricPrompt.Builder.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` on Android). Settings toggle "Require biometric only (no PIN fallback)" available for advanced users.

## 4. Privacy + security recap

- **OAuth provider identity data flow**: Apple provides `user.user_metadata.name` (first + last) ONLY on first sign-in; subsequent sign-ins omit it. Apple email is private relay (`<random>@privaterelay.appleid.com`) by default; user can choose to share real email. Google provides `name` + `email` + `picture` + `email_verified` on every sign-in. Both flows store the ID token's `sub` claim (provider user ID) in `auth.identities.identity_data`, NOT in our `profiles` table. **We never see the OAuth refresh token** — Supabase manages that internally. The ID token is single-use (consumed by `signInWithIdToken` once; thereafter the Supabase session is the auth token).
- **MFA enrollment artifacts**: TOTP secret stored in Supabase `auth.mfa_factors` table (encrypted at rest with Supabase-managed key). QR code is generated client-side via the TOTP secret URI; never logged. Recovery code: 16-char base32, displayed once at enrollment, bcrypt-hashed server-side via `register_mfa_recovery_code` RPC. Plaintext never persists client-side or server-side.
- **Biometric data**: NEVER leaves the device. LocalAuthentication / BiometricPrompt return only `success` / `failure` to our app. Biometric templates are OS-managed (Secure Enclave on iOS, TEE on Android). The app never receives template data, fingerprint counts, or any biometric attribute.
- **Account deletion (ADR-005) interaction**: ADR-005's `delete_own_account` RPC cascades via `auth.users → identities + mfa_factors + sessions`. The new `mfa_recovery_codes` table has `ON DELETE CASCADE` on `user_id` so it also cleans up. Phase 27 data-wipe (account-preserved) does NOT wipe identities or MFA factors (auth-side rows survive a data wipe — see Phase 27 ADR-023 §3.1 preservation matrix).
- **Privacy policy updates (Phase 26.7)**:
  - New `<h3>` subsection: "OAuth Sign-In (Apple, Google)" — declares what identity data each provider sends, the relay-email semantic for Apple, that ID tokens are single-use, and that revocation is OS-mediated.
  - New `<h3>` subsection: "Multi-Factor Authentication" — declares TOTP secret storage location (Supabase managed encrypted), recovery code one-time display + hash-only server storage, lost-device flow, lost-both = irrecoverable disclosure.
  - New `<h3>` subsection: "Biometric Authentication" — declares biometric data never leaves device, app receives only success/failure, OS-managed templates.
  - JA mirror for all three subsections at `docs/public/ja/privacy-policy/index.html`.

## 5. Sub-slice plan

7 implementation sub-slices, each independently shippable. **All 7 must ship before TestFlight + Play Internal Testing tester invites go out** per HARD-GATE positioning.

### Phase 26.0 — ADR cut (this slice)

- This ADR (`docs/en/adr/022-phase-26-auth-infrastructure.md`) + JA summary.
- CLAUDE.md promotion to active roadmap (handled by parent session after both Phase 26 + Phase 27 ADRs return).
- Zero code, zero tests, zero migrations, zero i18n.

### Phase 26.1 — iOS Apple Sign-In wiring

**Ships**:
- `iosApp/iosApp/iosApp.entitlements`: add `com.apple.developer.applesignin` key with `["Default"]` array value (alongside existing `aps-environment` and `com.apple.developer.associated-domains`).
- `iosApp/iosApp/Screens/LoginScreen.swift`: replace top of form with `SignInWithAppleButton(.signIn, onRequest: ..., onCompletion: ...)`. `onRequest` sets `request.requestedScopes = [.fullName, .email]` + sets `request.nonce = sha256(nonce)`. `onCompletion` extracts `ASAuthorizationAppleIDCredential.identityToken`, decodes to String, forwards to KoinHelper bridge.
- New `iosApp/iosApp/Core/Bridging/AppleSignInBridge.swift`: SwiftUI ↔ Kotlin bridge that calls `KoinHelperKt.signInWithAppleIdToken(idToken, nonce)`.
- New commonMain expect: `expect class OAuthClient { suspend fun acquireAppleIdToken(): OAuthIdTokenResult; suspend fun acquireGoogleIdToken(): OAuthIdTokenResult }`. iOS actual is a no-op for Apple (acquired in Swift); Phase 26.2 lands the Google iOS actual.
- New `AuthRepository.signInWithApple(idToken, nonce)` method + `AuthRepositoryImpl` body using `client.auth.signInWith(IDToken) { idToken = ...; provider = Provider.Apple; nonce = ... }`.
- Domain-state extension: new `AuthState.LinkIdentityRequired(email, pendingIdToken, provider)` data class.
- Supabase Auth Dashboard: enable Apple provider with Services ID + Team ID + Key ID + `.p8` blob (user-side action; not autonomously executable).

**i18n keys added** (en + ja + xcstrings): `action_sign_in_with_apple`, `label_or_sign_in_with_email`, `body_email_already_used_oauth_link_prompt`. ~3 new keys.

**Tests added**: 12 commonTest. `AuthRepositoryImplTest`:
- `signInWithApple invokes signInWith(IDToken) with Apple provider`.
- `signInWithApple propagates supabase user_already_exists error to LinkIdentityRequired state`.
- `signInWithApple propagates other supabase errors to Error state`.
- `signInWithApple short-circuits when supabaseClient null` (local-dev path).
- Apple-nonce flow integration tests (4 cases covering nonce-mismatch / valid-nonce / nil-nonce-rejected / replay-nonce-rejected, server-side enforced — these are MockEngine-level tests asserting we pass `nonce` correctly).
- `LinkIdentityRequired` state-transition tests (4 cases: incoming + dismiss + resolve-via-password + resolve-via-linkIdentity).

**Release-secrets added** (1 new secret in release-secrets.md EN + JA):
- `APPLE_SIGNIN_KEY_P8` + `APPLE_SIGNIN_KEY_ID` + `APPLE_SIGNIN_SERVICES_ID` — Apple Developer Portal → Keys → "+" → Sign In with Apple. SEPARATE `.p8` from the existing APNs `.p8`. Registered to Supabase Auth Dashboard, not to a GitHub Secret slot (the Supabase Dashboard provider config holds the key; no CI invocation needs it).

**Operator-side actions**:
1. Apple Developer Portal → Identifiers → "+" → Services IDs → create `io.github.b150005.skeinly.signin` (different from app bundle ID) → enable Sign In with Apple → configure return URL = `<supabase_project_url>/auth/v1/callback`.
2. Apple Developer Portal → Keys → "+" → Sign In with Apple → associate with Primary App ID = `io.github.b150005.skeinly` → download `.p8` (single download, like APNs `.p8`).
3. Supabase Dashboard → Authentication → Providers → Apple → enable → paste Services ID + Team ID + Key ID + `.p8` blob.
4. Verify by signing in with a sandbox Apple ID on the iOS simulator after Phase 26.1 ships.

### Phase 26.2 — Android Google Sign-In wiring

**Ships**:
- `androidApp/build.gradle.kts` deps: `androidx.credentials:credentials:1.3.0`, `androidx.credentials:credentials-play-services-auth:1.3.0`, `com.google.android.libraries.identity.googleid:googleid:1.1.1`.
- New `androidApp/.../auth/GoogleSignInActivityResultProvider.kt`: hosts the `CredentialManager` instance + exposes a suspend `getGoogleIdToken(): GoogleIdTokenCredential` method that builds a `GetCredentialRequest` with `GetGoogleIdOption.Builder()` configured for the Android client ID.
- `OAuthClient.android.kt` actual: `acquireGoogleIdToken()` delegates to the activity-result provider via Koin.
- New `AuthRepository.signInWithGoogle(idToken, nonce?)` method.
- Compose `LoginScreen.kt` (shared composable): add `OutlinedButton` styled with Google G logo + "Continue with Google" label, fires `viewModel.onEvent(AuthEvent.SignInWithGoogle)`.
- Supabase Auth Dashboard: enable Google provider with Web client ID + Web client secret + Android client ID + iOS client ID.

**Note**: this slice ALSO lands the **Google-on-iOS** wiring because both platforms share the `OAuthClient` abstraction. iOS actual uses `GIDSignIn` Swift SDK:
- New `iosApp/Podfile` or `iosApp/Package.swift` dep: `googlesignin-ios` (currently 8.x).
- iOS LoginScreen.swift: secondary `Button` "Continue with Google" → calls into Swift bridge `GoogleSignInBridge.swift` → `GIDSignIn.sharedInstance.signIn(withPresenting: rootViewController)` → extracts `result.user.idToken.tokenString` → forwards to `KoinHelperKt.signInWithGoogleIdToken(idToken)`.

**i18n keys added**: `action_sign_in_with_google`, `action_continue_with_google`. ~2 new keys.

**Tests added**: 14 commonTest.
- `signInWithGoogle invokes signInWith(IDToken) with Google provider`.
- `signInWithGoogle nonce optional path`.
- `signInWithGoogle propagates user_already_exists to LinkIdentityRequired`.
- `signInWithGoogle short-circuits when supabaseClient null`.
- 10 `AuthViewModel` integration tests covering button-tap → ID-token-acquire → Supabase-sign-in → state-transitions across happy / link-required / cancel / error paths.

**Release-secrets added** (3 new entries in release-secrets.md):
- `GOOGLE_OAUTH_WEB_CLIENT_ID` — Google Cloud Console OAuth 2.0 client of type "Web application". Used by Supabase Dashboard provider config.
- `GOOGLE_OAUTH_IOS_CLIENT_ID` — Google Cloud Console OAuth 2.0 client of type "iOS". Embedded in `GoogleService-Info.plist` (already shipped via Phase 24 — verify Phase 26.2 prereq is that the existing GoogleService-Info.plist has `CLIENT_ID` set; if not, regenerate via Firebase Console → Project Settings → Add iOS app → fill OAuth client info).
- `GOOGLE_OAUTH_ANDROID_CLIENT_ID` — Google Cloud Console OAuth 2.0 client of type "Android". Tied to package name + SHA-1 fingerprint; passed to `GetGoogleIdOption.Builder().setServerClientId(WEB_CLIENT_ID)` (Android client ID is implicit in the package+SHA-1 match; Web client ID is the explicit parameter).

**Operator-side actions**:
1. Google Cloud Console → APIs & Services → Credentials → "+ CREATE CREDENTIALS" → OAuth client ID → Web application → name `Skeinly Web` → Authorized redirect URIs = `<supabase_project_url>/auth/v1/callback` → create → note Client ID + Client Secret.
2. Repeat for iOS application → name `Skeinly iOS` → Bundle ID = `io.github.b150005.skeinly` → note Client ID.
3. Repeat for Android application → name `Skeinly Android` → Package name = `io.github.b150005.skeinly` → SHA-1 fingerprint (debug + release).
4. Supabase Dashboard → Authentication → Providers → Google → enable → paste Web Client ID + Web Client Secret. Skipping_nonce_checks = false.

### Phase 26.3 — Account-merge `linkIdentity` flow

**Ships**:
- `AuthRepositoryImpl`: catch `user_already_exists` from `signInWithIdToken`, surface `AuthState.LinkIdentityRequired(email, pendingIdToken, provider)`.
- `LoginScreen` Compose + SwiftUI: on `LinkIdentityRequired` state, render inline form: "<email> is already used by an existing Skeinly account. Sign in with your password to link your <provider> account." + password field + submit button + dismiss button.
- New `AuthRepository.linkPendingIdentity(pendingIdToken, provider)` method that calls `client.auth.linkIdentity(provider) { idToken = pendingIdToken }`.
- New `AuthEvent.SubmitLinkIdentity(password)` + `AuthEvent.DismissLinkIdentity` events.
- `AuthViewModel`: handle the events; on submit, sign-in-with-email-first, on success call `linkPendingIdentity`, on success the user is signed in with both identities attached.

**i18n keys added**: `title_link_identity`, `body_email_already_used_link_prompt`, `action_link_identity`, `action_dismiss_link_identity`, `state_linking_identity`. ~5 new keys.

**Tests added**: 18 commonTest.
- `AuthRepositoryImplTest.linkPendingIdentity success` + `failure` + `null-client`.
- `AuthViewModel link-identity full state-machine`: incoming → password-submit-success → identity-link-success → Authenticated.
- `AuthViewModel link-identity password-fails` → stays on LinkIdentityRequired with error.
- `AuthViewModel link-identity identity-link-fails` → stays on Authenticated (password sign-in succeeded; identity link can be retried from Settings).
- `AuthViewModel dismiss-link-identity` → returns to Unauthenticated, drops pending ID token (zero retention).
- `linkIdentity preserves account email-relay status for Apple`.
- 11 more permutation tests covering provider-A + identity-already-linked / pending-ID-token-expired / wrong-password-3x-lockout / etc.

### Phase 26.4 — TOTP MFA enrollment + challenge + recovery

**Ships**:
- Migration 033 `mfa_recovery_codes`:
  ```sql
  CREATE TABLE public.mfa_recovery_codes (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
      code_hash TEXT NOT NULL,  -- bcrypt
      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      consumed_at TIMESTAMPTZ
  );
  CREATE UNIQUE INDEX idx_mfa_recovery_codes_user_active
      ON public.mfa_recovery_codes (user_id) WHERE consumed_at IS NULL;
  ALTER TABLE public.mfa_recovery_codes ENABLE ROW LEVEL SECURITY;
  -- own-row SELECT only (read consumed_at to verify state); no INSERT/DELETE from client.
  CREATE POLICY own_recovery_codes_select ON public.mfa_recovery_codes
      FOR SELECT USING (user_id = auth.uid());
  ```
- Migration 033 RPCs: `register_mfa_recovery_code(p_code_hash TEXT)` SECURITY DEFINER + `consume_mfa_recovery_code(p_code TEXT)` SECURITY DEFINER. Both `search_path = public, pg_temp` locked.
- New `AuthRepository` methods: `enrollMfaTotp(): MfaEnrollmentResult` (returns `{ secret, qrCodeUri, recoveryCode }`), `verifyMfaChallenge(challengeId, code)`, `disableMfa(currentCode)`, `regenerateRecoveryCode(currentCode)`, `consumeRecoveryCode(recoveryCode)`.
- New `MfaEnrollmentScreen` + `MfaChallengeScreen` + `MfaRecoveryCodeScreen` Compose + SwiftUI mirrors.
- Settings → Privacy → 2 要素認証 row → entry to enrollment flow.
- LoginScreen: post-password-sign-in if `auth.mfa.listFactors()` returns verified factor → route to MfaChallengeScreen → on success Supabase elevates session to AAL2.

**i18n keys added**: `title_mfa_enroll`, `body_mfa_enroll_scan_qr`, `label_mfa_manual_secret`, `action_mfa_verify`, `title_mfa_recovery_code`, `body_mfa_recovery_code_warning`, `action_mfa_recovery_code_saved`, `title_mfa_challenge`, `body_mfa_challenge_prompt`, `action_mfa_use_recovery_code`, `title_mfa_disable_confirm`, `body_mfa_disable_warning`, `state_mfa_enabled`, `state_mfa_disabled`. ~14 new keys.

**Tests added**: 28 commonTest.
- `AuthRepositoryImplTest`: 12 cases covering enroll-success / enroll-failure / verify-success / verify-wrong-code / verify-stale-challenge / disable-success / disable-wrong-code / regenerate-success / regenerate-wrong-code / consume-success / consume-already-used / consume-wrong-code.
- `MfaEnrollmentViewModelTest`: 8 cases covering full state machine.
- `MfaChallengeViewModelTest`: 8 cases covering challenge / verify / recovery-fallback / 3-failure-lockout.

**Operator-side actions**: none — Supabase Auth MFA is enabled by default in Supabase project settings. Verify Authentication → Settings → "Enable MFA" is on.

### Phase 26.5 — Biometric re-auth + sensitive-action gates

**Ships**:
- `androidApp/build.gradle.kts` dep: `androidx.biometric:biometric:1.2.0-alpha05` (latest stable; biometric 1.2.0 is alpha but widely used in production).
- New commonMain expect: `expect class BiometricAuthenticator { suspend fun authenticate(reason: String): BiometricResult; fun canAuthenticate(): BiometricAvailability }`.
- Android actual: `BiometricPrompt.PromptInfo.Builder` wired via `FragmentActivity` (MainActivity is a `ComponentActivity` which extends `FragmentActivity`).
- iOS actual: `LAContext.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)` via `suspendCancellableCoroutine`.
- New `BiometricGuardian` shared service: `requireForResume()` (re-auth gate on foreground past threshold), `requireForAction(SensitiveAction)` (sensitive-action gate).
- Wire `requireForResume()` from app-lifecycle observer (commonMain) — fires on transition from background to foreground.
- Wire `requireForAction(SensitiveAction.AccountDeletion)` in `DeleteAccountUseCase`.
- Wire `requireForAction(SensitiveAction.MfaDisable)` in `disableMfa` path.
- New `BiometricSettingsScreen` (Compose + SwiftUI mirror): toggle on/off + threshold picker (1 min / 5 min / 15 min / 1 hr).
- Settings → Privacy → 生体認証 row → entry to settings screen.

**i18n keys added**: `title_biometric_settings`, `state_biometric_enabled`, `state_biometric_disabled`, `label_biometric_threshold_picker`, `value_biometric_threshold_1m`, `value_biometric_threshold_5m`, `value_biometric_threshold_15m`, `value_biometric_threshold_1h`, `body_biometric_reauth_reason`, `body_biometric_account_deletion_reason`, `body_biometric_mfa_disable_reason`, `label_biometric_fallback_pin_allowed`, `state_biometric_unavailable_no_hw`, `state_biometric_unavailable_not_enrolled`. ~14 new keys.

**Tests added**: 16 commonTest.
- `BiometricGuardianTest`: 8 cases covering threshold-evaluation / fresh-foreground / cold-resume / disabled-globally / canAuthenticate-No-Hardware / canAuthenticate-Not-Enrolled / sensitive-action-success / sensitive-action-cancel.
- `BiometricSettingsViewModelTest`: 8 cases covering toggle / threshold-edit / canAuthenticate-checks / persist-to-settings.

**Operator-side actions**: none — biometric is a client-only concern.

### Phase 26.6 — Onboarding integration + display name + avatar handling

**Ships**:
- Onboarding flow extension: after OAuth sign-in succeeds (`Authenticated` state for the first time), check if `profiles.display_name` is null → if null AND `user.user_metadata.name` is present → pre-fill the existing onboarding "What should we call you?" screen.
- Avatar import: if `user.user_metadata.picture` URL is present (Google only), show in onboarding "Use this picture as your avatar?" with one-tap import + one-tap "Choose different".
- New `ImportOAuthAvatarUseCase`: downloads the picture URL → uploads to existing Supabase Storage `avatars` bucket → updates `profiles.avatar_url`.
- Settings → Account: show "Signed in as <relay-email> via Apple — using Apple's private email relay" / "Signed in as <email> via Google" / "Signed in as <email>" depending on provider. Tap shows full identity list (multiple providers if linked).

**i18n keys added**: `body_onboarding_oauth_name_prefill`, `action_use_oauth_avatar`, `action_choose_different_avatar`, `state_signed_in_via_apple_relay`, `state_signed_in_via_google`, `state_signed_in_via_email`, `label_linked_identities`. ~7 new keys.

**Tests added**: 18 commonTest.
- `ImportOAuthAvatarUseCaseTest`: 6 cases covering picture-URL-present / picture-URL-absent / download-fail / upload-fail / profile-update-fail / happy-path.
- `OnboardingViewModelTest` extension: 6 cases covering OAuth-name-prefill / no-name-fallback / name-edited-by-user / avatar-import-tap / avatar-decline-tap / no-avatar-source.
- Settings identity-list 6 cases covering single-identity / multi-identity / Apple-relay-label / Google-label / email-label / linked-identities-list-collapse.

### Phase 26.7 — Privacy policy update + smoke test

**Ships**:
- `docs/public/privacy-policy/index.html` (EN): add 3 new `<h3>` subsections inside "Information We Collect" — "OAuth Sign-In (Apple, Google)", "Multi-Factor Authentication", "Biometric Authentication".
- `docs/public/ja/privacy-policy/index.html` (JA mirror): same 3 subsections in Japanese.
- A0d-6 Data Safety vendor-setup checklist: update the operator-side recipe to re-submit Data Safety with account-creation methods = email/password + OAuth (Apple, Google) + MFA, after Phase 26.7 closes.
- End-to-end smoke test recipe at `docs/en/ops/auth-smoke-test.md`: 
  - Apple Sign-In on real iOS device with sandbox Apple ID.
  - Google Sign-In on real Android device + real iOS device.
  - Email/password sign-in still works.
  - Account-merge flow: sign up email/password → sign out → sign in with same email via Google → see LinkIdentityRequired → enter password → see linked.
  - TOTP enrollment + challenge + recovery flow.
  - Biometric re-auth on foreground past threshold.
  - Account deletion gate fires biometric.
  - MFA disable gate fires biometric.

**i18n keys added**: 0 (privacy policy is HTML-only).

**Tests added**: 0 commonTest delta (docs + smoke-test recipe).

**Operator-side actions**:
1. Smoke test per `auth-smoke-test.md` on both platforms.
2. Re-submit Play Data Safety after smoke-test passes (account-creation methods update).
3. Re-submit App Store privacy nutrition facts (same update).

## 6. Estimated commonTest delta across 26.1-26.7

| Sub-slice | commonTest added |
|---|---|
| 26.1 Apple Sign-In wiring | 12 |
| 26.2 Google Sign-In wiring | 14 |
| 26.3 linkIdentity flow | 18 |
| 26.4 TOTP MFA | 28 |
| 26.5 Biometric | 16 |
| 26.6 Onboarding integration | 18 |
| 26.7 Privacy + smoke test | 0 |
| **Total** | **106** |

Baseline today: 1536 commonTest (per CLAUDE.md Phase 39 W5b entry). Post-Phase-26 baseline: ~1642 commonTest.

## 7. Alternatives considered

### A1. Passkey support (FIDO2 / WebAuthn)

Passkeys are Apple + Google's strategic direction for password replacement, gradually rolling out across both platforms in 2024-2026. Supabase Auth does NOT currently support WebAuthn as a native auth method (only via the third-party Supabase Auth Helpers + a custom Edge Function bridge).

**Why NOT in scope for Phase 26**:
- Supabase support is not native — adding it requires either rolling our own WebAuthn server-side OR waiting for Supabase native support. Native is likely a 2026-late or 2027-early item per Supabase's public roadmap.
- Phase 26 OAuth + TOTP cover the primary auth modalities. Adding Passkey is a third path, not a replacement.
- Cross-platform Passkey portability story is still in flux (Apple iCloud Keychain Passkeys + Google Password Manager Passkeys interoperate via the WebAuthn standard, but the platform UX is divergent).
- Marketplace context: Passkey is a strict security improvement, but our marketplace seller/creator population will skew technical enough to use TOTP via 1Password / Authy; Passkey-first would close out users without a Passkey-supporting platform.

**Reopen**: post-Phase-40 GA, if Supabase ships native Passkey support OR if a real user-demand signal surfaces.

### A2. SMS-based MFA

SMS-OTP MFA is the most familiar consumer-grade MFA modality but is also the weakest:
- SIM-swap attacks (well-documented Twitter/X CEO and 50K+ consumer cases).
- SS7 protocol vulnerabilities (network-level interception).
- Cost: ~$0.01-$0.05 per SMS, scales with user base.

**Why NOT in scope**: TOTP is strictly stronger and adds no per-MFA cost. Knitters skew toward 1Password / iCloud Keychain / Google Password Manager users (Pew 2024 cohort data) — TOTP is familiar to them.

**Never reopened**: would be a strict downgrade.

### A3. Hardware security key (FIDO U2F / FIDO2)

YubiKey + similar. Adds a per-user hardware cost ($25-50/key) AND requires the user to physically have the key with them.

**Why NOT in scope**: Skeinly is a mobile-only app. The user has their phone for primary auth + has the phone for biometric. A separate hardware key for a craft app is over-engineering at any user scale we'll see this decade.

**Never reopened** (unless an enterprise / B2B Skeinly-Pro tier ever ships).

### A4. Custom auth UI hosted in WebView

Some apps roll their own Apple/Google sign-in UI inside a `WKWebView` / Android WebView to control the look. Apple HIG explicitly forbids this — Sign in with Apple MUST use the native `SignInWithAppleButton`. Material Design strongly discourages WebView for Google Sign-In.

**Never reopened**: HIG violation.

### A5. Phone-number + SMS-OTP sign-in (Supabase native)

Supabase Auth supports phone-number primary auth via SMS-OTP. Compared to email/password it has the strength of "no password to remember" but the weakness of "tied to a phone number which can be SIM-swapped".

**Why NOT in scope**: adds an additional auth modality that overlaps with both email (account recovery) and biometric (frictionless re-auth). The combined user-mental-model of email + OAuth + TOTP + biometric is already at the cap of what an alpha user will tolerate; adding phone-number-as-identity fragments the recovery story.

**Reopen**: post-Phase-40 GA if a market segment surfaces where SMS-primary is the dominant identity (e.g. emerging-market users without email accounts).

### A6. Magic-link primary sign-in

Some auth-first apps (Notion, Linear, Slack) default to magic-link sign-in instead of password. Supabase Auth supports it via `signInWithOtp(email)`.

**Why NOT in scope for primary path**: email round-trip friction at every sign-in is high. Magic link works for OUR lost-device MFA recovery flow (Phase 26.4) but using it as the primary path for alpha would dramatically slow down the test loop.

**Reopen**: post-Phase-40 GA if user testing shows password-rememberability is a top-3 friction.

### A7. Skip OAuth entirely; ship alpha with email/password only

Original Phase 26 scope per CLAUDE.md prior to 2026-05-14: post-Phase-39-beta polish. Operator surfaced two pressures (A0d-6 declarative answer + future marketplace retrofit cost) that flipped the call to HARD-GATE alpha-launch.

**Why NOT skipped**: Skipping means (a) A0d-6 locks in "email/password only" declarative answer at alpha, forcing re-submission for OAuth additions later, (b) marketplace retrofit asks every existing user to enroll in stronger auth — fragile trust moment, (c) AI agents do the work, the cost-of-now is not a scarce resource.

**Never reopened**: settled by the user policy shift 2026-05-14.

### A8. Account-merge auto-merge (single-email = single-account)

The simplest UX: if OAuth email matches an existing user, just sign them in to that account, no friction.

**Why NOT**: see §3.3 — silent auto-merge is the canonical OAuth account-takeover vector. Hard veto on security grounds.

**Never reopened**.

### A9. Account-merge first-claim-wins

Alternative to explicit-link: if OAuth email matches an existing user, simply create a NEW account with a different internal UUID, and the user has two separate accounts.

**Why NOT**: UX dead-end. User now has two accounts, doesn't know which one has their patterns/projects, can't easily merge them post-hoc. Worse than the explicit-link friction.

**Never reopened**.

## 8. Consequences

**Positive**:
- Alpha testers see frictionless Apple/Google sign-in on day one. OAuth uptake at alpha gives directional signal on provider preference (Apple vs Google vs email) that informs marketplace seller-onboarding UX.
- Cross-platform identity portability is built-in — a tester who signs up via Apple-on-iOS can recover their account via Apple-on-Android via Custom Tabs. No "I lost my account because I switched phones" stories.
- MFA infrastructure is ready for marketplace seller-onboarding mandatory enforcement at Phase 50+ without retrofit. Existing alpha + beta + GA users who opted in get a head start; everyone else gets prompted at role transition.
- Biometric infrastructure surfaces a cleaner sensitive-action UX than confirmation dialogs alone (account deletion, MFA disable). Reused for marketplace purchase confirm + payout-method change at Phase 50+.
- The 4-provider × 2-platform OAuth path tests both `signInWithIdToken` paths at alpha scale, surfacing supabase-kt 3.6 quirks (e.g. nonce handling, identity_data shape) under low-stakes conditions before marketplace traffic.
- Privacy policy disclosure of OAuth + MFA + biometric mechanics signals security maturity to App Store / Play Console reviewers post-Phase-40 GA — pre-empts the "do you handle user identity?" questions.
- A0d-6 Data Safety re-submission post-Phase-26.7 is a one-time event that locks in the full account-creation-methods declaration upfront, not retrofitted later.

**Negative**:
- Implementation surface is real: 7 sub-slices, ~106 new commonTest cases, 4 new UI screens (MfaEnrollment / MfaChallenge / MfaRecoveryCode / BiometricSettings), expanded LoginScreen on both platforms, expanded onboarding flow, expanded Settings → Privacy.
- New Apple Developer Portal dependency: Services ID + separate `.p8` for Apple Sign-In (different from existing APNs `.p8`).
- New Google Cloud Console dependency: 3 OAuth 2.0 clients (Web + iOS + Android), each tied to specific Bundle ID / Package Name / SHA-1 fingerprint. Re-issuing these at credential rotation has more moving parts than the single Apple `.p8`.
- New Supabase Auth provider config in Dashboard (Apple + Google) — both require manual operator-side Dashboard config that is NOT migration-versioned. Mitigation: `docs/en/ops/auth-provider-setup.md` reproduces the config recipe.
- 6 new release-secrets entries (3 Apple + 3 Google) increase the credential-custody surface. Mitigation: all 6 are Dashboard-provider-config only; none embedded in client bundles; none CI-consumed.
- The lost-both-device-AND-email account-recovery dead-end is a real user-impact case. Mitigation: enrollment flow prominently warns "Save your recovery code AND remember your email."
- supabase-kt 3.6 `mfa.enroll` / `challenge` / `verify` API surface is GA but not heavily exercised by the alpha tester pool we know — risk of supabase-kt-side bugs. Mitigation: 28 dedicated MFA commonTest cases + dedicated alpha-tester smoke-test recipe in Phase 26.7.
- The `mfa_recovery_codes` table + 2 RPCs add server surface that needs RLS + search_path lock + bcrypt computation (bcrypt is `pgcrypto` extension — already enabled per migrations 030/031). Mitigation: migration 033 follows the established RLS + search_path pattern.

**Tracking**:
- Phase 26.0 = this ADR + CLAUDE.md `### Planned — Phase 26` retain (parent session promotes to active section).
- Phase 26.1 → 26.7 each ship independently as separate commits.
- HARD-GATE status: alpha-launch tester invites blocked until Phase 26.7 smoke-test passes.
- Parallel with Phase 27 (data wipe, ADR-023) — both ADRs cut same session 2026-05-14.

## 9. References

- ADR-005 (account deletion RPC — auth.users cascade chain that mfa_recovery_codes joins)
- ADR-016 (subscriptions — marketplace lens informing MFA role-transition enforcement)
- ADR-017 (push notifications — KMP `expect/actual` pattern this ADR's `OAuthClient` + `BiometricAuthenticator` reuse)
- ADR-021 (UGC moderation — Settings → Privacy section that hosts MFA + Biometric controls)
- [Apple — Sign In with Apple](https://developer.apple.com/sign-in-with-apple/)
- [Apple — Implementing user authentication with Sign in with Apple](https://developer.apple.com/documentation/sign_in_with_apple/implementing_user_authentication_with_sign_in_with_apple)
- [Apple — App Store Review Guideline 4.8](https://developer.apple.com/app-store/review/guidelines/#sign-in-with-apple)
- [Google — Sign In with Google on Android (Credential Manager)](https://developers.google.com/identity/android-credential-manager)
- [Google — Sign-In for iOS (GIDSignIn)](https://developers.google.com/identity/sign-in/ios)
- [Supabase — signInWithIdToken](https://supabase.com/docs/reference/javascript/auth-signinwithidtoken)
- [Supabase — Multi-Factor Authentication](https://supabase.com/docs/guides/auth/auth-mfa)
- [Apple — LocalAuthentication framework](https://developer.apple.com/documentation/localauthentication)
- [Android — androidx.biometric](https://developer.android.com/reference/androidx/biometric/package-summary)
- [OWASP — Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
- `shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/repository/AuthRepository.kt` (existing surface this ADR extends)
- `shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/repository/AuthRepositoryImpl.kt` (existing impl this ADR extends)
- `shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/auth/AuthViewModel.kt` (existing VM this ADR extends)
- `iosApp/iosApp/iosApp.entitlements` (entitlements file gaining `com.apple.developer.applesignin`)
- `iosApp/iosApp/Screens/LoginScreen.swift` (SwiftUI screen gaining Apple + Google buttons)
- `docs/en/release-secrets.md` (release secrets registry gaining 6 new entries)
- `docs/en/vendor-setup.md` (vendor setup runbook updated at Phase 26.1 + 26.2 for operator-side Dashboard configs)

## Revision history

| Date | Status | Author | Change |
|---|---|---|---|
| 2026-05-14 | Accepted | claude-opus-4-7[1m] | Initial cut. Pre-alpha HARD-GATE designation per operator policy shift 2026-05-14. 8 decisions resolved via agent-team deliberation per CLAUDE.md "Output Quality Standard". 7 sub-slice plan (26.1–26.7) — ALL ship before alpha tester invites. Scope cuts: Passkey (A1), SMS-MFA (A2), hardware keys (A3), WebView custom UI (A4), phone-OTP (A5), magic-link primary (A6), email-only-alpha (A7), auto-merge (A8), first-claim-wins (A9). 6 new release-secrets entries. ~106 commonTest delta. 1 new migration (033 `mfa_recovery_codes`). 1 new entitlement (`com.apple.developer.applesignin`). 1 new Apple `.p8` (separate from APNs). 3 new Google Cloud OAuth 2.0 clients (Web + iOS + Android). |
