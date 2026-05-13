package io.github.b150005.skeinly.domain.model

/**
 * Distinguishes whether a successful sign-up call also created an
 * authenticated session, or if Supabase deferred the session pending
 * email confirmation.
 *
 * The session-deferred path occurs when the Supabase project has the
 * "Confirm email" auth setting enabled on the dashboard. The shared
 * module cannot detect this configuration ahead of time, so
 * `signUpWithEmail` must inspect the post-call session state and
 * return the corresponding outcome. The UI uses this to decide whether
 * to wait for the `observeAuthState()` flow to emit `Authenticated`
 * (immediate-sign-in case) or to show a "check your email" view
 * (deferred-confirmation case).
 *
 * Root cause trail: pre-alpha 2026-05-13 sign-up bug where Supabase
 * Dashboard had "Confirm email" enabled in production, so
 * `signUpWith(Email)` succeeded at HTTP but no session was created, the
 * `observeAuthState()` flow stayed at `Unauthenticated`, the UI never
 * navigated away from `LoginScreen`, and the user perceived "登録 button
 * does nothing". Sealed type makes the dual outcome explicit at the
 * type level so the ViewModel cannot accidentally drop the
 * confirmation-pending path.
 */
sealed interface SignUpOutcome {
    /**
     * Sign-up succeeded and Supabase created an authenticated session.
     * The `observeAuthState()` flow will emit `Authenticated` imminently;
     * navigation to the post-login screen is driven by that flow.
     */
    data object SessionCreated : SignUpOutcome

    /**
     * Sign-up succeeded at the HTTP level but Supabase did NOT create a
     * session — the user must confirm the email address Supabase sent a
     * verification link to before they can sign in. [email] is the
     * address the verification was sent to, surfaced by the UI so the
     * user knows which inbox to check.
     */
    data class EmailConfirmationRequired(
        val email: String,
    ) : SignUpOutcome
}
