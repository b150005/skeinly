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
     * Common supertype for the two outcomes that share a UI surface:
     * `EmailConfirmationRequired` (genuine new signup pending
     * confirmation) and `AlreadyRegistered` (signup attempted with an
     * email that already exists in `auth.users`).
     *
     * Both produce the **same** "check your email" UI state, deliberately,
     * to preserve Supabase's email-enumeration security-by-obscurity at
     * the UI layer (OWASP A07 — Identification and Authentication
     * Failures). The Repository layer detects which one occurred via
     * `UserInfo.identities`, but the UI cannot expose that distinction
     * — otherwise an attacker who can decompile or screen-record the
     * app could probe arbitrary emails through the sign-up form and
     * observe which message appears to enumerate valid Skeinly accounts.
     *
     * The supertype keeps the variants as distinct types so that
     * future-state features (logging / analytics / rate-limit detection
     * / timing equalization) can branch on them in the Repository or
     * orchestration layers without re-introducing the UI-layer leak.
     */
    sealed interface AwaitingEmailAction : SignUpOutcome {
        /** The email address the user submitted, surfaced by the UI so the user knows which inbox to check. */
        val email: String
    }

    /**
     * Sign-up succeeded at the HTTP level but Supabase did NOT create a
     * session — the user must confirm the email address Supabase sent a
     * verification link to before they can sign in.
     */
    data class EmailConfirmationRequired(
        override val email: String,
    ) : AwaitingEmailAction

    /**
     * Sign-up succeeded at the HTTP level but the email is already
     * registered in `auth.users`. Supabase's security-by-obscurity policy
     * returns HTTP 200 OK with `UserInfo.identities = []` in this case
     * to prevent email enumeration attacks; the UI must surface this
     * **identically** to `EmailConfirmationRequired` (same "check your
     * email" view + same wording) so an attacker cannot distinguish via
     * the UI surface.
     *
     * Root cause trail: pre-alpha 2026-05-13 sign-up bug where the
     * operator tried to signup with their own pre-existing
     * `b150005@outlook.jp` 7 times in succession — Supabase logged
     * each as `user_repeated_signup` action with HTTP 200, no audit
     * trail at the auth.users level. The 20d65a5 commit initially
     * routed this case to an explicit "An account with this email
     * already exists" alert + auto-switch to sign-in, but that surface
     * leaked the existence signal at the UI layer (security-reviewer
     * flag) — reverted in the next commit to share UI with
     * `EmailConfirmationRequired` and added an
     * "if email doesn't arrive, you may already have an account, try
     * signing in" hint that helps the legitimate owner discover the
     * right action without exposing the existence signal to attackers.
     */
    data class AlreadyRegistered(
        override val email: String,
    ) : AwaitingEmailAction
}
