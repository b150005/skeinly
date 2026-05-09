package io.github.b150005.skeinly.ui.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.ClickActionId
import io.github.b150005.skeinly.data.analytics.PaywallDismissReason
import io.github.b150005.skeinly.data.analytics.PaywallTrigger
import io.github.b150005.skeinly.data.analytics.Screen
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.SubscriptionRepository
import io.github.b150005.skeinly.domain.subscription.PaywallOffering
import io.github.b150005.skeinly.domain.subscription.PaywallPackage
import io.github.b150005.skeinly.domain.subscription.PurchaseResult
import io.github.b150005.skeinly.domain.subscription.RestoreResult
import io.github.b150005.skeinly.domain.subscription.RevenueCatService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 41.3 (ADR-016 §6 §41.3) — drives the paywall sheet surfaced from
 * Settings → "Subscribe to Pro" and (rare-fire) the chart editor's
 * SelectPaletteSymbol path when [CompositeSymbolCatalog.get] returns
 * null.
 *
 * **Lifecycle:**
 * - `init` fires `loadOfferings` immediately; the sheet renders a
 *   spinner state until the SDK responds.
 * - `Purchase(pkg)` initiates the platform Store dialog; suspends until
 *   user completes / cancels / errors.
 * - On purchase Success: dispatch the post-purchase callback chain —
 *   `SubscriptionRepository.refresh` → `CompositeSymbolCatalog.refresh`
 *   → `SymbolPackSyncManager.sync` — so the in-memory entitlement
 *   gates flip and any Pro packs not yet downloaded are pulled. Emits
 *   `PaywallNavEvent.PurchaseConfirmed` so the screen layer can dismiss
 *   itself with a "Welcome!" toast.
 *
 * **Failure tolerance:** Each link in the post-purchase chain is wrapped
 * in its own try/catch; a refresh-subscription failure does NOT prevent
 * the chain from continuing to refresh-catalog or sync-packs (the user
 * has paid; we want to surface Pro access ASAP and let server-side
 * reconciliation patch any temporarily-stale state). Failures log to
 * stderr; future Phase work may surface them via Sentry breadcrumbs.
 */
data class PaywallState(
    /** True between `init` / `RefreshOfferings` and the SDK response. */
    val isLoading: Boolean = false,
    /** Loaded offering, null while loading or on fetch failure. */
    val offering: PaywallOffering? = null,
    /** Currently selected package id; defaults to annual when both are present. */
    val selectedPackageId: String? = null,
    /** True between `Purchase` dispatch and the platform Store callback. */
    val isPurchasing: Boolean = false,
    /** True between `RestorePurchases` dispatch and the SDK callback. */
    val isRestoring: Boolean = false,
    /** Inline error message shown in the paywall body — null when no error. */
    val error: String? = null,
)

sealed interface PaywallEvent {
    data object RefreshOfferings : PaywallEvent

    data class SelectPackage(
        val packageId: String,
    ) : PaywallEvent

    data class ConfirmPurchase(
        val packageId: String,
    ) : PaywallEvent

    data object RestorePurchases : PaywallEvent

    data object ClearError : PaywallEvent

    data object Dismiss : PaywallEvent
}

/**
 * One-shot navigation events emitted from the paywall ViewModel to the
 * screen layer. Buffered Channel matches existing project precedent
 * (`SettingsViewModel.toastEvents`, `BugReportPreviewViewModel`'s
 * implicit submit-then-dismiss).
 */
sealed interface PaywallNavEvent {
    /** Purchase succeeded; screen dismisses + shows a welcome toast. */
    data class PurchaseConfirmed(
        val productId: String,
    ) : PaywallNavEvent

    /** Restore succeeded with prior Pro entitlement; screen dismisses + welcome-back toast. */
    data object RestoredWithPro : PaywallNavEvent

    /** Restore succeeded with no Pro entitlement; screen shows a "no purchases found" toast. */
    data object RestoredEmpty : PaywallNavEvent

