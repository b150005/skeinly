package io.github.b150005.skeinly.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SubscriptionTest {
    private val now = Instant.parse("2026-05-06T12:00:00Z")

    private fun sub(
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        expiresAt: Instant? = Instant.parse("2026-12-31T00:00:00Z"),
        platform: SubscriptionPlatform = SubscriptionPlatform.IOS,
    ) = Subscription(
        id = "sub-1",
        userId = "user-1",
        platform = platform,
        productId = "skeinly.pro.monthly",
        status = status,
        originalTransactionId = "txn-1",
        expiresAt = expiresAt,
        lastVerifiedAt = now,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `active sub with future expiresAt is active`() {
        assertTrue(sub().isActiveAt(now))
    }

    @Test
    fun `active sub with past expiresAt is not active`() {
        val past = Instant.parse("2026-01-01T00:00:00Z")
        assertFalse(sub(expiresAt = past).isActiveAt(now))
    }

    @Test
    fun `in_grace_period sub with future expiresAt is active`() {
        // Grace period: payment retry in flight. Apple/Google extend access,
        // client mirrors the policy.
        assertTrue(sub(status = SubscriptionStatus.IN_GRACE_PERIOD).isActiveAt(now))
    }

    @Test
    fun `canceled sub is not active even with future expiresAt`() {
        // CANCELED means the user requested cancellation. The platform also
        // emits ACTIVE rows during the until-expiry window; we trust the
        // status field and treat CANCELED as ineligible for Pro.
        assertFalse(sub(status = SubscriptionStatus.CANCELED).isActiveAt(now))
    }

    @Test
    fun `expired sub is not active`() {
        assertFalse(sub(status = SubscriptionStatus.EXPIRED).isActiveAt(now))
    }

    @Test
    fun `in_billing_retry sub is not active`() {
        assertFalse(sub(status = SubscriptionStatus.IN_BILLING_RETRY).isActiveAt(now))
    }

    @Test
    fun `refunded sub is not active`() {
        assertFalse(sub(status = SubscriptionStatus.REFUNDED).isActiveAt(now))
    }

    @Test
    fun `null expiresAt is perpetual when status is active`() {
        // Alpha-grant rows ship with expires_at = null per migration 017
        // grant_alpha_pro RPC.
        val perpetual = sub(platform = SubscriptionPlatform.ALPHA_GRANT, expiresAt = null)
        assertTrue(perpetual.isActiveAt(now))
        // Still perpetual far in the future.
        assertTrue(perpetual.isActiveAt(Instant.parse("2099-01-01T00:00:00Z")))
    }

    @Test
    fun `null expiresAt does not override non-active status`() {
        val refunded = sub(status = SubscriptionStatus.REFUNDED, expiresAt = null)
        assertFalse(refunded.isActiveAt(now))
    }

    @Test
    fun `expiresAt exactly equal to now is not active`() {
        // Edge: expiresAt > now is the gate, not >=. A row whose expiresAt
        // is the exact current instant has expired.
        val sub = sub(expiresAt = now)
        assertFalse(sub.isActiveAt(now))
    }

    @Test
    fun `default ctor values match migration 017 defaults`() {
        // is_in_trial defaults false, auto_renew_status defaults true.
        val s = sub()
        assertEquals(false, s.isInTrial)
        assertEquals(true, s.autoRenewStatus)
    }
}
