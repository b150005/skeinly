package io.github.b150005.skeinly.data.mapper

import io.github.b150005.skeinly.db.SubscriptionEntity
import io.github.b150005.skeinly.domain.model.SubscriptionPlatform
import io.github.b150005.skeinly.domain.model.SubscriptionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SubscriptionMapperTest {
    private fun entity(
        status: String = "active",
        platform: String = "ios",
        expiresAt: String? = "2026-12-31T00:00:00Z",
        isInTrial: Long = 0,
        autoRenewStatus: Long = 1,
        originalTransactionId: String? = "txn-123",
    ) = SubscriptionEntity(
        id = "sub-1",
        user_id = "user-1",
        platform = platform,
        product_id = "skeinly.pro.monthly",
        status = status,
        original_transaction_id = originalTransactionId,
        expires_at = expiresAt,
        is_in_trial = isInTrial,
        auto_renew_status = autoRenewStatus,
        last_verified_at = "2026-04-25T10:00:00Z",
        created_at = "2026-04-25T10:00:00Z",
        updated_at = "2026-04-25T10:00:00Z",
    )

    @Test
    fun `entity round-trips with active status and ios platform`() {
        val sub = entity().toDomain()

        assertEquals("sub-1", sub.id)
        assertEquals("user-1", sub.userId)
        assertEquals(SubscriptionPlatform.IOS, sub.platform)
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertEquals("txn-123", sub.originalTransactionId)
        assertEquals(Instant.parse("2026-12-31T00:00:00Z"), sub.expiresAt)
        assertEquals(false, sub.isInTrial)
        assertEquals(true, sub.autoRenewStatus)
    }

    @Test
    fun `null expiresAt round-trips for alpha-grant perpetual sub`() {
        val sub = entity(platform = "alpha-grant", expiresAt = null).toDomain()

        assertEquals(SubscriptionPlatform.ALPHA_GRANT, sub.platform)
        assertNull(sub.expiresAt)
    }

    @Test
    fun `null originalTransactionId round-trips`() {
        val sub = entity(originalTransactionId = null).toDomain()

        assertNull(sub.originalTransactionId)
    }

    @Test
    fun `is_in_trial INTEGER 1 maps to true`() {
        val sub = entity(isInTrial = 1).toDomain()

        assertTrue(sub.isInTrial)
    }

    @Test
    fun `auto_renew_status INTEGER 0 maps to false`() {
        val sub = entity(autoRenewStatus = 0).toDomain()

        assertEquals(false, sub.autoRenewStatus)
    }

    @Test
    fun `unknown status string raises`() {
        // Defensive: prevents silent drift if migration 017 adds a new status
        // value the Kotlin enum doesn't yet know about — hard fail beats
        // silently treating unknown rows as Pro.
        assertFailsWith<IllegalStateException> {
            entity(status = "trial").toDomain()
        }
    }

    @Test
    fun `unknown platform string raises`() {
        assertFailsWith<IllegalStateException> {
            entity(platform = "web").toDomain()
        }
    }

    @Test
    fun `every status value round-trips through enum-to-db and back`() {
        val pairs =
            listOf(
                "active" to SubscriptionStatus.ACTIVE,
                "expired" to SubscriptionStatus.EXPIRED,
                "canceled" to SubscriptionStatus.CANCELED,
                "in_grace_period" to SubscriptionStatus.IN_GRACE_PERIOD,
                "in_billing_retry" to SubscriptionStatus.IN_BILLING_RETRY,
                "refunded" to SubscriptionStatus.REFUNDED,
            )
        pairs.forEach { (db, enum) ->
            assertEquals(enum, db.toSubscriptionStatus())
            assertEquals(db, enum.toDbString())
        }
    }

    @Test
    fun `every platform value round-trips through enum-to-db and back`() {
        val pairs =
            listOf(
                "ios" to SubscriptionPlatform.IOS,
                "android" to SubscriptionPlatform.ANDROID,
                "alpha-grant" to SubscriptionPlatform.ALPHA_GRANT,
            )
        pairs.forEach { (db, enum) ->
            assertEquals(enum, db.toSubscriptionPlatform())
            assertEquals(db, enum.toDbString())
        }
    }
}
