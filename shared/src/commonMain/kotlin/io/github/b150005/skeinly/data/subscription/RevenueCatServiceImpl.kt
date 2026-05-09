package io.github.b150005.skeinly.data.subscription

import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.models.Offering
import com.revenuecat.purchases.kmp.models.Offerings
import com.revenuecat.purchases.kmp.models.Package
import com.revenuecat.purchases.kmp.models.PackageType
import com.revenuecat.purchases.kmp.models.PurchasesTransactionException
import com.revenuecat.purchases.kmp.result.awaitLogInResult
import com.revenuecat.purchases.kmp.result.awaitLogOutResult
import com.revenuecat.purchases.kmp.result.awaitOfferingsResult
import com.revenuecat.purchases.kmp.result.awaitPurchaseResult
import com.revenuecat.purchases.kmp.result.awaitRestoreResult
import io.github.b150005.skeinly.domain.subscription.PaywallOffering
import io.github.b150005.skeinly.domain.subscription.PaywallPackage
import io.github.b150005.skeinly.domain.subscription.PaywallPeriod
import io.github.b150005.skeinly.domain.subscription.PurchaseResult
import io.github.b150005.skeinly.domain.subscription.RestoreResult
import io.github.b150005.skeinly.domain.subscription.RevenueCatService
import kotlinx.coroutines.CancellationException

/**
 * Phase 41.3 (ADR-016 §6 §41.3) — production [RevenueCatService] backed
 * by `purchases-kmp-core` v2.10.x's `Purchases.sharedInstance` singleton.
 *
 * **Lifecycle invariant.** `Purchases.sharedInstance` is configured once
 * at Application init via [RevenueCatBootstrap.configure]. Accessing
 * `sharedInstance` before configure() throws an `IllegalStateException`
 * that propagates as `Result.failure(...)` and surfaces as
 * [PurchaseResult.Failed] / [RestoreResult.Failed] in the UI.
 *
 * **Why this isn't `expect/actual`.** `purchases-kmp-core` already
 * provides a common `Purchases.sharedInstance` API that bridges to
 * `PurchasesHybridCommon` on iOS and `purchases-android` on Android. The
 * platform abstraction is upstream's job — Skeinly's wrapper exists
 * only for testability. The [RevenueCatService] interface lets tests
 * inject a fake without booting the SDK.
 *
 * **User-cancellation contract.** `purchases-kmp-result`'s
 * `awaitPurchaseResult` returns `Result.failure(PurchasesTransactionException)`
 * which carries a `userCancelled: Boolean` field directly — no reflection
 * required. We map the cancelled case to [PurchaseResult.UserCancelled]
 * so the paywall UI doesn't render an error toast (cancellation is the
 * expected exit path), only genuine failures.
 */
class RevenueCatServiceImpl : RevenueCatService {
    /**
     * Cached most-recently-fetched [Offerings] so [resolvePackage] can
     * look up the native [Package] for purchase without making a second
     * SDK round-trip. The RevenueCat SDK already caches offerings
     * internally, so this is largely a cycle-saving precaution rather
     * than a correctness fix; it also makes the lookup deterministic
     * regardless of any future SDK cache-eviction behavior.
     *
     * Mutation happens only inside [getOfferings] which is called from
     * a suspending coroutine; concurrent purchases are gated upstream
     * by the [io.github.b150005.skeinly.ui.paywall.PaywallViewModel]'s
     * `isPurchasing` flag so no atomic discipline is required here.
     */
    private var lastOfferings: Offerings? = null

