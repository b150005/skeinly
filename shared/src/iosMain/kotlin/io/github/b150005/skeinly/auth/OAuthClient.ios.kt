package io.github.b150005.skeinly.auth

/**
 * Phase 26.3 — iOS [OAuthClient] actual.
 *
 * The iOS Google sign-in path does **NOT** go through this seam. iOS
 * uses `GIDSignIn.sharedInstance.signIn(withPresenting:)` from SwiftUI's
 * `GoogleSignInBridge.swift`, which needs `UIWindow.rootViewController`
 * for modal presentation — abstracting that through Kotlin's
 * `expect class` would force an extra Swift⇄Kotlin hop without value.
 * The SwiftUI button calls `KoinHelperKt.signInWithGoogleIdToken(...)`
 * directly, mirroring the Apple Sign-In bridge pattern from Phase 26.1.
 *
 * Therefore [acquireGoogleIdToken] returns [OAuthIdTokenResult.Failure]
 * on iOS — any caller that somehow reaches this seam (a hypothetical
 * stale Android-shaped path through `AuthEvent.SignInWithGoogle`) gets
 * a structurally clean error path instead of a crash. The production
 * iOS flow never invokes this method; the path goes:
 *
 *   SwiftUI Button → `GoogleSignInBridge.signIn` → GIDSignIn modal →
 *   `KoinHelperKt.signInWithGoogleIdToken(idToken, nonce)` →
 *   `AuthEvent.SignInWithGoogleIdToken` → `AuthViewModel.handleGoogleIdToken` →
 *   `AuthRepository.signInWithGoogle`.
 *
 * Mirror surface for Apple Sign-In: same architecture (Swift owns
 * presentation, Kotlin owns repository forward).
 */
actual class OAuthClient {
    actual suspend fun acquireGoogleIdToken(): OAuthIdTokenResult =
        OAuthIdTokenResult.Failure(
            message =
                "iOS Google sign-in goes through GoogleSignInBridge + " +
                    "KoinHelperKt.signInWithGoogleIdToken, not OAuthClient. " +
                    "Reaching this code path indicates a wiring bug.",
        )
}
