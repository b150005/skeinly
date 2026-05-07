package io.github.b150005.skeinly.domain.subscription

/**
 * Phase 41.3 (ADR-016 §5.1) — domain projection of a RevenueCat
 * `Offering` for the paywall surface.
 *
 * RevenueCat's native `Offering` exposes a multi-package list keyed by
 * predefined identifiers (`$rc_monthly`, `$rc_annual`, etc.). The paywall
 * ViewModel projects the bits the UI needs ([identifier], [packages]) and
 * lets the SDK keep the rest. Domain-typed packaging means tests can
 * synthesize fixtures without booting the SDK.
 */
data class PaywallOffering(
    /** RevenueCat offering id — typically `default` for the active one. */
    val identifier: String,
    val packages: List<PaywallPackage>,
) {
    /** Convenience accessor for the monthly package, if present. */
    val monthly: PaywallPackage? get() = packages.firstOrNull { it.period == PaywallPeriod.MONTHLY }

    /** Convenience accessor for the annual package, if present. */
    val annual: PaywallPackage? get() = packages.firstOrNull { it.period == PaywallPeriod.ANNUAL }
}

/**
 * One purchasable package surfaced by the paywall.
 *
 * @property identifier RevenueCat package id (`$rc_monthly` / `$rc_annual` /
 *   custom). Used by the ViewModel to dispatch the purchase call back into
 *   [RevenueCatService.purchase].
 * @property productId Store-side product identifier (App Store SKU /
 *   Play Store SKU) — surfaces in [io.github.b150005.skeinly.data.analytics.AnalyticsEvent.PurchaseSubscribed]
 *   for funnel analysis.
 * @property period Best-effort period classification — derived from the
 *   RevenueCat package id to drive UI labels (monthly vs annual section,
 *   "save X%" badge). [PaywallPeriod.OTHER] covers lifetime / weekly /
 *   custom packages we don't model in v1.
 * @property priceString Localized price string from the platform Store
 *   (e.g. "¥800", "$3.99/mo"). Display-only — never parse for price math.
 * @property priceMicros Price in micro-units (1 unit = 1_000_000 micros)
 *   for the "save X%" calculation. Comparisons across [period] values
 *   normalize via a 12× multiplier on monthly to compare against annual.
 * @property currencyCode ISO 4217 (e.g. "JPY", "USD"). Used for sanity
 *   checks; mismatch across packages would suggest a misconfigured
 *   RevenueCat offering and the paywall surfaces a generic error.
 */
data class PaywallPackage(
    val identifier: String,
    val productId: String,
    val period: PaywallPeriod,
    val priceString: String,
    val priceMicros: Long,
    val currencyCode: String,
)

/**
 * Coarse-grained period classification of a paywall package.
 *
 * Phase 41.3 ships the monthly + annual paywall layout per ADR-016 §5.1.
 * Other periods (weekly, lifetime, etc.) map to [OTHER] and are rendered
 * generically without the side-by-side comparison treatment.
 */
enum class PaywallPeriod {
    MONTHLY,
    ANNUAL,
    OTHER,
}

/**
 * Result of [RevenueCatService.purchase].
 *
 * Distinguishes user cancellation (no error surface to render — the user
 * tapped Cancel, that's the expected path out) from an actual failure
 * (network / Store unavailable / receipt rejected).
 */
sealed interface PurchaseResult {
    /**
     * Purchase landed.
     *
     * @property productId The store product id the user actually bought.
     *   May differ from the package's [PaywallPackage.productId] if the
     *   user's prior subscription was on a different SKU and RevenueCat's
     *   upgrade/crossgrade resolved to that SKU.
     */
    data class Success(
        val productId: String,
    ) : PurchaseResult

    /** User cancelled the platform purchase dialog. Not surfaced as error. */
    data object UserCancelled : PurchaseResult

    /**
     * Purchase failed for a reason other than user cancellation.
     *
     * @property message Human-readable error from the platform; safe to
     *   show in an inline paywall error label or pipe through Sentry.
     */
    data class Failed(
        val message: String,
    ) : PurchaseResult
}

/**
 * Result of [RevenueCatService.restorePurchases].
 *
 * [Success.proActive] indicates whether, after the restore, the user has
 * an active Pro entitlement. This drives the "Welcome back!" toast vs.
 * the "No purchases found" affordance distinction.
 */
sealed interface RestoreResult {
    data class Success(
        val proActive: Boolean,
    ) : RestoreResult

    data class Failed(
        val message: String,
    ) : RestoreResult
}