    override suspend fun getOfferings(): Result<PaywallOffering?> =
        try {
            Purchases.sharedInstance
                .awaitOfferingsResult()
                .map { offerings ->
                    lastOfferings = offerings
                    val current = offerings.current ?: return@map null
                    current.toDomain()
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun purchase(pkg: PaywallPackage): PurchaseResult =
        try {
            // Resolve the platform package by identifier. The offering
            // surfaced from getOfferings() must still be live in the SDK
            // cache (offerings are immutable per session). If the package
            // has somehow vanished (offering hot-swap mid-session is
            // implausible but defended), surface as Failed.
            val nativePackage = resolvePackage(pkg.identifier)
            if (nativePackage == null) {
                return PurchaseResult.Failed(
                    "Package ${pkg.identifier} is no longer available; please retry.",
                )
            }
            val result = Purchases.sharedInstance.awaitPurchaseResult(nativePackage)
            result.fold(
                onSuccess = { successfulPurchase ->
                    // Apple StoreKit / Google Play Billing already verified the
                    // transaction. RevenueCat's CustomerInfo on the wrapper
                    // carries the post-purchase entitlement state. The product
                    // id we report is whichever active subscription emerged
                    // (may differ from `pkg.productId` on a crossgrade — RevenueCat
                    // resolves SKU choice server-side).
                    val activeSubscription =
                        successfulPurchase.customerInfo.activeSubscriptions.firstOrNull()
                            ?: pkg.productId
                    PurchaseResult.Success(productId = activeSubscription)
                },
                onFailure = { throwable ->
                    if (throwable is PurchasesTransactionException && throwable.userCancelled) {
                        PurchaseResult.UserCancelled
                    } else {
                        PurchaseResult.Failed(throwable.message ?: "Purchase failed.")
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PurchaseResult.Failed(e.message ?: "Purchase failed.")
        }

    override suspend fun restorePurchases(): RestoreResult =
        try {
            val result = Purchases.sharedInstance.awaitRestoreResult()
            result.fold(
                onSuccess = { customerInfo ->
                    val proActive = customerInfo.entitlements.active.containsKey(PRO_ENTITLEMENT_ID)
                    RestoreResult.Success(proActive = proActive)
                },
                onFailure = { throwable ->
                    RestoreResult.Failed(throwable.message ?: "Restore failed.")
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RestoreResult.Failed(e.message ?: "Restore failed.")
        }

    /**
     * Phase 39 closed beta prep — wraps `Purchases.sharedInstance.awaitLogInResult(userId)`.
     *
     * Returns `Result.success(Unit)` when the SDK is not configured
     * (local-dev build without `REVENUECAT_API_KEY_*`); the no-op short-
     * circuit keeps the auth flow uncoupled from RevenueCat configuration
     * state. Otherwise maps the SDK's `Result<SuccessfulLogin>` to
     * `Result<Unit>` — callers (the auth bridge) don't need the
     * customer-info / isCreated detail; they only care whether
     * identification succeeded or failed.
     */
    override suspend fun identifyUser(userId: String): Result<Unit> =
        try {
            if (!RevenueCatBootstrap.isConfigured) {
                Result.success(Unit)
            } else {
                Purchases.sharedInstance.awaitLogInResult(userId).map { Unit }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Phase 39 closed beta prep — wraps `Purchases.sharedInstance.awaitLogOutResult()`.
     *
     * Same no-op shortcut as [identifyUser] when the SDK is not
     * configured. Discards the returned `CustomerInfo` (callers only
     * care about success/failure).
     */
    override suspend fun logOutUser(): Result<Unit> =
        try {
            if (!RevenueCatBootstrap.isConfigured) {
                Result.success(Unit)
            } else {
                Purchases.sharedInstance.awaitLogOutResult().map { Unit }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun resolvePackage(identifier: String): Package? {
        // Prefer the cached offerings to avoid a second SDK round-trip.
        // Falls back to a fresh fetch when the cache is empty (cold-start
        // edge case where purchase is dispatched before any successful
        // getOfferings — implausible from the ViewModel's flow but defended).
        val offerings =
            lastOfferings
                ?: Purchases.sharedInstance
                    .awaitOfferingsResult()
                    .getOrNull()
                    ?.also { lastOfferings = it }
                ?: return null
        return offerings.current?.availablePackages?.firstOrNull { it.identifier == identifier }
    }

    private fun Offering.toDomain(): PaywallOffering =
        PaywallOffering(
            identifier = identifier,
            packages = availablePackages.map { it.toDomain() },
        )

    private fun Package.toDomain(): PaywallPackage =
        PaywallPackage(
            identifier = identifier,
            productId = storeProduct.id,
            period = packageType.toDomainPeriod(),
            priceString = storeProduct.price.formatted,
            priceMicros = storeProduct.price.amountMicros,
            currencyCode = storeProduct.price.currencyCode,
        )

    private fun PackageType.toDomainPeriod(): PaywallPeriod =
        when (this) {
            PackageType.MONTHLY -> PaywallPeriod.MONTHLY
            PackageType.ANNUAL -> PaywallPeriod.ANNUAL
            else -> PaywallPeriod.OTHER
        }

    private companion object {
        /**
         * RevenueCat dashboard entitlement id that gates Pro packs.
         * Configured per ADR-016 §1 / vendor-setup.md A0d-2. The
         * `revenuecat-webhook` Edge Function (Phase 39 prep, 2026-05-08)
         * derives the `subscriptions.status` value from RevenueCat event
         * types (see `supabase/functions/revenuecat-webhook/mapping.ts`)
         * and writes through to the `subscriptions` row via the
         * `upsert_subscription_from_webhook` RPC.
         */
        const val PRO_ENTITLEMENT_ID = "pro"
    }
}
