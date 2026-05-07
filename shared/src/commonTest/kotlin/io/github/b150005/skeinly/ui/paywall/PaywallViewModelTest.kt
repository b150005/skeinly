package io.github.b150005.skeinly.ui.paywall

import app.cash.turbine.test
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.PaywallDismissReason
import io.github.b150005.skeinly.data.analytics.PaywallTrigger
import io.github.b150005.skeinly.data.analytics.RecordingAnalyticsTracker
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.Subscription
import io.github.b150005.skeinly.domain.repository.SubscriptionRepository
import io.github.b150005.skeinly.domain.subscription.PaywallOffering
import io.github.b150005.skeinly.domain.subscription.PaywallPackage
import io.github.b150005.skeinly.domain.subscription.PaywallPeriod
import io.github.b150005.skeinly.domain.subscription.PurchaseResult
import io.github.b150005.skeinly.domain.subscription.RestoreResult
import io.github.b150005.skeinly.domain.subscription.RevenueCatService
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaywallViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val monthly =
        PaywallPackage(
            identifier = "\$rc_monthly",
            productId = "skeinly_pro_monthly",
            period = PaywallPeriod.MONTHLY,
            priceString = "¥800",
            priceMicros = 800_000_000L,
            currencyCode = "JPY",
        )
    private val annual =
        PaywallPackage(
            identifier = "\$rc_annual",
            productId = "skeinly_pro_annual",
            period = PaywallPeriod.ANNUAL,
            priceString = "¥6000",
            priceMicros = 6_000_000_000L,
            currencyCode = "JPY",
        )
    private val offering =
        PaywallOffering(identifier = "default", packages = listOf(monthly, annual))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads offerings and defaults to annual when present`() =
        runTest(testDispatcher) {
            val rig = makeRig(offering = offering)
            val state = rig.viewModel.state.value
            assertEquals(false, state.isLoading)
            assertEquals(offering, state.offering)
            assertEquals(annual.identifier, state.selectedPackageId)
        }

    @Test
    fun `init defaults to monthly when no annual is present`() =
        runTest(testDispatcher) {
            val monthlyOnly = offering.copy(packages = listOf(monthly))
            val rig = makeRig(offering = monthlyOnly)
            assertEquals(monthly.identifier, rig.viewModel.state.value.selectedPackageId)
        }

    @Test
    fun `init defaults to first package when neither monthly nor annual`() =
        runTest(testDispatcher) {
            val custom =
                monthly.copy(
                    identifier = "lifetime",
                    period = PaywallPeriod.OTHER,
                )
            val customOffering = offering.copy(packages = listOf(custom))
            val rig = makeRig(offering = customOffering)
            assertEquals(custom.identifier, rig.viewModel.state.value.selectedPackageId)
        }

    @Test
    fun `init records PaywallOpened with the supplied trigger`() =
        runTest(testDispatcher) {
            val tracker = RecordingAnalyticsTracker()
            makeRig(
                offering = offering,
                trigger = PaywallTrigger.AutoLockInEditor,
                tracker = tracker,
            )
            val opened = tracker.captured.firstOrNull { it is AnalyticsEvent.PaywallOpened }
            assertNotNull(opened)
            assertEquals(
                PaywallTrigger.AutoLockInEditor,
                (opened as AnalyticsEvent.PaywallOpened).trigger,
            )
        }

    @Test
    fun `getOfferings failure surfaces error and clears offering`() =
        runTest(testDispatcher) {
            val rig = makeRig(offeringsResult = Result.failure(RuntimeException("network down")))
            val state = rig.viewModel.state.value
            assertEquals(false, state.isLoading)
            assertNull(state.offering)
            assertNotNull(state.error)
        }

    @Test
    fun `SelectPackage updates selectedPackageId`() =
        runTest(testDispatcher) {
            val rig = makeRig(offering = offering)
            rig.viewModel.onEvent(PaywallEvent.SelectPackage(monthly.identifier))
            assertEquals(monthly.identifier, rig.viewModel.state.value.selectedPackageId)
        }

    @Test
    fun `ConfirmPurchase success runs post-purchase chain and emits PurchaseConfirmed`() =
        runTest(testDispatcher) {
            val service = StubRevenueCatService(offering = offering)
            service.purchaseResultByIdentifier[annual.identifier] =
                PurchaseResult.Success(productId = "skeinly_pro_annual")
            val auth = FakeAuthRepository().also { it.setSignedIn() }
            val sub = MutableSubscriptionRepository()
            val refreshFlag = mutableListOf<Boolean>()
            val syncFlag = mutableListOf<Boolean>()
            val tracker = RecordingAnalyticsTracker()
            val viewModel =
                PaywallViewModel(
                    trigger = PaywallTrigger.Settings,
                    revenueCatService = service,
                    subscriptionRepository = sub,
                    authRepository = auth,
                    refreshCatalog = { refreshFlag.add(true) },
                    syncPacks = { syncFlag.add(true) },
                    analyticsTracker = tracker,
                )
            viewModel.navEvents.test {
                viewModel.onEvent(PaywallEvent.ConfirmPurchase(annual.identifier))
                val nav = awaitItem()
                assertTrue(nav is PaywallNavEvent.PurchaseConfirmed)
                assertEquals("skeinly_pro_annual", nav.productId)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, sub.refreshCount)
            assertEquals(1, refreshFlag.size)
            assertEquals(1, syncFlag.size)
            assertTrue(tracker.captured.any { it is AnalyticsEvent.PurchaseSubscribed })
            assertEquals(false, viewModel.state.value.isPurchasing)
        }

    @Test
    fun `ConfirmPurchase user-cancelled emits PaywallDismissed without nav event`() =
        runTest(testDispatcher) {
            val service = StubRevenueCatService(offering = offering)
            service.purchaseResultByIdentifier[annual.identifier] = PurchaseResult.UserCancelled
            val tracker = RecordingAnalyticsTracker()
            val rig = makeRigWithService(service = service, tracker = tracker)
            rig.viewModel.onEvent(PaywallEvent.ConfirmPurchase(annual.identifier))
            assertEquals(false, rig.viewModel.state.value.isPurchasing)
            assertNull(rig.viewModel.state.value.error)
            val dismissed =
                tracker.captured.firstOrNull {
                    it is AnalyticsEvent.PaywallDismissed && it.reason == PaywallDismissReason.PurchaseCancel
                }
            assertNotNull(dismissed)
        }

    @Test
    fun `ConfirmPurchase failed surfaces error and analytics event`() =
        runTest(testDispatcher) {
            val service = StubRevenueCatService(offering = offering)
            service.purchaseResultByIdentifier[annual.identifier] =
                PurchaseResult.Failed("network error")
            val tracker = RecordingAnalyticsTracker()
            val rig = makeRigWithService(service = service, tracker = tracker)
            rig.viewModel.onEvent(PaywallEvent.ConfirmPurchase(annual.identifier))
            val state = rig.viewModel.state.value
            assertEquals(false, state.isPurchasing)
            assertEquals("network error", state.error)
            val dismissed =
                tracker.captured.firstOrNull {
                    it is AnalyticsEvent.PaywallDismissed && it.reason == PaywallDismissReason.PurchaseFailed
                }
            assertNotNull(dismissed)
        }

    @Test
    fun `ConfirmPurchase with unknown package surfaces error without invoking SDK`() =
        runTest(testDispatcher) {
            val service = StubRevenueCatService(offering = offering)
            val rig = makeRigWithService(service = service)
            rig.viewModel.onEvent(PaywallEvent.ConfirmPurchase("not_a_real_package"))
            assertNotNull(rig.viewModel.state.value.error)
            assertEquals(0, service.purchaseCallCount)
        }

    @Test
    fun `RestorePurchases success with pro active runs post-purchase chain and emits RestoredWithPro`() =
        runTest(testDispatcher) {
            val service = StubRevenueCatService(offering = offering)
            service.restoreResult = RestoreResult.Success(proActive = true)
            val auth = FakeAuthRepository().also { it.setSignedIn() }
            val sub = MutableSubscriptionRepository()
            val refreshFlag = mutableListOf<Boolean>()
            val viewModel =
                PaywallViewModel(
                    trigger = PaywallTrigger.Settings,
                    revenueCatService = service,
                    subscriptionRepository = sub,
                    authRepository = auth,
                    refreshCatalog = { refreshFlag.add(true) },
                    syncPacks = null,
                )
            viewModel.navEvents.test {
                viewModel.onEvent(PaywallEvent.RestorePurchases)
                val nav = awaitItem()
                assertTrue(nav is PaywallNavEvent.RestoredWithPro)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, sub.refreshCount)
            assertEquals(1, refreshFlag.size)
            assertEquals(false, viewModel.state.value.isRestoring)
        }

    @Test
    fun `RestorePurchases success without pro emits RestoredEmpty without running chain`() =
        runTest(testDispatcher) {
            val service = StubRevenueCatService(offering = offering)
            service.restoreResult = RestoreResult.Success(proActive = false)
            val sub = MutableSubscriptionRepository()
            val refreshFlag = mutableListOf<Boolean>()
            val viewModel =
                PaywallViewModel(
                    trigger = PaywallTrigger.Settings,
                    revenueCatService = service,
                    subscriptionRepository = sub,
                    authRepository = FakeAuthRepository(),
                    refreshCatalog = { refreshFlag.add(true) },
                )
            viewModel.navEvents.test {
                viewModel.onEvent(PaywallEvent.RestorePurchases)
                val nav = awaitItem()
                assertTrue(nav is PaywallNavEvent.RestoredEmpty)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(0, sub.refreshCount)
            assertEquals(0, refreshFlag.size)
        }

    @Test
    fun `RestorePurchases failed surfaces error and stays open`() =
        runTest(testDispatcher) {
            val service = StubRevenueCatService(offering = offering)
            service.restoreResult = RestoreResult.Failed("network down")
            val rig = makeRigWithService(service = service)
            rig.viewModel.onEvent(PaywallEvent.RestorePurchases)
            val state = rig.viewModel.state.value
            assertEquals("network down", state.error)
            assertEquals(false, state.isRestoring)
        }

    @Test
    fun `Dismiss emits Dismissed nav event with PaywallDismissed analytics`() =
        runTest(testDispatcher) {
            val tracker = RecordingAnalyticsTracker()
            val rig =
                makeRig(
                    offering = offering,
                    trigger = PaywallTrigger.Settings,
                    tracker = tracker,
                )
            rig.viewModel.navEvents.test {
                rig.viewModel.onEvent(PaywallEvent.Dismiss)
                val nav = awaitItem()
                assertTrue(nav is PaywallNavEvent.Dismissed)
                cancelAndIgnoreRemainingEvents()
            }
            val dismissed =
                tracker.captured.firstOrNull {
                    it is AnalyticsEvent.PaywallDismissed && it.reason == PaywallDismissReason.UserCancel
                }
            assertNotNull(dismissed)
        }

    @Test
    fun `ClearError clears the error state`() =
        runTest(testDispatcher) {
            val rig = makeRig(offeringsResult = Result.failure(RuntimeException("boom")))
            assertNotNull(rig.viewModel.state.value.error)
            rig.viewModel.onEvent(PaywallEvent.ClearError)
            assertNull(rig.viewModel.state.value.error)
        }

    @Test
    fun `post-purchase chain isolates failures so later steps still run`() =
        runTest(testDispatcher) {
            val service = StubRevenueCatService(offering = offering)
            service.purchaseResultByIdentifier[annual.identifier] =
                PurchaseResult.Success(productId = "skeinly_pro_annual")
            val auth = FakeAuthRepository().also { it.setSignedIn() }
            val sub =
                MutableSubscriptionRepository().apply {
                    nextRefreshError = RuntimeException("subscription refresh failed")
                }
            val refreshFlag = mutableListOf<Boolean>()
            val syncFlag = mutableListOf<Boolean>()
            val viewModel =
                PaywallViewModel(
                    trigger = PaywallTrigger.Settings,
                    revenueCatService = service,
                    subscriptionRepository = sub,
                    authRepository = auth,
                    refreshCatalog = {
                        refreshFlag.add(true)
                        throw RuntimeException("catalog refresh failed")
                    },
                    syncPacks = { syncFlag.add(true) },
                )
            viewModel.navEvents.test {
                viewModel.onEvent(PaywallEvent.ConfirmPurchase(annual.identifier))
                val nav = awaitItem()
                assertTrue(nav is PaywallNavEvent.PurchaseConfirmed)
                cancelAndIgnoreRemainingEvents()
            }
            // Subscription refresh threw, but catalog refresh + pack sync STILL ran.
            assertEquals(1, sub.refreshCount)
            assertEquals(1, refreshFlag.size)
            assertEquals(1, syncFlag.size)
        }

    private fun makeRig(
        offering: PaywallOffering? = null,
        offeringsResult: Result<PaywallOffering?>? = null,
        trigger: PaywallTrigger = PaywallTrigger.Settings,
        tracker: io.github.b150005.skeinly.data.analytics.AnalyticsTracker? = null,
    ): Rig {
        val service =
            StubRevenueCatService(
                offering = offering,
                offeringsResultOverride = offeringsResult,
            )
        return makeRigWithService(service = service, trigger = trigger, tracker = tracker)
    }

    private fun makeRigWithService(
        service: StubRevenueCatService,
        trigger: PaywallTrigger = PaywallTrigger.Settings,
        tracker: io.github.b150005.skeinly.data.analytics.AnalyticsTracker? = null,
    ): Rig {
        val auth = FakeAuthRepository().also { it.setSignedIn() }
        val sub = MutableSubscriptionRepository()
        val viewModel =
            PaywallViewModel(
                trigger = trigger,
                revenueCatService = service,
                subscriptionRepository = sub,
                authRepository = auth,
                refreshCatalog = { /* no-op */ },
                syncPacks = { /* no-op */ },
                analyticsTracker = tracker,
            )
        return Rig(viewModel, service, sub)
    }

    private data class Rig(
        val viewModel: PaywallViewModel,
        val service: StubRevenueCatService,
        val subscriptionRepository: MutableSubscriptionRepository,
    )

    private fun FakeAuthRepository.setSignedIn() {
        setAuthState(
            AuthState.Authenticated(userId = "test-user-id", email = "test@example.com"),
        )
    }
}

/**
 * In-memory [RevenueCatService] for ViewModel tests.
 */
private class StubRevenueCatService(
    private val offering: PaywallOffering? = null,
    private val offeringsResultOverride: Result<PaywallOffering?>? = null,
) : RevenueCatService {
    val purchaseResultByIdentifier: MutableMap<String, PurchaseResult> = mutableMapOf()
    var restoreResult: RestoreResult = RestoreResult.Failed("not configured")
    var purchaseCallCount: Int = 0
        private set

    override suspend fun getOfferings(): Result<PaywallOffering?> = offeringsResultOverride ?: Result.success(offering)

    override suspend fun purchase(pkg: PaywallPackage): PurchaseResult {
        purchaseCallCount++
        return purchaseResultByIdentifier[pkg.identifier]
            ?: PurchaseResult.Failed("unknown package")
    }

    override suspend fun restorePurchases(): RestoreResult = restoreResult
}

/**
 * In-memory [SubscriptionRepository] tracking refresh invocations + an
 * optional throw on next refresh — used by the post-purchase
 * isolation-of-failures test.
 */
private class MutableSubscriptionRepository : SubscriptionRepository {
    var refreshCount: Int = 0
        private set
    var nextRefreshError: Throwable? = null

    override fun cachedActiveSubscription(userId: String): Subscription? = null

    override fun observeActiveSubscription(userId: String): Flow<Subscription?> = flowOf(null)

    override suspend fun refresh(userId: String): Result<Subscription?> {
        refreshCount++
        nextRefreshError?.let { throw it }
        return Result.success(null)
    }

    override suspend fun clearLocalCache(userId: String) {}
}
