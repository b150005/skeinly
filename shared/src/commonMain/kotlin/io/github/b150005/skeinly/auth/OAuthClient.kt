package io.github.b150005.skeinly.auth

/**
 * Phase 26.2 (ADR-022 §6.2) — platform-specific OAuth ID-token
 * acquisition surface. The Apple path on iOS uses SwiftUI's
 * `SignInWithAppleButton` and bridges through
 * `KoinHelperKt.signInWithAppleIdToken(...)` — this expect class
 * intentionally does NOT cover Apple sign-in (the path lives entirely
 * in Swift on iOS, with the bridge bypassing this seam).
 *
 * Phase 26.2 ships Android Google sign-in via
 * `androidx.credentials.CredentialManager`; iOS Google sign-in is
 * deferred to Phase 26.3 (would route through `GIDSignIn` via
 * GoogleService-Info.plist; the iOS actual returns
 * `OAuthIdTokenResult.Failure` for now).
 *
 * The platform actual is a Koin singleton — on Android it stores a
 * weak reference to the current Activity (attached at `MainActivity.onCreate`,
 * detached at `onDestroy`), mirroring the Phase 24.2e
 * `PushTokenRegistrar` precedent for Activity-scoped APIs.
 */
expect class OAuthClient {
    /**
     * Acquires a Google ID token using the platform's native
     * credentials surface. Returns [OAuthIdTokenResult.Success] with
     * the JWT + optional plaintext nonce on the happy path,
     * [OAuthIdTokenResult.UserCancelled] when the user dismisses the
     * picker, or [OAuthIdTokenResult.Failure] on any other failure
     * (no Google account on device, no credentials, Play Services
     * missing, etc.).
     */
    suspend fun acquireGoogleIdToken(): OAuthIdTokenResult
}

/**
 * Outcome of a platform-side OAuth ID-token acquisition call. The UI
 * layer decides whether to surface the failure to the user or to
 * silently no-op (UserCancelled is the typical silent case).
 */
sealed interface OAuthIdTokenResult {
    /**
     * @property idToken The signed JWT to forward to Supabase via
     *                   `signInWithGoogle(idToken, nonce)`.
     * @property nonce   Plaintext nonce that the request stamped into
     *                   the token's `nonce` claim, if any. May be null
     *                   when the platform does not bind a nonce.
     */
    data class Success(
        val idToken: String,
        val nonce: String?,
    ) : OAuthIdTokenResult

    /** User dismissed the credential picker — surface no banner. */
    data object UserCancelled : OAuthIdTokenResult

    /**
     * Any other terminal failure — no Google account on device,
     * misconfigured Web Client ID, Play Services unavailable, etc.
     * [message] is the underlying error message for log + diagnostic
     * surfaces; the UI maps to `ErrorMessage.Generic` for the user.
     */
    data class Failure(
        val message: String,
    ) : OAuthIdTokenResult
}
