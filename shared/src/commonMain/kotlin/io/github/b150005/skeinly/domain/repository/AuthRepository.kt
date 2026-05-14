package io.github.b150005.skeinly.domain.repository

import io.github.b150005.skeinly.domain.model.AuthState
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
}
