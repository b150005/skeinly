package io.github.b150005.skeinly.domain.subscription

/**
 * Phase 41.3 (ADR-016 §6 §41.3) — domain port over the RevenueCat KMP SDK.
 *
 * Skeinly takes the ports-and-adapters shape (same as `PullRequestMergeOperations`
 * for Phase 38.4 and `SymbolPackRemoteOperations` for Phase 41.2b): the
 * domain-layer interface is suspendable + maps to domain types; the
 * `data.subscription.RevenueCatServiceImpl` adapter wraps `Purchases.sharedInstance`
 * from `purchases-kmp-core` and converts SDK error / package types into
 * the [PaywallOffering] / [PurchaseResult] shapes the paywall renders.
 *
 * **Why a wrapper rather than direct SDK calls.** The official KMP SDK is
 * already common — `Purchases.sharedInstance` is callable from `commonMain`.
 * The wrapper exists for testability: [PaywallViewModel][io.github.b150005.skeinly.ui.paywall.PaywallViewModel]
 * tests inject an in-memory fake without booting the SDK or stubbing
 * platform StoreKit / Play Billing surfaces. The runtime overhead is
 * one method dispatch per call — negligible against the seconds-scale
 * Store I/O the SDK is doing under the hood.
 *
 * **Configuration is out of scope here.** [RevenueCatBootstrap] is the
 * single `Purchases.configure(apiKey)` entry point, called from the
 * Application init paths (Android `SkeinlyApplication.onCreate`, iOS
 * `iOSApp.init`). The wrapper assumes configuration has succeeded and
 * surfaces SDK errors as typed [PurchaseResult.Failed] / [RestoreResult.Failed]
 * variants without trying to recover.
 */
interface RevenueCatService {
    /**
     * Fetches the current `default` offering from RevenueCat. Returns
     * `Result.success(null)` when the project has no active offering
     * (early-development RevenueCat dashboard state) — surfaces as
     * "No paywall available" in the UI.
     *
     * Network errors / SDK misconfiguration / signed-out state surface
     * as `Result.failure(...)`.
     */
    suspend fun getOfferings(): Result<PaywallOffering?>

    /**
     * Initiates a purchase for [pkg] through the platform Store dialog.
     * Suspends until the user completes the dialog (Success / UserCancelled)
     * or it fails (Failed).
     *
     * RevenueCat handles the receipt validation + entitlement grant
     * server-side via the `verify-receipt` Edge Function (Phase A0d-3
     * webhook). On Success the local `subscriptions` cache mirror updates
     * via either (a) the post-purchase callback chain in
     * [io.github.b150005.skeinly.ui.paywall.PaywallViewModel], or (b) the
     * Realtime push from the `verify-receipt` Edge Function, whichever
     * arrives first.
     */
    suspend fun purchase(pkg: PaywallPackage): PurchaseResult

    /**
     * Restores prior purchases for the currently signed-in user. Used by
     * the "Restore Purchases" CTA on the paywall — handles app reinstalls
     * and account restoration on a new device.
     *
     * Returns [RestoreResult.Success] with [RestoreResult.Success.proActive]
     * indicating whether a Pro entitlement is now active (drives the
     * "Welcome back!" vs "No purchases found" toast).
     */
    suspend fun restorePurchases(): RestoreResult
}
