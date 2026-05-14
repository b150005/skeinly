package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.OAuthSignInOutcome
import io.github.b150005.skeinly.domain.model.SignUpOutcome
import io.github.b150005.skeinly.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAuthRepository : AuthRepository {
    private val authStateFlow = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    var signInError: Throwable? = null
    var signUpError: Throwable? = null
    var signOutError: Throwable? = null
    var deleteAccountError: Throwable? = null
    var sendPasswordResetError: Throwable? = null
    var updatePasswordError: Throwable? = null
    var updateEmailError: Throwable? = null
    var signInWithAppleError: Throwable? = null
    var signInWithGoogleError: Throwable? = null

    /**
     * Phase 26.1 / 26.2 — when non-null, the next [signInWithApple] /
     * [signInWithGoogle] call returns this outcome instead of
     * [OAuthSignInOutcome.SessionCreated]. Tests override per-case
     * to model the LinkIdentityRequired path.
     */
    var signInWithAppleOutcome: OAuthSignInOutcome? = null
    var signInWithGoogleOutcome: OAuthSignInOutcome? = null

    var lastAppleIdToken: String? = null
    var lastAppleNonce: String? = null
    var lastGoogleIdToken: String? = null
    var lastGoogleNonce: String? = null

    /**
     * Determines whether the next `signUpWithEmail` call returns
     * [SignUpOutcome.SessionCreated] (default — Confirm email disabled on
     * the Supabase dashboard, immediate session) or
     * [SignUpOutcome.EmailConfirmationRequired] (Confirm email enabled,
     * deferred-confirmation path). Tests override per-case.
     */
    var signUpEmailConfirmationRequired: Boolean = false

    /**
     * When true, the next `signUpWithEmail` call returns
     * [SignUpOutcome.AlreadyRegistered]. Models the Supabase production
     * security-by-obscurity case: HTTP 200 OK + empty identities array
     * when the email already corresponds to an existing user. Takes
     * priority over [signUpEmailConfirmationRequired] when both are set.
     */
    var signUpEmailAlreadyRegistered: Boolean = false
    var lastPasswordResetEmail: String? = null
    var lastUpdatedPassword: String? = null
    var lastUpdatedEmail: String? = null
    private var currentUserId: String? = null

    override fun observeAuthState(): Flow<AuthState> = authStateFlow

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ) {
        signInError?.let { throw it }
        currentUserId = "test-user-id"
        authStateFlow.value = AuthState.Authenticated(userId = "test-user-id", email = email)
    }

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
    ): SignUpOutcome {
        signUpError?.let { throw it }
        return when {
            signUpEmailAlreadyRegistered ->
                // Models Supabase 200 OK + empty identities for an email
                // that already exists in auth.users. NEVER auto-authenticates
                // — the legitimate owner must go through sign-in.
                SignUpOutcome.AlreadyRegistered(email = email)
            signUpEmailConfirmationRequired ->
                // Account row exists in auth.users but session is NOT
                // created until the user clicks the email confirmation
                // link. Matches production behavior when Confirm email
                // is enabled on the dashboard.
                SignUpOutcome.EmailConfirmationRequired(email = email)
            else -> {
                currentUserId = "new-user-id"
                authStateFlow.value = AuthState.Authenticated(userId = "new-user-id", email = email)
                SignUpOutcome.SessionCreated
            }
        }
    }

    override suspend fun signOut() {
        signOutError?.let { throw it }
        currentUserId = null
        authStateFlow.value = AuthState.Unauthenticated
    }

    override suspend fun deleteAccount() {
        deleteAccountError?.let { throw it }
        currentUserId = null
        authStateFlow.value = AuthState.Unauthenticated
    }

    override fun getCurrentUserId(): String? = currentUserId

    override suspend fun sendPasswordResetEmail(email: String) {
        sendPasswordResetError?.let { throw it }
        lastPasswordResetEmail = email
    }

    override suspend fun updatePassword(newPassword: String) {
        updatePasswordError?.let { throw it }
        lastUpdatedPassword = newPassword
    }

    override suspend fun updateEmail(newEmail: String) {
        updateEmailError?.let { throw it }
        lastUpdatedEmail = newEmail
    }

    override suspend fun signInWithApple(
        idToken: String,
        nonce: String,
    ): OAuthSignInOutcome {
        lastAppleIdToken = idToken
        lastAppleNonce = nonce
        signInWithAppleError?.let { throw it }
        val override = signInWithAppleOutcome
        if (override != null) return override
        currentUserId = "apple-user-id"
        authStateFlow.value =
            AuthState.Authenticated(userId = "apple-user-id", email = "apple@example.com")
        return OAuthSignInOutcome.SessionCreated
    }

    override suspend fun signInWithGoogle(
        idToken: String,
        nonce: String?,
    ): OAuthSignInOutcome {
        lastGoogleIdToken = idToken
        lastGoogleNonce = nonce
        signInWithGoogleError?.let { throw it }
        val override = signInWithGoogleOutcome
        if (override != null) return override
        currentUserId = "google-user-id"
        authStateFlow.value =
            AuthState.Authenticated(userId = "google-user-id", email = "google@example.com")
        return OAuthSignInOutcome.SessionCreated
    }

    /**
     * Phase 26.x — call-counter for the Android web-OAuth Apple path.
     * Production fires a Custom Tab; the fake just records the call so
     * the ViewModel can verify it routed the event correctly. Session
     * emergence is simulated by setting [authStateFlow] in test code
     * post-call (mirrors the real `handleDeeplinks` callback path).
     */
    var signInWithAppleViaWebOAuthCallCount: Int = 0
    var signInWithAppleViaWebOAuthError: Throwable? = null

    override suspend fun signInWithAppleViaWebOAuth() {
        signInWithAppleViaWebOAuthCallCount++
        signInWithAppleViaWebOAuthError?.let { throw it }
    }

    fun setAuthState(state: AuthState) {
        authStateFlow.value = state
        currentUserId =
            when (state) {
                is AuthState.Authenticated -> state.userId
                else -> null
            }
    }
}
