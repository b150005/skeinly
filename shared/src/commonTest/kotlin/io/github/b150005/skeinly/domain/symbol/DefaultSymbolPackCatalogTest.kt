package io.github.b150005.skeinly.domain.symbol

import io.github.b150005.skeinly.data.local.LocalSymbolPackDataSource
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
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Phase 41.5 (ADR-016 §41.5.3) — gate-site tests for [DefaultSymbolPackCatalog].
 *
 * Pre-41.5 these lived in [io.github.b150005.skeinly.ui.packmanagement.PackManagementViewModelTest]
 * because the ViewModel owned the gate. After moving the gate to the
 * catalog per §41.5.1, the ViewModel test set narrowed to state-machine
 * concerns and the gate fold + ordering + total-bytes math moved here.
 */
class DefaultSymbolPackCatalogTest {
    private val now = Instant.parse("2026-05-08T12:00:00Z")
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

    private fun payloadBody(
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
                            category = "knit",
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
                payloadJson = payloadBody(pack.id, downloadedVersion),
            )
        }
    }

    // ----- empty path --------------------------------------------------------

    @Test
    fun `empty mirror produces empty rows and zero total`() =
        runTest {
            val local = localStore()
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = false))

            val inventory = catalog.listInventory()

            assertTrue(inventory.rows.isEmpty())
            assertEquals(0L, inventory.totalDownloadedBytes)
        }

    // ----- status fold -------------------------------------------------------

    @Test
    fun `free pack with payload on disk reads as Downloaded`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.alpha", payloadSize = 100), downloadedVersion = 1)
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = false))

            val row = catalog.listInventory().rows.single()

            assertEquals(PackStatus.Downloaded, row.status)
            assertEquals(1, row.serverVersion)
            assertEquals(1, row.downloadedVersion)
        }

    @Test
    fun `free pack with no payload reads as NotDownloaded`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.alpha"), downloadedVersion = null)
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = false))

            val row = catalog.listInventory().rows.single()

            assertEquals(PackStatus.NotDownloaded, row.status)
            assertNull(row.downloadedVersion)
        }

    @Test
    fun `update available when downloaded version is older than server`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.alpha", version = 3), downloadedVersion = 1)
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = false))

            val row = catalog.listInventory().rows.single()

            assertEquals(PackStatus.UpdateAvailable, row.status)
            assertEquals(3, row.serverVersion)
            assertEquals(1, row.downloadedVersion)
        }

    @Test
    fun `pro pack reads as Locked when user is not entitled`() =
        runTest {
            val local = localStore()
            seed(local, pack("pro.cables", tier = SymbolPackTier.PRO))
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = false))

            val row = catalog.listInventory().rows.single()

            assertEquals(SymbolPackTier.PRO, row.tier)
            assertEquals(PackStatus.Locked, row.status)
        }

    @Test
    fun `pro pack reads as NotDownloaded when user is entitled and no payload on disk`() =
        runTest {
            val local = localStore()
            seed(local, pack("pro.cables", tier = SymbolPackTier.PRO))
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = true))

            val row = catalog.listInventory().rows.single()

            assertEquals(PackStatus.NotDownloaded, row.status)
        }

    @Test
    fun `pro pack reads as Downloaded when user is entitled and payload matches`() =
        runTest {
            val local = localStore()
            seed(local, pack("pro.cables", tier = SymbolPackTier.PRO, version = 2), downloadedVersion = 2)
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = true))

            val row = catalog.listInventory().rows.single()

            assertEquals(PackStatus.Downloaded, row.status)
        }

    @Test
    fun `pro pack reads as UpdateAvailable when entitled and downloaded version is older`() =
        runTest {
            val local = localStore()
            seed(local, pack("pro.cables", tier = SymbolPackTier.PRO, version = 5), downloadedVersion = 3)
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = true))

            val row = catalog.listInventory().rows.single()

            assertEquals(PackStatus.UpdateAvailable, row.status)
        }

    @Test
    fun `pro pack with payload on disk but lost entitlement reads as Locked not Downloaded`() =
        runTest {
            // Subscription expired after the pack landed on disk. The
            // gate must beat the downloaded-version compare — the user
            // should NOT see the pack as Downloaded just because the
            // payload row is still on disk.
            val local = localStore()
            seed(local, pack("pro.cables", tier = SymbolPackTier.PRO), downloadedVersion = 1)
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = false))

            val row = catalog.listInventory().rows.single()

            assertEquals(PackStatus.Locked, row.status)
        }

    // ----- ordering ---------------------------------------------------------

    @Test
    fun `rows ordered free packs before pro packs both alpha by id`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.zulu", tier = SymbolPackTier.FREE))
            seed(local, pack("pro.alpha", tier = SymbolPackTier.PRO))
            seed(local, pack("free.alpha", tier = SymbolPackTier.FREE))
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = false))

            assertEquals(
                listOf("free.alpha", "free.zulu", "pro.alpha"),
                catalog.listInventory().rows.map { it.packId },
            )
        }

    // ----- aggregate sums ---------------------------------------------------

    @Test
    fun `total downloaded bytes counts only packs with payloads on disk`() =
        runTest {
            val local = localStore()
            seed(local, pack("free.a", payloadSize = 500), downloadedVersion = 1)
            seed(local, pack("free.b", payloadSize = 800), downloadedVersion = 1)
            seed(local, pack("free.c", payloadSize = 9999), downloadedVersion = null)
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = false))

            assertEquals(1300L, catalog.listInventory().totalDownloadedBytes)
        }

    @Test
    fun `total downloaded bytes excludes Locked PRO packs that never resolved`() =
        runTest {
            // A PRO pack the user has never been entitled to has no
            // payload row on disk — it's surfaced as Locked but
            // contributes zero to the bytes sum. Defense-in-depth
            // assertion against a future regression where Locked rows
            // accidentally get counted.
            val local = localStore()
            seed(local, pack("free.a", payloadSize = 100), downloadedVersion = 1)
            seed(local, pack("pro.locked", tier = SymbolPackTier.PRO, payloadSize = 5000))
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = false))

            assertEquals(100L, catalog.listInventory().totalDownloadedBytes)
        }

    @Test
    fun `pro pack payload that persists on disk after entitlement lapse still contributes to totalDownloadedBytes`() =
        runTest {
            // User downloaded a PRO pack while entitled, then the
            // subscription lapsed. Phase 41.2b's archive-preservation
            // invariant keeps the payload row on disk so a re-subscribed
            // user resumes Pro access without re-downloading. The bytes
            // are physically present, so they contribute to the sum even
            // though the row's status is Locked. The footer copy ("X MB
            // on disk") reflects physical presence, NOT current
            // accessibility — pinned in PackInventory.totalDownloadedBytes
            // KDoc and exercised here.
            val local = localStore()
            seed(local, pack("pro.cables", tier = SymbolPackTier.PRO, payloadSize = 5000), downloadedVersion = 1)
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = false))

            val inventory = catalog.listInventory()
            assertEquals(PackStatus.Locked, inventory.rows.single().status)
            assertEquals(5000L, inventory.totalDownloadedBytes)
        }

    // ----- metadata round-trip ----------------------------------------------

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
            val catalog = DefaultSymbolPackCatalog(local, stubResolver(isPro = false))

            val row = catalog.listInventory().rows.single()
            assertEquals("Beginner", row.displayName)
            assertEquals("Starter knit symbols", row.description)
            assertEquals(12, row.symbolCount)
            assertEquals(4096, row.payloadSize)
        }

    // ----- gate consistency --------------------------------------------------

    @Test
    fun `gate snapshot is taken exactly once per listInventory call`() =
        runTest {
            // Pins the per-call snapshot contract: one listInventory()
            // call MUST result in exactly one isPro() evaluation, even
            // when the inventory contains many rows. If the implementation
            // regressed to calling isPro() per-row, we'd see N >= 2
            // cachedActiveSubscription invocations for an N-row inventory
            // (EntitlementResolver.isPro reads cachedActiveSubscription
            // once per its own call). Counting the underlying read makes
            // the contract testable without relying on observable
            // status-flip behavior.
            val local = localStore()
            seed(local, pack("pro.a", tier = SymbolPackTier.PRO))
            seed(local, pack("pro.b", tier = SymbolPackTier.PRO))
            seed(local, pack("free.c"), downloadedVersion = 1)
            val countingRepo = CountingStubSubscriptionRepository(cachedFor = "u-1", sub = null)
            val auth = FakeAuthRepository().apply { setAuthState(AuthState.Authenticated(userId = "u-1", email = "e@x")) }
            val resolver = EntitlementResolver(countingRepo, auth, frozenClock)
            val catalog = DefaultSymbolPackCatalog(local, resolver)

            val rows = catalog.listInventory().rows
            assertEquals(3, rows.size)
            // Exactly one cachedActiveSubscription call — proves that
            // isPro() was called exactly once for the whole inventory.
            assertEquals(1, countingRepo.cachedCallCount)

            // A second listInventory() call should produce exactly one
            // additional cachedActiveSubscription call.
            catalog.listInventory()
            assertEquals(2, countingRepo.cachedCallCount)
        }

    @Test
    fun `gate flip between listInventory calls is observed on the second call`() =
        runTest {
            // Companion to the snapshot-per-call test above — proves
            // that the snapshot is re-evaluated on each call (otherwise
            // a Pro-purchase mid-session would never unlock packs until
            // the user navigated away and back). First call: not
            // entitled → Locked. Flip the underlying sub to active.
            // Second call: entitled → NotDownloaded (no payload on disk).
            val local = localStore()
            seed(local, pack("pro.cables", tier = SymbolPackTier.PRO))
            val flippingRepo = FlippingStubSubscriptionRepository(cachedFor = "u-1", subBefore = null, subAfter = activeProSub())
            val auth = FakeAuthRepository().apply { setAuthState(AuthState.Authenticated(userId = "u-1", email = "e@x")) }
            val resolver = EntitlementResolver(flippingRepo, auth, frozenClock)
            val catalog = DefaultSymbolPackCatalog(local, resolver)

            assertEquals(
                PackStatus.Locked,
                catalog
                    .listInventory()
                    .rows
                    .single()
                    .status,
            )
            flippingRepo.flip()
            assertEquals(
                PackStatus.NotDownloaded,
                catalog
                    .listInventory()
                    .rows
                    .single()
                    .status,
            )
        }

    private fun activeProSub() =
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

    private class StubSubscriptionRepository(
        private val cachedFor: String?,
        private val sub: Subscription?,
    ) : SubscriptionRepository {
        override fun cachedActiveSubscription(userId: String): Subscription? = if (userId == cachedFor) sub else null

        override fun observeActiveSubscription(userId: String): Flow<Subscription?> = flowOf(if (userId == cachedFor) sub else null)

        override suspend fun refresh(userId: String): Result<Subscription?> = Result.success(sub)

        override suspend fun clearLocalCache(userId: String) = Unit
    }

    /**
     * Counts every cachedActiveSubscription call so the snapshot-per-call
     * contract can be asserted directly. Used by the
     * "gate snapshot is taken exactly once per listInventory call" test.
     */
    private class CountingStubSubscriptionRepository(
        private val cachedFor: String?,
        private val sub: Subscription?,
    ) : SubscriptionRepository {
        var cachedCallCount: Int = 0

        override fun cachedActiveSubscription(userId: String): Subscription? {
            cachedCallCount++
            return if (userId == cachedFor) sub else null
        }

        override fun observeActiveSubscription(userId: String): Flow<Subscription?> = flowOf(if (userId == cachedFor) sub else null)

        override suspend fun refresh(userId: String): Result<Subscription?> = Result.success(sub)

        override suspend fun clearLocalCache(userId: String) = Unit
    }

    /**
     * Returns [subBefore] until [flip] is called, then [subAfter]. Used by
     * the "gate flip between listInventory calls" test to model a Pro
     * purchase landing between two consecutive screen reads.
     */
    private class FlippingStubSubscriptionRepository(
        private val cachedFor: String?,
        private val subBefore: Subscription?,
        private val subAfter: Subscription?,
    ) : SubscriptionRepository {
        private var flipped = false

        fun flip() {
            flipped = true
        }

        override fun cachedActiveSubscription(userId: String): Subscription? {
            val current = if (flipped) subAfter else subBefore
            return if (userId == cachedFor) current else null
        }

        override fun observeActiveSubscription(userId: String): Flow<Subscription?> = flowOf(cachedActiveSubscription(userId))

        override suspend fun refresh(userId: String): Result<Subscription?> = Result.success(cachedActiveSubscription(userId))

        override suspend fun clearLocalCache(userId: String) = Unit
    }
}
