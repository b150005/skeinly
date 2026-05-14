package io.github.b150005.skeinly.domain.repository

import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.MfaEnrollment
import io.github.b150005.skeinly.domain.model.MfaEnrollmentStatus
import io.github.b150005.skeinly.domain.model.OAuthSignInOutcome
import io.github.b150005.skeinly.domain.model.SignUpOutcome
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    fun observeAuthState(): Flow<AuthState>

    suspend fun signInWithEmail(
        email: String,
        password: String,
    )

    /**
     * Creates a new Supabase auth account. Returns [SignUpOutcome] indicating
     * whether the call also created an authenticated session
     * ([SignUpOutcome.SessionCreated]) or whether Supabase deferred session
     * creation pending email confirmation
     * ([SignUpOutcome.EmailConfirmationRequired]). The UI uses this to
     * decide between waiting on `observeAuthState()` for the Authenticated
     * transition vs. surfacing a "check your email" view.
     */
    suspend fun signUpWithEmail(
        email: String,
        password: String,
    ): SignUpOutcome

    suspend fun signOut()

    suspend fun deleteAccount()

    fun getCurrentUserId(): String?

    /**
     * Sends a password-reset email to the given address. The email contains
     * a Supabase-hosted reset link that lets the user set a new password
     * via Supabase's default reset page (no in-app deep link required for
     * alpha1; Universal Links / App Links land in Phase D).
     */
    suspend fun sendPasswordResetEmail(email: String)

    /**
     * Updates the currently-authenticated user's password. Requires an
     * active session — Supabase rejects with 401 if not signed in.
     */
    suspend fun updatePassword(newPassword: String)

    /**
     * Initiates an email change for the current user. Supabase sends a
     * verification email to [newEmail]; the change does not take effect
     * until the user clicks the verification link. The current session
     * remains valid throughout.
     */
    suspend fun updateEmail(newEmail: String)

    /**
     * Phase 26.1 (ADR-022 §6.1) — verifies an Apple ID token with Supabase
     * via the `signInWith(IDToken) { provider = Apple }` path. The
     * [idToken] is the raw JWT returned by SignInWithAppleButton on iOS
     * (`ASAuthorizationAppleIDCredential.identityToken`, UTF-8 decoded);
     * [nonce] is the plaintext nonce whose `SHA-256(plaintext)` digest
     * was sent in `ASAuthorizationOpenIDRequest.nonce` and is included
     * in the ID token claims for replay protection. Supabase server-side
     * verifies the nonce matches.
     *
     * Returns [OAuthSignInOutcome.SessionCreated] on the happy path
     * (session emerges on the auth-state flow), or
     * [OAuthSignInOutcome.LinkIdentityRequired] when Supabase reports
     * the email already corresponds to an existing user that needs
     * explicit `linkIdentity` after the original credentials sign in.
     * Other failures bubble as exceptions for the use-case layer to map.
     */
    suspend fun signInWithApple(
        idToken: String,
        nonce: String,
    ): OAuthSignInOutcome

    /**
     * Phase 26.2 (ADR-022 §6.2) — verifies a Google ID token with
     * Supabase via the `signInWith(IDToken) { provider = Google }`
     * path. [idToken] is the JWT returned by Android's
     * `androidx.credentials.CredentialManager` flow (via
     * `GoogleIdTokenCredential.idToken`); [nonce] is optional — Google
     * supports the same `nonce` claim binding as Apple (recommended
     * for replay protection), but the Credential Manager flow can
     * issue tokens without a nonce when no `setNonce(...)` is set on
     * the `GetGoogleIdOption` builder.
     *
     * Returns [OAuthSignInOutcome] following the same contract as
     * [signInWithApple] — `SessionCreated` on the happy path,
     * `LinkIdentityRequired` when Supabase reports an existing
     * account under the email.
     */
    suspend fun signInWithGoogle(
        idToken: String,
        nonce: String? = null,
    ): OAuthSignInOutcome

    /**
     * Phase 26.4 (ADR-022 §6.3) — links a pending OAuth identity to the
     * currently-authenticated Supabase user via
     * `auth.linkIdentityWithIdToken(provider, pendingIdToken)`. Called
     * by the LinkIdentity resolution flow AFTER the user has signed in
     * with their original credentials (typically email/password).
     *
     * Supabase server-side verifies (a) the ID token's `nonce` claim
     * matches the supplied [nonce] (Apple replay protection), (b) the
     * ID token's email matches the current session's user email
     * (forgery protection — prevents an attacker who obtains an OAuth
     * token for the victim's address from attaching their own provider
     * identity).
     *
     * Throws on failure (expired token, email mismatch, provider
     * disabled). The caller maps the exception to a generic error
     * banner and keeps the session intact — password sign-in already
     * succeeded, so the user IS authenticated; identity link can be
     * retried from Settings.
     *
     * iOS does NOT exercise this path through any special bridge —
     * the AuthViewModel routes the call after collecting the password.
     *
     * @param provider       provider matching the pending OAuth identity
     *                       (Apple / Google).
     * @param pendingIdToken raw JWT that the failed `signInWithIdToken`
     *                       call returned, carried in the
     *                       [io.github.b150005.skeinly.domain.model.OAuthSignInOutcome.LinkIdentityRequired]
     *                       outcome.
     * @param nonce          plaintext nonce paired with the token (Apple
     *                       only; null for Google).
     */
    suspend fun linkPendingIdentity(
        provider: io.github.b150005.skeinly.domain.model.OAuthProviderKind,
        pendingIdToken: String,
        nonce: String?,
    )

    /**
     * Phase 26.x (ADR-022 §6.1) — Apple Sign-In on Android via web-OAuth
     * + Custom Tabs (supabase-kt's `signInWith(Apple)` non-IDToken path).
     * Mirrors the cross-platform symmetric-coverage decision: Apple-iOS
     * uses native `SignInWithAppleButton` + IDToken (Phase 26.1);
     * Apple-Android uses Supabase's browser-OAuth flow because Android
     * has no first-party Apple SDK.
     *
     * **Side effect, not return value**: kicks off the Custom Tabs
     * browser flow and returns immediately. The session emerges
     * asynchronously on `observeAuthState()` once the browser redirects
     * back to `skeinly://auth-callback` and `MainActivity.onNewIntent`
     * calls `supabase.auth.handleDeeplinks(intent)`. The caller MUST
     * NOT await a `OAuthSignInOutcome` here — the success path is
     * observed via the auth-state flow, the cancel/error path is
     * silent (user closed the Custom Tab without completing).
     *
     * iOS callers should NOT invoke this — the iOS Apple path goes
     * through the native `signInWithApple(idToken, nonce)` IDToken
     * bridge from Phase 26.1. Surface here is platform-agnostic for
     * KMP cohesion; the Android Compose LoginScreen is the only
     * production consumer.
     */
    suspend fun signInWithAppleViaWebOAuth()

    /**
     * Phase 26.5 (ADR-022 §6.4) — observe the caller's MFA enrollment
     * status. The flow re-emits whenever the session changes or the
     * factor list mutates (enroll / unenroll / verify). Settings →
     * Security mirrors this flow to keep the row state in sync without
     * polling.
     *
     * Returns [MfaEnrollmentStatus.NotEnrolled] when the supabase
     * client is unconfigured (local-only dev builds) so callers
     * degrade gracefully — the Settings entry simply offers "Enable
     * 2FA" which fails fast on tap.
     */
    fun observeMfaStatus(): Flow<MfaEnrollmentStatus>

    /**
     * Phase 26.5 (ADR-022 §6.4) — enrolls a new TOTP factor for the
     * currently-authenticated user, generates a 16-char recovery code
     * client-side, computes its bcrypt hash via the
     * `hash_recovery_code` RPC, then registers the hash via the
     * `register_mfa_recovery_code` RPC. Returns the full
     * [MfaEnrollment] envelope (factor ID + base32 secret + otpauth
     * URI + recovery code plaintext) for one-time display.
     *
     * The TOTP factor is created in an unverified state — the caller
     * must follow up with [verifyMfaEnrollment] using a code from the
     * user's authenticator app to complete enrollment.
     */
    suspend fun enrollMfaTotp(): MfaEnrollment

    /**
     * Phase 26.5 (ADR-022 §6.4) — verifies a TOTP code against the
     * just-enrolled factor, completing the enrollment. Internally
     * creates a challenge via Supabase's `auth.mfa.createChallenge`
     * then immediately verifies it via `auth.mfa.verifyChallenge`.
     * Throws on bad code / expired window / network failure; the
     * caller maps the exception to a banner and stays on the
     * enrollment screen.
     */
    suspend fun verifyMfaEnrollment(
        factorId: String,
        code: String,
    )

    /**
     * Phase 26.5 (ADR-022 §6.4) — post-password-sign-in TOTP gate.
     * Creates a fresh challenge on the verified factor, verifies the
     * supplied [code], and elevates the session AAL to AAL2. Throws
     * on bad code / expired challenge / network — caller stays on
     * MfaChallengeScreen.
     *
     * Idempotent on factorId discovery: callers do not pass the
     * factor ID; the repository resolves the verified factor itself
     * via `auth.mfa.retrieveFactorsForCurrentUser`. Throws
     * [IllegalStateException] if no verified factor exists (which
     * would be a bug — only callers that reached this code path went
     * through the [observeAuthState] gate where MfaChallengeRequired
     * was emitted, which already proved a verified factor exists).
     */
    suspend fun submitMfaChallenge(code: String)

    /**
     * Phase 26.5 (ADR-022 §6.4) — recovery-code bypass path. Bcrypt-
     * verifies the plaintext against the stored hash via the
     * `consume_mfa_recovery_code` RPC. On match: unenrolls the TOTP
     * factor via `auth.mfa.unenroll` (the recovery code is a "reset
     * MFA enrollment" bypass, NOT a permanent AAL2 elevation; the
     * user re-enrolls TOTP from scratch via Settings → Security after
     * regaining access).
     *
     * Returns true on success, false on wrong / consumed / no-row
     * code. Caller maps to UI state — Settings shows "MFA disabled"
     * + offers re-enrollment.
     */
    suspend fun consumeRecoveryCode(plaintextCode: String): Boolean

    /**
     * Phase 26.5 (ADR-022 §6.4) — drops the TOTP factor without
     * recovery-code bypass. Used when the user explicitly chooses to
     * disable 2FA from Settings → Security (requires current TOTP
     * code to gate the action — passed via [verifyMfaEnrollment]'s
     * verify path before this call).
     */
    suspend fun disableMfa(factorId: String)

    /**
     * Phase 26.5 (ADR-022 §6.4) — regenerates the recovery code while
     * the user retains TOTP access. Generates a fresh 16-char code,
     * bcrypt-hashes it server-side via `hash_recovery_code`, replaces
     * the active row via `register_mfa_recovery_code`. Returns the
     * new plaintext for one-time display on the regen screen.
     *
     * Caller must gate this with a fresh TOTP verify in the UI layer
     * — server-side does NOT re-verify TOTP; the threat model
     * presumes an attacker with an active AAL2 session is a
     * compromise regardless of which mutation they perform.
     */
    suspend fun regenerateRecoveryCode(): String
}