    /** User dismissed via Cancel button or backdrop tap; screen pops the route. */
    data object Dismissed : PaywallNavEvent
}

/**
 * Suspending callback the ViewModel invokes after a successful purchase
 * (and after a successful restore that surfaces an active Pro entitlement)
 * to refresh the in-memory symbol catalog so Pro symbols start rendering.
 *
 * Wired at the DI boundary as `compositeSymbolCatalog::refresh`. Lambda
 * indirection keeps the ViewModel testable without booting the full
 * catalog graph in commonTest — `CompositeSymbolCatalog` is a concrete
 * class with non-trivial init dependencies (DefaultSymbolCatalog +
 * LocalSymbolPackDataSource + EntitlementResolver), and the only thing
 * this ViewModel needs from it is `refresh()`.
 */
typealias CatalogRefresh = suspend () -> Unit

/**
 * Suspending callback the ViewModel invokes after a successful purchase
 * to pull any Pro packs not yet downloaded.
 *
 * Wired at the DI boundary as `symbolPackSyncManager::sync`. Nullable
 * with default-null because [SymbolPackSyncManager][io.github.b150005.skeinly.data.sync.SymbolPackSyncManager]
 * is registered conditionally (only when Supabase is configured) — local-
 * only dev builds skip pack sync entirely.
 */
typealias PackSync = suspend () -> Unit

