package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.OAuthProviderKind
import io.github.b150005.skeinly.domain.model.OAuthSignInOutcome
import io.github.b150005.skeinly.domain.model.SignUpOutcome
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.event.AuthEvent
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

class AuthRepositoryImpl(
    private val supabaseClient: SupabaseClient?,
) : AuthRepository {
    // TODO: re-evaluate @SupabaseExperimental before v1.0 — auth.events is the
    // library-recommended migration target for SessionStatus.RefreshFailure.cause
    // but carries an API-stability caveat until the annotation is removed upstream.
    @OptIn(SupabaseExperimental::class)
    override fun observeAuthState(): Flow<AuthState> {
        val client = supabaseClient ?: return flowOf(AuthState.Unauthenticated)
        val auth = client.auth

        // supabase-kt 3.x deprecated `SessionStatus.RefreshFailure.cause` and routes
        // failure-cause diagnostics through a separate `auth.events` SharedFlow.
        // Combine the two so `AuthState.Error(message)` still carries the last-known
        // cause when SessionStatus.RefreshFailure fires.
        val refreshCauseFlow: Flow<RefreshFailureCause?> =
            auth.events
                .filterIsInstance<AuthEvent.RefreshFailure>()
                .map<AuthEvent.RefreshFailure, RefreshFailureCause?> { it.cause }
                .onStart { emit(null) }

        return combine(auth.sessionStatus, refreshCauseFlow) { status, latestCause ->
            when (status) {
                is SessionStatus.Authenticated ->
                    AuthState.Authenticated(
                        userId = status.session.user?.id ?: "",
                        email = status.session.user?.email,
                    )
                is SessionStatus.NotAuthenticated -> AuthState.Unauthenticated
                is SessionStatus.Initializing -> AuthState.Loading
                is SessionStatus.RefreshFailure ->
                    AuthState.Error(
                        latestCause?.toString() ?: "Session refresh failed",
                    )
            }
        }
    }

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ) {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
    ): SignUpOutcome {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        val userInfo =
            client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }

        // Supabase security-by-obscurity: when the email already
        // corresponds to an existing user, the /signup endpoint returns
        // HTTP 200 OK with `UserInfo.identities = []` (instead of an
        // error, to prevent email enumeration). Detect this branch
        // BEFORE the session-presence check — both AlreadyRegistered and
        // EmailConfirmationRequired produce a no-session response, so
        // identities is the only signal distinguishing them.
        if (userInfo?.identities?.isEmpty() == true) {
            return SignUpOutcome.AlreadyRegistered(email = email)
        }

        // Otherwise inspect the post-call session state to determine
        // whether Supabase auto-signed-in the user (Confirm-email = OFF
        // on the dashboard) or deferred session creation pending email
        // confirmation (Confirm-email = ON). The shared module has no
        // a priori knowledge of the dashboard setting, so this post-hoc
        // inspection is the canonical detection path. supabase-kt
        // populates `currentSessionOrNull()` synchronously inside
        // `signUpWith` on the session-created path; on the confirmation-
        // pending path the accessor stays null because no session token
        // was issued.
        return if (client.auth.currentSessionOrNull() != null) {
            SignUpOutcome.SessionCreated
        } else {
            SignUpOutcome.EmailConfirmationRequired(email = email)
        }
    }

    override suspend fun signOut() {
        supabaseClient?.auth?.signOut()
    }

    override suspend fun deleteAccount() {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        client.postgrest.rpc("delete_own_account")
        // Best-effort local session cleanup — account is already deleted server-side
        try {
            client.auth.signOut()
        } catch (_: Exception) {
            // Ignore: the auth session will become invalid on next refresh anyway
        }
    }

    override fun getCurrentUserId(): String? = supabaseClient?.auth?.currentUserOrNull()?.id

    override suspend fun sendPasswordResetEmail(email: String) {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        client.auth.resetPasswordForEmail(email)
    }

    override suspend fun updatePassword(newPassword: String) {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        client.auth.updateUser { password = newPassword }
    }

    override suspend fun updateEmail(newEmail: String) {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        client.auth.updateUser { email = newEmail }
    }

    override suspend fun signInWithApple(
        idToken: String,
        nonce: String,
    ): OAuthSignInOutcome = signInWithOAuthIdToken(idToken, nonce, OAuthProviderKind.Apple)

    override suspend fun signInWithGoogle(
        idToken: String,
        nonce: String?,
    ): OAuthSignInOutcome = signInWithOAuthIdToken(idToken, nonce, OAuthProviderKind.Google)

    override suspend fun linkPendingIdentity(
        provider: OAuthProviderKind,
        pendingIdToken: String,
        nonce: String?,
    ) {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")
        // supabase-kt 3.6 — `linkIdentityWithIdToken` is the IDToken-
        // flavored counterpart to the browser-OAuth `linkIdentity`.
        // Requires an active session (Supabase 401s if anonymous) +
        // verifies the token's `nonce` claim + cross-checks the
        // token's email against the session's user email. Throws on
        // any failure (expired token, email mismatch, provider
        // disabled in Dashboard) — the caller maps to a generic
        // error and keeps the session intact (the email/password
        // sign-in step that preceded this call already authenticated
        // the user; the identity link can be retried from Settings).
        client.auth.linkIdentityWithIdToken(
            provider =
                when (provider) {
                    OAuthProviderKind.Apple -> Apple
                    OAuthProviderKind.Google -> Google
                },
            idToken = pendingIdToken,
        ) {
            this.nonce = nonce
        }
    }

    override suspend fun signInWithAppleViaWebOAuth() {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")
        // No outcome-mapping path here: the session emerges
        // asynchronously on the auth-state flow once Custom Tabs
        // redirects back to `skeinly://auth-callback` and
        // `MainActivity.handleDeeplinks(intent)` fires. This call
        // returns once the Custom Tab is launched; user cancel /
        // browser failure path silently leaves auth-state untouched.
        //
        // LinkIdentityRequired is NOT distinguishable here because
        // the supabase-kt browser-OAuth flow doesn't return an
        // outcome envelope — Supabase emits the collision via the
        // hosted auth UI which the user sees in the Custom Tab.
        // Phase 26.4 (linkIdentity) addresses the merge UX from the
        // post-IDToken side; the Android web-OAuth path falls back
        // to Supabase's hosted error rendering for the alpha scope.
        client.auth.signInWith(Apple)
    }

    /**
     * Phase 26.1 / 26.2 — shared IDToken path. The two OAuth provider
     * methods diverge only in `IDTokenProvider` (Apple / Google) and
     * the `LinkIdentityRequired.provider` field; everything else
     * (error-code matching, email-claim extraction, generic-error
     * bubbling) is identical.
     */
    private suspend fun signInWithOAuthIdToken(
        idToken: String,
        nonce: String?,
        provider: OAuthProviderKind,
    ): OAuthSignInOutcome {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        return try {
            client.auth.signInWith(IDToken) {
                this.idToken = idToken
                this.provider =
                    when (provider) {
                        OAuthProviderKind.Apple -> Apple
                        OAuthProviderKind.Google -> Google
                    }
                this.nonce = nonce
            }
            // Supabase emits the new session on the auth-state flow; the
            // UI clears its submitting flag and the root navigator routes
            // away from LoginScreen on the Authenticated transition.
            OAuthSignInOutcome.SessionCreated
        } catch (e: AuthRestException) {
            // Supabase rejects when the OAuth identity's email already
            // exists in auth.users under a different sign-in method.
            // Error codes vary across supabase-kt versions: 3.x typically
            // surfaces `user_already_exists`, but `email_exists` and
            // `identity_already_exists` have been observed across the
            // 3.x patch line. Match defensively across the family — and
            // fall back on the human-readable message for forward-compat
            // when supabase-kt renames a code without a corresponding
            // SDK release.
            val code =
                e.errorCode
                    ?.value
                    .orEmpty()
                    .lowercase()
            val msg = e.message.orEmpty().lowercase()
            val isUserExists =
                code in USER_EXISTS_CODES ||
                    msg.contains("already exists") ||
                    msg.contains("already registered")
            if (isUserExists) {
                // Phase 26.4 (ADR-022 §6.3) — carry the pendingIdToken
                // + nonce back to the ViewModel so the LinkIdentity
                // resolution step can re-submit them via
                // `linkIdentityWithIdToken(provider, ...)` once the
                // user signs in with their email/password.
                OAuthSignInOutcome.LinkIdentityRequired(
                    email = extractEmailFromIdToken(idToken).orEmpty(),
                    provider = provider,
                    pendingIdToken = idToken,
                    nonce = nonce,
                )
            } else {
                throw e
            }
        }
    }

    private companion object {
        /**
         * Supabase error-code aliases recognized as the "email already
         * registered under a different auth method" path. Centralized
         * so Phase 26.2 (Google sign-in) shares the same set.
         */
        val USER_EXISTS_CODES: Set<String> =
            setOf(
                "user_already_exists",
                "email_exists",
                "identity_already_exists",
            )
    }
}

