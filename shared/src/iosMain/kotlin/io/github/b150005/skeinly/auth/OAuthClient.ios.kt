package io.github.b150005.skeinly.auth

/**
 * Phase 26.2 — iOS stub for [OAuthClient.acquireGoogleIdToken].
 *
 * Phase 26.2 ships Android Google sign-in only; iOS Google sign-in
 * lands in Phase 26.3 (will route through `GIDSignIn` reading
 * `GoogleService-Info.plist`'s OAuth iOS Client ID). Until then this
 * actual returns [OAuthIdTokenResult.Failure] so a SwiftUI caller
 * that wires up the Continue-with-Google button gets a structurally
 * clean error path instead of a crash.
 *
 * The iOS Apple sign-in path does NOT go through this surface —
 * `SignInWithAppleButton` (SwiftUI) bridges directly to Kotlin via
 * `KoinHelperKt.signInWithAppleIdToken(idToken, nonce)`.
 */
actual class OAuthClient {
    actual suspend fun acquireGoogleIdToken(): OAuthIdTokenResult =
        OAuthIdTokenResult.Failure(
            message = "iOS Google sign-in is deferred to Phase 26.3 — use Apple sign-in instead.",
        )
}