class PaywallViewModel(
    private val trigger: PaywallTrigger,
    private val revenueCatService: RevenueCatService,
    private val subscriptionRepository: SubscriptionRepository,
    private val authRepository: AuthRepository,
    private val refreshCatalog: CatalogRefresh,
    private val syncPacks: PackSync? = null,
    private val analyticsTracker: AnalyticsTracker? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(PaywallState(isLoading = true))
    val state: StateFlow<PaywallState> = _state.asStateFlow()

    private val _navEvents = Channel<PaywallNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<PaywallNavEvent> = _navEvents.receiveAsFlow()

    init {
        analyticsTracker?.track(AnalyticsEvent.PaywallOpened(trigger))
        viewModelScope.launch { loadOfferings() }
    }

    fun onEvent(event: PaywallEvent) {
        when (event) {
            PaywallEvent.RefreshOfferings -> viewModelScope.launch { loadOfferings() }
            is PaywallEvent.SelectPackage -> {
                analyticsTracker?.track(
                    AnalyticsEvent.ClickAction(ClickActionId.SelectPaywallPackage, Screen.Paywall),
                )
                _state.update { it.copy(selectedPackageId = event.packageId) }
            }
            is PaywallEvent.ConfirmPurchase -> {
                analyticsTracker?.track(
                    AnalyticsEvent.ClickAction(ClickActionId.ConfirmPurchase, Screen.Paywall),
                )
                viewModelScope.launch { confirmPurchase(event.packageId) }
            }
            PaywallEvent.RestorePurchases -> {
                analyticsTracker?.track(
                    AnalyticsEvent.ClickAction(ClickActionId.RestorePurchases, Screen.Paywall),
                )
                viewModelScope.launch { restorePurchases() }
            }
            PaywallEvent.ClearError -> _state.update { it.copy(error = null) }
            PaywallEvent.Dismiss -> dismissUserCancel()
        }
    }

    private suspend fun loadOfferings() {
        _state.update { it.copy(isLoading = true, error = null) }
        val result = revenueCatService.getOfferings()
        result.fold(
            onSuccess = { offering ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        offering = offering,
                        // Default selection: annual if present, else monthly, else first.
                        selectedPackageId =
                            offering?.annual?.identifier
                                ?: offering?.monthly?.identifier
                                ?: offering?.packages?.firstOrNull()?.identifier,
                    )
                }
            },
            onFailure = { e ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        offering = null,
                        error = e.message ?: "Subscriptions are unavailable right now.",
                    )
                }
            },
        )
    }

    private suspend fun confirmPurchase(packageId: String) {
        val current = _state.value
        val pkg = current.offering?.packages?.firstOrNull { it.identifier == packageId }
        if (pkg == null) {
            _state.update { it.copy(error = "Selected package is no longer available; please retry.") }
            return
        }
        _state.update { it.copy(isPurchasing = true, error = null) }
        val purchaseResult = revenueCatService.purchase(pkg)
        when (purchaseResult) {
            is PurchaseResult.Success -> handlePurchaseSuccess(pkg, purchaseResult.productId)
            PurchaseResult.UserCancelled -> {
                _state.update { it.copy(isPurchasing = false) }
                analyticsTracker?.track(
                    AnalyticsEvent.PaywallDismissed(
                        trigger,
                        PaywallDismissReason.PurchaseCancel,
                    ),
                )
            }
            is PurchaseResult.Failed -> {
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        error = purchaseResult.message,
                    )
                }
                analyticsTracker?.track(
                    AnalyticsEvent.PaywallDismissed(
                        trigger,
                        PaywallDismissReason.PurchaseFailed,
                    ),
                )
            }
        }
    }

    private suspend fun handlePurchaseSuccess(
        pkg: PaywallPackage,
        productId: String,
    ) {
        analyticsTracker?.track(AnalyticsEvent.PurchaseSubscribed(productId))
        // Post-purchase callback chain. Each step is best-effort — a failure
        // in one link does not prevent the next from running. The user has
        // paid; we want Pro access surfaced ASAP. Server-side reconciliation
        // (RevenueCat → `revenuecat-webhook` Edge Function → `subscriptions`
        // row → next refresh) will patch any residual local-cache divergence.
        val userId = authRepository.getCurrentUserId()
        if (userId != null) {
            try {
                subscriptionRepository.refresh(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Non-fatal — the Realtime push to subscriptions will land
                // shortly. Log so the failure is visible in stderr / Sentry.
                println("PaywallViewModel: post-purchase subscription refresh failed: $e")
            }
        }
        try {
            refreshCatalog()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("PaywallViewModel: post-purchase catalog refresh failed: $e")
        }
        try {
            syncPacks?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("PaywallViewModel: post-purchase pack sync failed: $e")
        }
        _state.update { it.copy(isPurchasing = false) }
        _navEvents.trySend(PaywallNavEvent.PurchaseConfirmed(productId = productId))
    }

    private suspend fun restorePurchases() {
        _state.update { it.copy(isRestoring = true, error = null) }
        val restoreResult = revenueCatService.restorePurchases()
        when (restoreResult) {
            is RestoreResult.Success -> {
                analyticsTracker?.track(AnalyticsEvent.PurchasesRestored(restoreResult.proActive))
                if (restoreResult.proActive) {
                    // Run the same post-purchase chain — entitlement just flipped Pro.
                    val userId = authRepository.getCurrentUserId()
                    if (userId != null) {
                        try {
                            subscriptionRepository.refresh(userId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            println("PaywallViewModel: post-restore subscription refresh failed: $e")
                        }
                    }
                    try {
                        refreshCatalog()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("PaywallViewModel: post-restore catalog refresh failed: $e")
                    }
                    try {
                        syncPacks?.invoke()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("PaywallViewModel: post-restore pack sync failed: $e")
                    }
                    _state.update { it.copy(isRestoring = false) }
                    _navEvents.trySend(PaywallNavEvent.RestoredWithPro)
                } else {
                    _state.update { it.copy(isRestoring = false) }
                    _navEvents.trySend(PaywallNavEvent.RestoredEmpty)
                }
            }
            is RestoreResult.Failed -> {
                _state.update {
                    it.copy(
                        isRestoring = false,
                        error = restoreResult.message,
                    )
                }
            }
        }
    }

    private fun dismissUserCancel() {
        analyticsTracker?.track(
            AnalyticsEvent.PaywallDismissed(
                trigger,
                PaywallDismissReason.UserCancel,
            ),
        )
        _navEvents.trySend(PaywallNavEvent.Dismissed)
    }
}
