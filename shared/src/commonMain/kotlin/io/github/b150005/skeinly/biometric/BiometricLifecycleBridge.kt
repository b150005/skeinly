package io.github.b150005.skeinly.biometric

import io.github.b150005.skeinly.platform.AppLifecycleEvent
import io.github.b150005.skeinly.platform.AppLifecycleObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Phase 26.6 (ADR-022 §6.5) — wires [AppLifecycleObserver] events to
 * the [BiometricGuardian]'s `onBackgrounded` / `requireForResume`
 * surface and routes [BiometricResult.Failed] / [BiometricResult.Cancelled]
 * outcomes to the supplied [onResumeAuthInvalidated] callback so the
 * platform boot layer can sign the user out (per ADR §3.8).
 *
 * The callback approach replaces an earlier shape that returned a
 * `SharedFlow<BiometricResult>` to the caller — that surface left the
 * security-critical sign-out wiring as an opt-in step on each platform,
 * which the Phase 26.6 first cut accidentally omitted. Folding the
 * collector into the bridge itself makes the gate close-loop end-to-end
 * by construction: a platform that calls
 * [startBiometricLifecycleBridge] inherits the sign-out plumbing for
 * free.
 *
 * Why a separate bridge (not a `BiometricGuardian.start()` method):
 * the lifecycle observer + application-scope coroutine wiring belongs
 * at the DI / app-boot layer, NOT inside the Guardian itself. The
 * Guardian stays unit-testable as a pure suspend service taking
 * lambda-injected dependencies. Same shape as `startRevenueCatAuthBridge`
 * (Phase 39.0.1).
 *
 * Multiple invocations are not idempotent — the AppLifecycleObserver
 * is a Koin singleton and observers stack on its underlying
 * NSNotificationCenter / ProcessLifecycleOwner registration, but
 * Skeinly only calls this once at app boot from each platform's
 * Application entry point.
 *
 * @param onResumeAuthInvalidated invoked when the resume gate yields
 *   [BiometricResult.Failed] or [BiometricResult.Cancelled]. The
 *   platform binding supplies a closure that invokes the SignOut use
 *   case so the next [AuthState] emission routes the user back to
 *   LoginScreen with the session invalidated. Synchronous-style
 *   suspend so the platform can await sign-out completion before
 *   yielding the lifecycle thread back to the system.
 */
fun startBiometricLifecycleBridge(
    scope: CoroutineScope,
    guardian: BiometricGuardian,
    lifecycleObserver: AppLifecycleObserver,
    onResumeAuthInvalidated: suspend () -> Unit,
) {
    scope.launch {
        lifecycleObserver.events.collect { event ->
            when (event) {
                AppLifecycleEvent.Backgrounded -> guardian.onBackgrounded()
                AppLifecycleEvent.Foregrounded -> {
                    when (guardian.requireForResume()) {
                        BiometricResult.Success -> Unit
                        BiometricResult.Cancelled,
                        BiometricResult.Failed,
                        ->
                            onResumeAuthInvalidated()
                        // Defense-in-depth — the Guardian collapses
                        // unavailable-device cases to Failed before
                        // reaching here, so Unavailable is unreachable
                        // from requireForResume. Mapping to sign-out
                        // matches Failed semantics if it ever surfaces.
                        BiometricResult.Unavailable -> onResumeAuthInvalidated()
                    }
                }
            }
        }
    }
}
