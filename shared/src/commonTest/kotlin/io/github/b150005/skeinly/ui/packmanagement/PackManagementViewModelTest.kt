package io.github.b150005.skeinly.ui.packmanagement

import io.github.b150005.skeinly.data.local.LocalSymbolPackDataSource
import io.github.b150005.skeinly.data.sync.PackSyncOutcome
import io.github.b150005.skeinly.data.sync.SyncCycleResult
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.db.createTestDriver
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.Subscription
import io.github.b150005.skeinly.domain.model.SubscriptionPlatform
import io.github.b150005.skeinly.domain.model.SubscriptionStatus
import io.github.b150005.skeinly.domain.model.SymbolPack
import io.github.b150005.skeinly.domain.model.SymbolPackPayload
import io.github.b150005.skeinly.domain.model.SymbolPackPayloadEntry
import io.github.b150005.skeinly.domain.model.SymbolPackTier
import io.github.b150005.skeinly.domain.repository.SubscriptionRepository
import io.github.b150005.skeinly.domain.symbol.EntitlementResolver
import io.github.b150005.skeinly.domain.symbol.SymbolCategory
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PackManagementViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val now = Instant.parse("2026-05-07T12:00:00Z")
    private val frozenClock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val json = Json { ignoreUnknownKeys = true }

    private fun pack(
        id: String,
        tier: SymbolPackTier = SymbolPackTier.FREE,
        version: Int = 1,
        displayName: String = id,
        description: String? = null,
        payloadSize: Int = 1024,
        symbolCount: Int = 4,
        updatedAt: Instant = Instant.parse("2026-05-01T00:00:00Z"),
    ) = SymbolPack(
        id = id,
        tier = tier,
        version = version,
        displayName = displayName,
        description = description,
        payloadPath = "$id/$version/payload.json",
        payloadSize = payloadSize,
        symbolCount = symbolCount,
        signedUntil = null,
        createdAt = Instant.parse("2026-05-01T00:00:00Z"),
        updatedAt = updatedAt,
    )

    private fun payloadJson(
        packId: String,
        version: Int,
    ): String =
        json.encodeToString(
            SymbolPackPayload(
                packId = packId,
                version = version,
                schemaVersion = SymbolPackPayload.CURRENT_SCHEMA_VERSION,
                symbols =
                    listOf(
                        SymbolPackPayloadEntry(
                            id = "$packId.symbol",
                            category = SymbolCategory.KNIT.name,
                            tier = SymbolPackTier.FREE,
                            pathData = "M 0 0 L 1 1",
                            jaLabel = "ja",
                            enLabel = "en",
                        ),
                    ),
            ),
        )

    private fun localStore(): LocalSymbolPackDataSource {
        val driver = createTestDriver()
        val db = SkeinlyDatabase(driver)
        return LocalSymbolPackDataSource(db, Dispatchers.Unconfined)
    }

    private fun stubResolver(isPro: Boolean): EntitlementResolver {
        val auth = FakeAuthRepository().apply { setAuthState(AuthState.Authenticated(userId = "u-1", email = "e@x")) }
        val sub: Subscription? =
            if (isPro) {
                Subscription(
                    id = "sub-1",
                    userId = "u-1",
                    platform = SubscriptionPlatform.IOS,
                    productId = "p",
                    status = SubscriptionStatus.ACTIVE,
                    originalTransactionId = "txn",
                    expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
                    lastVerifiedAt = now,
                    createdAt = now,
                    updatedAt = now,
                )
            } else {
                null
            }
        val repo = StubSubscriptionRepository(cachedFor = "u-1", sub = sub)
        return EntitlementResolver(repo, auth, frozenClock)
    }

    private suspend fun seed(
        local: LocalSymbolPackDataSource,
        pack: SymbolPack,
        downloadedVersion: Int? = null,
    ) {
        local.upsertPack(pack)
        if (downloadedVersion != null) {
            local.upsertPayload(
                packId = pack.id,
                version = downloadedVersion,
                payloadJson = payloadJson(pack.id, downloadedVersion),
            )
        }
    }

    private fun viewModel(
        local: LocalSymbolPackDataSource,
        resolver: EntitlementResolver = stubResolver(isPro = false),
        syncDispatch: PackSyncDispatch? = null,
    ) = PackManagementViewModel(
        localSymbolPackDataSource = local,
        entitlementResolver = resolver,
        syncDispatch = syncDispatch,
    )

    // ----- load --------------------------------------------------------------

    @Test
    fun `empty mirror produces empty rows and zero total`() =
        runTest {
            val local = localStore()
            val vm = viewModel(local)
            advanceUntilIdle()
            val state = vm.state.value
            assertFalse(state.isLoading)
            assertTrue(state.rows.isEmpty())
            assertEquals(0L, state.totalDownloadedBytes)
            assertNull(state.error)
        }

    @Test
    fun `loaded packs produce rows with correct status and totals`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.alpha", payloadSize = 100), downloadedVersion = 1)
            seed(local, pack("free.beta", payloadSize = 200), downloadedVersion = null)
            val vm = viewModel(local, resolver = stubResolver(isPro = false))
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(2, state.rows.size)
            assertEquals(100L, state.totalDownloadedBytes)
            assertEquals(PackStatus.Downloaded, state.rows.first { it.packId == "free.alpha" }.status)
            assertEquals(PackStatus.NotDownloaded, state.rows.first { it.packId == "free.beta" }.status)
        }

    @Test
    fun `update available when downloaded version is older than server`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.alpha", version = 3), downloadedVersion = 1)
            val vm = viewModel(local)
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .first()
            assertEquals(PackStatus.UpdateAvailable, row.status)
            assertEquals(3, row.serverVersion)
            assertEquals(1, row.downloadedVersion)
        }

    @Test
    fun `pro pack reads as locked when user is not entitled`() =
        runTest {
            val local = localStore()
            seed(local, pack("pro.cables", tier = SymbolPackTier.PRO))
            val vm = viewModel(local, resolver = stubResolver(isPro = false))
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .first()
            assertEquals(SymbolPackTier.PRO, row.tier)
            assertEquals(PackStatus.Locked, row.status)
            assertFalse(vm.state.value.isProEntitled)
        }

    @Test
    fun `pro pack reads as not downloaded when user is entitled`() =
        runTest {
            val local = localStore()
            seed(local, pack("pro.cables", tier = SymbolPackTier.PRO))
            val vm = viewModel(local, resolver = stubResolver(isPro = true))
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .first()
            assertEquals(PackStatus.NotDownloaded, row.status)
            assertTrue(vm.state.value.isProEntitled)
        }

    @Test
    fun `rows ordered free packs before pro packs both alpha by id`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.zulu", tier = SymbolPackTier.FREE))
            seed(local, pack("pro.alpha", tier = SymbolPackTier.PRO))
            seed(local, pack("free.alpha", tier = SymbolPackTier.FREE))
            val vm = viewModel(local)
            advanceUntilIdle()

            assertEquals(
                listOf("free.alpha", "free.zulu", "pro.alpha"),
                vm.state.value.rows
                    .map { it.packId },
            )
        }

    @Test
    fun `total downloaded bytes counts only packs with payloads on disk`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.a", payloadSize = 500), downloadedVersion = 1)
            seed(local, pack("free.b", payloadSize = 800), downloadedVersion = 1)
            seed(local, pack("free.c", payloadSize = 9999), downloadedVersion = null) // not on disk
            val vm = viewModel(local)
            advanceUntilIdle()

            assertEquals(1300L, vm.state.value.totalDownloadedBytes)
        }

    // ----- refresh -----------------------------------------------------------

    @Test
    fun `refresh dispatches sync and re-reads the mirror`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.a", version = 1, payloadSize = 100), downloadedVersion = 1)
            var syncCallCount = 0
            val dispatch: PackSyncDispatch = {
                syncCallCount++
                // After the sync, simulate a server-side update by bumping the local mirror.
                seed(local, pack("free.a", version = 2, payloadSize = 100), downloadedVersion = 2)
                SyncCycleResult.Completed(outcomes = listOf(PackSyncOutcome.Downloaded("free.a", version = 2)))
            }
            val vm = viewModel(local, syncDispatch = dispatch)
            advanceUntilIdle()

            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()

            assertEquals(1, syncCallCount)
            val row =
                vm.state.value.rows
                    .first()
            assertEquals(2, row.serverVersion)
            assertEquals(2, row.downloadedVersion)
            assertEquals(PackStatus.Downloaded, row.status)
            assertFalse(vm.state.value.isRefreshing)
        }

    @Test
    fun `refresh without sync dispatch still re-reads mirror`() =
        runTest {
            // Local-only dev path: no sync manager wired. The local mirror
            // just got updated by a hypothetical foreground hook, and the
            // user taps Refresh to see the result. We must NOT throw or
            // surface an error.
            val local = localStore()
            seed(local, pack("free.a"), downloadedVersion = 1)
            val vm = viewModel(local, syncDispatch = null)
            advanceUntilIdle()

            // Mutate the underlying store to simulate a downstream change.
            seed(local, pack("free.b", payloadSize = 250), downloadedVersion = 1)

            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()

            val state = vm.state.value
            assertFalse(state.isRefreshing)
            assertEquals(2, state.rows.size)
            assertNull(state.error)
        }

    @Test
    fun `refresh surfaces manifest fetch failure as error`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.a"), downloadedVersion = 1)
            val dispatch: PackSyncDispatch = {
                SyncCycleResult.ManifestFetchFailed(cause = RuntimeException("network down"))
            }
            val vm = viewModel(local, syncDispatch = dispatch)
            advanceUntilIdle()

            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()

            assertNotNull(vm.state.value.error)
            assertTrue(
                vm.state.value.error!!
                    .contains("network down"),
            )
            assertFalse(vm.state.value.isRefreshing)
        }

    @Test
    fun `refresh ignores re-entry while a cycle is in flight`() =
        runTest {
            // Gate the first dispatch so it suspends until we explicitly
            // complete the deferred — without this, UnconfinedTestDispatcher
            // runs the entire refresh synchronously and isRefreshing flips
            // back before the second tap is observable. This is the same
            // pattern as `concurrent refresh callers leave coherent
            // snapshot` in CompositeSymbolCatalogTest.
            val local = localStore()
            seed(local, pack("free.a"), downloadedVersion = 1)
            var syncCallCount = 0
            val gate = CompletableDeferred<SyncCycleResult>()
            val dispatch: PackSyncDispatch = {
                syncCallCount++
                gate.await()
            }
            val vm = viewModel(local, syncDispatch = dispatch)
            advanceUntilIdle()

            // First tap — suspends inside the dispatch.
            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()
            assertTrue(vm.state.value.isRefreshing)
            assertEquals(1, syncCallCount)

            // Second tap while the first is suspended — short-circuits via
            // the in-flight guard, dispatch is NOT called again.
            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()
            assertEquals(1, syncCallCount)

            // Release the first refresh and let it complete cleanly.
            gate.complete(SyncCycleResult.Skipped(reason = "test"))
            advanceUntilIdle()
            assertFalse(vm.state.value.isRefreshing)
        }

    @Test
    fun `refresh surfaces unexpected exception as error and clears refreshing`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.a"), downloadedVersion = 1)
            val dispatch: PackSyncDispatch = {
                throw RuntimeException("boom")
            }
            val vm = viewModel(local, syncDispatch = dispatch)
            advanceUntilIdle()

            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()

            assertEquals("boom", vm.state.value.error)
            assertFalse(vm.state.value.isRefreshing)
        }

    @Test
    fun `clear error nulls the error field without touching other state`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.a"), downloadedVersion = 1)
            val dispatch: PackSyncDispatch = {
                SyncCycleResult.ManifestFetchFailed(cause = RuntimeException("nope"))
            }
            val vm = viewModel(local, syncDispatch = dispatch)
            advanceUntilIdle()
            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)

            val rowsBefore = vm.state.value.rows
            vm.onEvent(PackManagementEvent.ClearError)

            assertNull(vm.state.value.error)
            assertEquals(rowsBefore, vm.state.value.rows)
        }

    @Test
    fun `loaded pack carries description and symbol count and payload size`() =
        runTest {
            val local = localStore()
            seed(
                local,
                pack(
                    id = "free.beginner",
                    displayName = "Beginner",
                    description = "Starter knit symbols",
                    payloadSize = 4096,
                    symbolCount = 12,
                ),
                downloadedVersion = 1,
            )
            val vm = viewModel(local)
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .first()
            assertEquals("Beginner", row.displayName)
            assertEquals("Starter knit symbols", row.description)
            assertEquals(12, row.symbolCount)
            assertEquals(4096, row.payloadSize)
        }

    private class StubSubscriptionRepository(
        private val cachedFor: String?,
        private val sub: Subscription?,
    ) : SubscriptionRepository {
        override fun cachedActiveSubscription(userId: String): Subscription? = if (userId == cachedFor) sub else null

        override fun observeActiveSubscription(userId: String): Flow<Subscription?> = flowOf(if (userId == cachedFor) sub else null)

        override suspend fun refresh(userId: String): Result<Subscription?> = Result.success(sub)

        override suspend fun clearLocalCache(userId: String) = Unit
    }
}
