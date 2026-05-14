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
}
