package io.github.b150005.skeinly.data.local

import app.cash.turbine.test
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.db.createTestDriver
import io.github.b150005.skeinly.domain.model.Subscription
import io.github.b150005.skeinly.domain.model.SubscriptionPlatform
import io.github.b150005.skeinly.domain.model.SubscriptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class LocalSubscriptionDataSourceTest {
    private lateinit var dataSource: LocalSubscriptionDataSource

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        val db = SkeinlyDatabase(driver)
        dataSource = LocalSubscriptionDataSource(db, Dispatchers.Unconfined)
    }

    private fun sub(
        id: String = "sub-1",
        userId: String = "user-1",
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        platform: SubscriptionPlatform = SubscriptionPlatform.IOS,
        expiresAt: Instant? = Instant.parse("2026-12-31T00:00:00Z"),
        updatedAt: Instant = Instant.parse("2026-04-25T10:00:00Z"),
    ) = Subscription(
        id = id,
        userId = userId,
        platform = platform,
        productId = "skeinly.pro.monthly",
        status = status,
        originalTransactionId = "txn-$id",
        expiresAt = expiresAt,
        lastVerifiedAt = updatedAt,
        createdAt = Instant.parse("2026-04-25T10:00:00Z"),
        updatedAt = updatedAt,
    )

    @Test
    fun `upsert persists active row and getActiveForUser returns it`() =
        runTest {
            val s = sub()
            dataSource.upsert(s)

            val active = dataSource.getActiveForUser("user-1")
            assertNotNull(active)
            assertEquals("sub-1", active.id)
            assertEquals(SubscriptionStatus.ACTIVE, active.status)
        }

    @Test
    fun `getActiveForUser returns null when no row exists`() =
        runTest {
            assertNull(dataSource.getActiveForUser("user-1"))
        }

    @Test
    fun `getActiveForUser excludes refunded rows`() =
        runTest {
            dataSource.upsert(sub(status = SubscriptionStatus.REFUNDED))

            assertNull(dataSource.getActiveForUser("user-1"))
        }

    @Test
    fun `getActiveForUser excludes expired and canceled rows`() =
        runTest {
            dataSource.upsert(sub(id = "exp", status = SubscriptionStatus.EXPIRED))
            dataSource.upsert(sub(id = "can", status = SubscriptionStatus.CANCELED))

            assertNull(dataSource.getActiveForUser("user-1"))
        }

    @Test
    fun `getActiveForUser returns most-recent active when multiple exist`() =
        runTest {
            // Alpha tester later subscribes paid: two rows, want the newest
            // updated_at to win.
            val alphaGrant =
                sub(
                    id = "alpha",
                    platform = SubscriptionPlatform.ALPHA_GRANT,
                    expiresAt = null,
                    updatedAt = Instant.parse("2026-04-01T00:00:00Z"),
                )
            val paid =
                sub(
                    id = "paid",
                    updatedAt = Instant.parse("2026-04-25T10:00:00Z"),
                )
            dataSource.upsert(alphaGrant)
            dataSource.upsert(paid)

            val active = dataSource.getActiveForUser("user-1")
            assertNotNull(active)
            assertEquals("paid", active.id)
        }

    @Test
    fun `in_grace_period rows are returned by getActiveForUser`() =
        runTest {
            dataSource.upsert(sub(status = SubscriptionStatus.IN_GRACE_PERIOD))

            val active = dataSource.getActiveForUser("user-1")
            assertNotNull(active)
            assertEquals(SubscriptionStatus.IN_GRACE_PERIOD, active.status)
        }

    @Test
    fun `getActiveForUserSync mirrors suspend variant`() =
        runTest {
            dataSource.upsert(sub())

            val active = dataSource.getActiveForUserSync("user-1")
            assertNotNull(active)
            assertEquals("sub-1", active.id)
        }

    @Test
    fun `getActiveForUserSync returns null with no row`() {
        // Non-suspend by design — must work without runTest.
        assertNull(dataSource.getActiveForUserSync("user-1"))
    }

    @Test
    fun `upsert overwrites existing row by id`() =
        runTest {
            dataSource.upsert(sub())
            // Refresh path: same id, status flipped.
            dataSource.upsert(sub(status = SubscriptionStatus.REFUNDED))

            // Active query now returns null (refunded is filtered).
            assertNull(dataSource.getActiveForUser("user-1"))
            // But the row still exists via getAllForUser.
            assertEquals(1, dataSource.getAllForUser("user-1").size)
        }

    @Test
    fun `getAllForUser returns all rows ordered newest first`() =
        runTest {
            dataSource.upsert(sub(id = "older", updatedAt = Instant.parse("2026-04-01T00:00:00Z")))
            dataSource.upsert(sub(id = "newer", updatedAt = Instant.parse("2026-04-25T10:00:00Z")))

            val all = dataSource.getAllForUser("user-1")
            assertEquals(2, all.size)
            assertEquals("newer", all[0].id)
            assertEquals("older", all[1].id)
        }

    @Test
    fun `clearForUser removes only the targeted users rows`() =
        runTest {
            dataSource.upsert(sub(id = "a", userId = "user-1"))
            dataSource.upsert(sub(id = "b", userId = "user-2"))

            dataSource.clearForUser("user-1")

            assertNull(dataSource.getActiveForUser("user-1"))
            assertNotNull(dataSource.getActiveForUser("user-2"))
        }

    @Test
    fun `observeActiveForUser emits new value when row updates`() =
        runTest {
            dataSource.observeActiveForUser("user-1").test {
                assertNull(awaitItem())

                dataSource.upsert(sub())
                val first = awaitItem()
                assertNotNull(first)
                assertEquals(SubscriptionStatus.ACTIVE, first.status)

                // Refund flips status; observe Flow should emit null because
                // the row no longer matches the active filter.
                dataSource.upsert(sub(status = SubscriptionStatus.REFUNDED))
                assertNull(awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }
}
