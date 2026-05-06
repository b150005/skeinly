package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.local.LocalSubscriptionDataSource
import io.github.b150005.skeinly.data.remote.SubscriptionRemoteOperations
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.db.createTestDriver
import io.github.b150005.skeinly.domain.model.Subscription
import io.github.b150005.skeinly.domain.model.SubscriptionPlatform
import io.github.b150005.skeinly.domain.model.SubscriptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SubscriptionRepositoryImplTest {
    private lateinit var local: LocalSubscriptionDataSource
    private lateinit var remote: FakeSubscriptionRemoteOperations
    private val isOnline = MutableStateFlow(true)

    @BeforeTest
    fun setUp() {
        val db = SkeinlyDatabase(createTestDriver())
        local = LocalSubscriptionDataSource(db, Dispatchers.Unconfined)
        remote = FakeSubscriptionRemoteOperations()
    }

    private fun repo(
        remote: SubscriptionRemoteOperations? = this.remote,
        online: Boolean = true,
        clock: Clock = Clock.System,
    ): SubscriptionRepositoryImpl {
        isOnline.value = online
        return SubscriptionRepositoryImpl(local = local, remote = remote, isOnline = isOnline, clock = clock)
    }

    private fun fixedClock(now: Instant): Clock =
        object : Clock {
            override fun now(): Instant = now
        }

    private fun activeSub(
        id: String = "sub-1",
        userId: String = "user-1",
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        updatedAt: Instant = Instant.parse("2026-04-25T10:00:00Z"),
        lastVerifiedAt: Instant = updatedAt,
    ) = Subscription(
        id = id,
        userId = userId,
        platform = SubscriptionPlatform.IOS,
        productId = "skeinly.pro.monthly",
        status = status,
        originalTransactionId = "txn-$id",
        expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
        lastVerifiedAt = lastVerifiedAt,
        createdAt = Instant.parse("2026-04-25T10:00:00Z"),
        updatedAt = updatedAt,
    )

    @Test
    fun `cachedActiveSubscription delegates to local sync read`() =
        runTest {
            local.upsert(activeSub())
            val r = repo()

            val cached = r.cachedActiveSubscription("user-1")
            assertNotNull(cached)
            assertEquals("sub-1", cached.id)
        }

    @Test
    fun `cachedActiveSubscription returns null when no cached row`() {
        val r = repo()
        assertNull(r.cachedActiveSubscription("user-1"))
    }

    @Test
    fun `refresh fetches from remote and writes through to local cache`() =
        runTest {
            val r = repo()
            remote.queue("user-1", listOf(activeSub()))

            val result = r.refresh("user-1")

            assertTrue(result.isSuccess)
            val active = result.getOrNull()
            assertNotNull(active)
            assertEquals("sub-1", active.id)
            // Local cache populated for the synchronous EntitlementResolver
            // path:
            assertNotNull(r.cachedActiveSubscription("user-1"))
        }

    @Test
    fun `refresh with empty rowset evicts STALE cached row`() =
        runTest {
            // Pre-condition: cached row last verified > 24h ago — qualifies
            // as stale per STALE_THRESHOLD.
            val now = Instant.parse("2026-05-06T12:00:00Z")
            val staleVerifiedAt = now - kotlin.time.Duration.parse("25h")
            local.upsert(activeSub(lastVerifiedAt = staleVerifiedAt))
            val r = repo(clock = fixedClock(now))
            // Real server-side row removal: e.g. account deletion via
            // auth.users CASCADE.
            remote.queue("user-1", emptyList())

            val result = r.refresh("user-1")

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull())
            // Stale local row evicted so EntitlementResolver flips to
            // false on the next read.
            assertNull(r.cachedActiveSubscription("user-1"))
        }

    @Test
    fun `refresh with empty rowset PRESERVES recently-verified cached row`() =
        runTest {
            // Defensive: a transient JWT / RLS scoping artifact can cause
            // remote to legitimately return [] for an authenticated user
            // whose row exists. Don't nuke a recently-verified Pro row on
            // a single empty response — wait until the staleness window
            // elapses without remote confirmation.
            val now = Instant.parse("2026-05-06T12:00:00Z")
            val freshVerifiedAt = now - kotlin.time.Duration.parse("1h")
            local.upsert(activeSub(lastVerifiedAt = freshVerifiedAt))
            val r = repo(clock = fixedClock(now))
            remote.queue("user-1", emptyList())

            val result = r.refresh("user-1")

            assertTrue(result.isSuccess)
            // Cache preserved — refresh returns the still-active row.
            assertNotNull(result.getOrNull())
            assertNotNull(r.cachedActiveSubscription("user-1"))
        }

    @Test
    fun `refresh with empty rowset is idempotent when cache already empty`() =
        runTest {
            // No cached row + remote empty = stable empty result, no error.
            val r = repo()
            remote.queue("user-1", emptyList())

            val result = r.refresh("user-1")

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull())
            assertNull(r.cachedActiveSubscription("user-1"))
        }

    @Test
    fun `refresh failure surfaces as Result failure and leaves cache untouched`() =
        runTest {
            local.upsert(activeSub())
            val r = repo()
            remote.nextError = RuntimeException("boom")

            val result = r.refresh("user-1")

            assertTrue(result.isFailure)
            // Local cache stays as-is — refresh failure is recoverable on
            // the next attempt.
            assertNotNull(r.cachedActiveSubscription("user-1"))
        }

    @Test
    fun `refresh with null remote returns cached value as success`() =
        runTest {
            local.upsert(activeSub())
            val r = repo(remote = null)

            val result = r.refresh("user-1")

            assertTrue(result.isSuccess)
            val active = result.getOrNull()
            assertNotNull(active)
            assertEquals("sub-1", active.id)
        }

    @Test
    fun `refresh while offline returns cached value as success`() =
        runTest {
            local.upsert(activeSub())
            val r = repo(online = false)

            val result = r.refresh("user-1")

            assertTrue(result.isSuccess)
            assertNotNull(result.getOrNull())
            // Critical: remote was NOT called while offline.
            assertEquals(0, remote.callCount)
        }

    @Test
    fun `refresh persists multiple rows for alpha-tester-with-paid scenario`() =
        runTest {
            val alpha =
                activeSub(id = "alpha", updatedAt = Instant.parse("2026-04-01T00:00:00Z"))
                    .copy(platform = SubscriptionPlatform.ALPHA_GRANT, expiresAt = null)
            val paid = activeSub(id = "paid", updatedAt = Instant.parse("2026-04-25T10:00:00Z"))
            val r = repo()
            remote.queue("user-1", listOf(paid, alpha))

            r.refresh("user-1")

            // Both rows persisted; getActiveForUser picks newest by updated_at.
            assertEquals(2, local.getAllForUser("user-1").size)
            val active = r.cachedActiveSubscription("user-1")
            assertEquals("paid", active?.id)
        }

    @Test
    fun `clearLocalCache removes only the targeted users rows`() =
        runTest {
            local.upsert(activeSub(id = "a", userId = "user-1"))
            local.upsert(activeSub(id = "b", userId = "user-2"))
            val r = repo()

            r.clearLocalCache("user-1")

            assertNull(r.cachedActiveSubscription("user-1"))
            assertNotNull(r.cachedActiveSubscription("user-2"))
        }

    @Test
    fun `refresh with refunded row in cache and same remote payload keeps cache filtered`() =
        runTest {
            val refunded = activeSub(status = SubscriptionStatus.REFUNDED)
            val r = repo()
            remote.queue("user-1", listOf(refunded))

            val result = r.refresh("user-1")

            assertTrue(result.isSuccess)
            // Refunded row is in the cache (getAllForUser), but
            // cachedActiveSubscription filters it out via the SQL active
            // status query.
            assertEquals(1, local.getAllForUser("user-1").size)
            assertNull(r.cachedActiveSubscription("user-1"))
            assertNull(result.getOrNull())
        }

    private class FakeSubscriptionRemoteOperations : SubscriptionRemoteOperations {
        private val payloads = mutableMapOf<String, List<Subscription>>()
        var nextError: Throwable? = null
        var callCount = 0
            private set

        fun queue(
            userId: String,
            rows: List<Subscription>,
        ) {
            payloads[userId] = rows
        }

        override suspend fun getAllForUser(userId: String): List<Subscription> {
            callCount += 1
            nextError?.let {
                nextError = null
                throw it
            }
            return payloads[userId] ?: emptyList()
        }
    }
}
