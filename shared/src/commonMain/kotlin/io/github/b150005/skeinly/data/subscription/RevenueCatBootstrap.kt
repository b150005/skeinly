package io.github.b150005.skeinly.data.subscription

import com.revenuecat.purchases.kmp.LogLevel
import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.PurchasesConfiguration
import io.github.b150005.skeinly.config.RevenueCatConfig
import io.github.b150005.skeinly.config.isConfigured
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Phase 41.3 (ADR-016 §6 §41.3) — single bootstrap entry point for
 * `Purchases.configure(apiKey)`.
 *
 * Called from each platform's Application init paths AFTER Koin is up:
 * - **Android**: `SkeinlyApplication.onCreate` (after Koin init, after
 *   Sentry init).
 * - **iOS**: `iOSApp.init` (after `KoinHelperKt.doInitKoin()`, after the
 *   Sentry boot block).
 *
 * **Atomic check-then-set against double init.** RevenueCat's
 * `Purchases.configure` is documented as safe to call once per process —
 * calling it twice with different keys would clobber the cache. The
 * [configured] flag is an [AtomicBoolean] so a defense-in-depth
 * concurrent caller (e.g. a future deep-link handler invoking from a
 * non-main thread, even though current call sites are main-thread-only)
 * cannot race past the guard. `compareAndSet(false, true)` is the
 * canonical atomic check-then-set primitive on KMP and is supported on
 * both JVM and Kotlin/Native.
 *
 * **No-op when [RevenueCatConfig.isConfigured] is false.** Local-dev
 * builds without the SDK key skip configure entirely. The paywall
 * surfaces "subscriptions unavailable in this build" rather than the
 * SDK throwing on uninitialized `sharedInstance` access.
 */
@OptIn(ExperimentalAtomicApi::class)
object RevenueCatBootstrap {
    private val configured = AtomicBoolean(false)

    /**
     * Configures `Purchases.sharedInstance` with the platform-specific
     * API key from [RevenueCatConfig]. Atomic check-then-set — any
     * concurrent second caller observes the flag flipped and silently
     * returns without invoking `Purchases.configure` again.
     *
     * @param appUserId Optional explicit RevenueCat app-user-id binding.
     *   Phase 41.3 leaves null (RevenueCat generates an anonymous id);
     *   future Phase 41.x can bind to the Supabase auth user id when the
     *   linkage between Skeinly accounts and RevenueCat customers is
     *   surfaced for cross-device entitlement transfer.
     * @param verbose If true, sets `Purchases.logLevel = LogLevel.DEBUG`.
     *   Default false (logs at INFO). Beta builds may flip this on for
     *   richer Sentry breadcrumbs around the purchase flow.
     */
    fun configure(
        appUserId: String? = null,
        verbose: Boolean = false,
    ) {
        // Reserve the configure slot atomically. If a concurrent caller
        // already flipped the flag, return silently. Note: an in-flight
        // first call that is mid-`Purchases.configure` will release the
        // SDK with the first caller's args; the second caller's args are
        // dropped — that matches the SDK's "configure once per process"
        // contract and the prior @Volatile-based behavior.
        if (!configured.compareAndSet(expectedValue = false, newValue = true)) return
        if (!RevenueCatConfig.isConfigured) {
            // Local-dev / CI fallback — no key wired. Paywall ViewModel
            // surfaces an "unavailable" error gracefully instead of the
            // SDK throwing on first access. Reset the flag so a later
            // session that DOES have a key wired can configure properly
            // (otherwise a single uninstrumented build would permanently
            // poison the singleton across hot-restart scenarios).
            configured.store(false)
            return
        }
        if (verbose) {
            Purchases.logLevel = LogLevel.DEBUG
        }
        val builder = PurchasesConfiguration.Builder(apiKey = RevenueCatConfig.apiKey)
        if (appUserId != null) {
            builder.appUserId = appUserId
        }
        Purchases.configure(builder.build())
    }

    /** True after a successful [configure] call. Test affordance. */
    val isConfigured: Boolean get() = configured.load()
}