/**
 * Phase 26.1 — best-effort `email` claim extraction from an Apple-issued
 * ID token. Used solely to populate
 * [OAuthSignInOutcome.LinkIdentityRequired.email] when Supabase rejects
 * with the "user already exists" path; we want to show the user the
 * exact address that conflicts so they pick the right sign-in method.
 *
 * Apple omits `email` after the first sign-in (privacy by default) AND
 * may return a `private_relay` address (random-string@privaterelay.appleid.com);
 * we surface whatever Apple included without further processing. Returns
 * null on any decoding failure — the UI then shows a generic "this
 * email" prompt without the address rather than fail the whole flow.
 *
 * Defensive: this is NOT a JWT verifier — Supabase already verifies
 * server-side. The function only parses the payload as Base64URL+JSON
 * for display purposes.
 */
internal fun extractEmailFromIdToken(idToken: String): String? {
    val parts = idToken.split('.')
    if (parts.size < 2) return null
    val payload =
        try {
            base64UrlDecodeToString(parts[1])
        } catch (_: Throwable) {
            return null
        }
    // Defensive substring parse to avoid a kotlinx.serialization
    // dependency surface here; the JWT body shape is well-known.
    val key = "\"email\""
    val keyIdx = payload.indexOf(key)
    if (keyIdx < 0) return null
    val colonIdx = payload.indexOf(':', startIndex = keyIdx + key.length)
    if (colonIdx < 0) return null
    val firstQuote = payload.indexOf('"', startIndex = colonIdx + 1)
    if (firstQuote < 0) return null
    val secondQuote = payload.indexOf('"', startIndex = firstQuote + 1)
    if (secondQuote < 0) return null
    return payload.substring(firstQuote + 1, secondQuote).takeIf { it.isNotBlank() }
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun base64UrlDecodeToString(b64Url: String): String {
    // Base64URL → Base64 (standard) + padding restoration
    val standard =
        b64Url
            .replace('-', '+')
            .replace('_', '/')
    val padded =
        when (standard.length % 4) {
            2 -> "$standard=="
            3 -> "$standard="
            else -> standard
        }
    val bytes =
        kotlin.io.encoding.Base64
            .decode(padded)
    return bytes.decodeToString()
}
