package io.github.b150005.skeinly.auth

import android.content.Intent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.handleDeeplinks
import org.koin.core.context.GlobalContext

/**
 * Phase 26.x (ADR-022 §6.1) — Android bridge for the supabase-kt
 * `handleDeeplinks(intent)` call. Lives in the shared module's
 * `androidMain` source set because supabase-kt's API surface is NOT
 * propagated into `:androidApp`'s compile classpath via the shared
 * module's `implementation` scope.
 *
 * Called from `MainActivity.onCreate` (cold-start) and
 * `MainActivity.onNewIntent` (warm-start) so the Custom Tab OAuth
 * callback URL gets parsed regardless of Activity lifecycle state.
 * `singleTop` launchMode on MainActivity ensures both code paths fire.
 *
 * Nullable Koin lookup matches `SupabaseModule`'s conditional
 * registration: when `SupabaseConfig.isConfigured = false` (local-only
 * mode / CI without backend secrets) the SupabaseClient is NOT
 * registered, `getOrNull` returns null, and this helper silently
 * no-ops. OAuth deep links won't arrive in that mode anyway because
 * `signInWithAppleViaWebOAuth()` itself throws at the upstream
 * Compose-button tap.
 *
 * `runCatching` defensive wrapper guards against any future
 * supabase-kt internal exception class — the deep-link callback path
 * is non-essential (the user can always retry the sign-in flow) and a
 * crashed Activity on auth resume is the worst outcome we want to
 * avoid.
 */
fun handleAuthDeeplink(intent: Intent) {
    val client = GlobalContext.getKoinApplicationOrNull()?.koin?.getOrNull<SupabaseClient>() ?: return
    // supabase-kt 3.6 — `handleDeeplinks` is an extension on
    // SupabaseClient (NOT on Auth). Located in package
    // `io.github.jan.supabase.auth` (the auth-kt-android artifact).
    //
    // **Accepted alpha trade-off**: this two-arg overload of
    // `handleDeeplinks` invokes the supabase-kt internals with
    // no-op `onSessionSuccess` / `onError` callbacks. OAuth
    // server-side errors (e.g. `error=redirect_uri_mismatch` from
    // Supabase rejecting a misconfigured Additional Redirect URL,
    // `error=access_denied` from the Apple consent screen, transient
    // network errors during code exchange) are silently dropped at
    // this layer. The user lands back on LoginScreen with no error
    // banner. Mirrors the Apple iOS native `SignInWithAppleButton`
    // cancel-silent UX precedent (Phase 26.1 KDoc on
    // `AppleSignInBridge.handleCompletion`).
    //
    // Tech Debt: Phase 26.4 (linkIdentity merge UX) + Phase 26.7 (UI
    // polish + privacy policy + smoke test) should surface a banner
    // for the non-cancel error subset. The four-arg `handleDeeplinks`
    // overload exposes `onError` — wiring that into a ViewModel event
    // bus from this `androidMain` layer requires either a Koin lookup
    // of `AuthViewModel` here (couples the deeplink helper to the VM)
    // or an `AuthErrorChannel` SharedFlow registered in Koin that the
    // VM subscribes to. Deferred — alpha tester signal will tell us
    // whether silent-cancel UX is good enough or whether the
    // misconfiguration paths need explicit feedback.
    //
    // `runCatching` defensive wrapper guards against any future
    // supabase-kt internal exception (e.g. a deeplink-parser change
    // throwing instead of routing through `onError`). The deep-link
    // callback path is non-essential to app continuation — a
    // crashed Activity on auth resume is the worst outcome to avoid.
    runCatching { client.handleDeeplinks(intent) }
}
