package io.github.b150005.skeinly.data.subscription

import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.subscription.RevenueCatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Phase 39 closed beta prep — bridges Skeinly's auth state to RevenueCat's
 * customer identity so webhook events carry the user UUID our backend uses.
 *
 * **Why this exists.** Without identification, [RevenueCatService] runs
 * under an anonymous `$RCAnonymousID:xxxx` id assigned by the RevenueCat
 * SDK at first use. RevenueCat webhook payloads include `event.app_user_id`
 * which our `revenuecat-webhook` Edge Function uses to key into
 * `public.subscriptions.user_id`. Mapping anonymous ids back to Skeinly
 * accounts post-fact is impossible, so the bridge identifies the customer
 * AS SOON AS auth succeeds, before the user can reach the paywall.
 *
 * **Anonymous → identified migration**: when the user purchases as a guest
 * and signs up later, [RevenueCatService.identifyUser] (via
 * `Purchases.sharedInstance.logIn`) alias-merges the anonymous purchases
 * into the new account. No purchase data is lost; the SDK handles this
 * transparently.
 *
 * **Lifecycle.** Started exactly once per process from Application init
 * (Android `SkeinlyApplication.onCreate`, iOS via [KoinHelper] →
 * `iOSApp.init`) AFTER both [RevenueCatBootstrap.configure] and Koin DI
 * have completed. Returns the [Job] for tests + future cleanup paths;
 * production callers can safely ignore it (the bridge naturally lives
 * for the lifetime of the application scope).
 *
 * **Failure handling.** Calls to [RevenueCatService.identifyUser] /
 * [RevenueCatService.logOutUser] return `Result` and never throw, so a
 * single network blip on RevenueCat won't terminate the bridge or block
 * the auth flow. Failures are logged via `println` so Sentry breadcrumbs
 * (which capture stdout on the Application process) capture them for
 * triage. The auth flow is unaffected — auth has its own success path
 * driven by Supabase, RevenueCat sync is best-effort.
 *
 * **Distinct-until-changed semantics.** Same userId emitted twice (e.g.
 * `Loading` → `Authenticated(uid=X)` → `Authenticated(uid=X)` after a
 * session refresh) does NOT re-fire `identifyUser`. The flow operator
 * dedupes upstream of the call site so the SDK never receives a
 * redundant logIn on session refresh tick.
 *
 * **State mapping.**
 *   - [AuthState.Authenticated] → call `identifyUser(userId)`
 *   - [AuthState.Unauthenticated] → call `logOutUser()`
 *   - [AuthState.Loading], [AuthState.Error] → no-op (transient states;
 *     the next non-transient emission triggers the appropriate call)
 */
fun startRevenueCatAuthBridge(
    scope: CoroutineScope,
    authRepository: AuthRepository,
    revenueCatService: RevenueCatService,
): Job =
    authRepository
        .observeAuthState()
        .map { state ->
            when (state) {
                is AuthState.Authenticated -> AuthIdentity.User(state.userId)
                AuthState.Unauthenticated -> AuthIdentity.Anonymous
                AuthState.Loading -> AuthIdentity.Transient
                is AuthState.Error -> AuthIdentity.Transient
            }
        }.distinctUntilChanged()
        .onEach { identity ->
            when (identity) {
                AuthIdentity.Transient -> Unit
                AuthIdentity.Anonymous ->
                    revenueCatService.logOutUser().onFailure { e ->
                        println("RevenueCatAuthBridge: logOut failed: ${e.message}")
                    }
                is AuthIdentity.User ->
                    revenueCatService.identifyUser(identity.userId).onFailure { e ->
                        println(
                            "RevenueCatAuthBridge: identify(userId=${identity.userId}) failed: ${e.message}",
                        )
                    }
            }
        }.launchIn(scope)

/**
 * Internal identity sentinel so [distinctUntilChanged] can dedupe by
 * value-class equality without needing nullable userId trickery.
 *
 * `Transient` represents [AuthState.Loading] / [AuthState.Error] — the
 * bridge ignores these but still passes them through the flow so the
 * dedupe operator sees a stable "no-op" marker between authenticated
 * and unauthenticated transitions.
 */
internal sealed interface AuthIdentity {
    data object Transient : AuthIdentity

    data object Anonymous : AuthIdentity

    data class User(
        val userId: String,
    ) : AuthIdentity
}
