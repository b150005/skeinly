package io.github.b150005.skeinly.domain.symbol

import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.Subscription
import io.github.b150005.skeinly.domain.model.SubscriptionPlatform
import io.github.b150005.skeinly.domain.model.SubscriptionStatus
import io.github.b150005.skeinly.domain.repository.SubscriptionRepository
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class EntitlementResolverTest {
    private val now = Instant.parse("2026-05-06T12:00:00Z")

    private val frozenClock =
        object : Clock {
            override fun now(): Instant = now
        }

    private fun resolver(
        userId: String? = "user-1",
        sub: Subscription? = null,
    ): EntitlementResolver {
        val auth =
            FakeAuthRepository().apply {
                if (userId != null) setAuthState(AuthState.Authenticated(userId = userId, email = "e@x"))
            }
        val repo = StubSubscriptionRepository(cachedFor = userId, sub = sub)
        return EntitlementResolver(repo, auth, frozenClock)
    }

    private fun activeSub(
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        expiresAt: Instant? = Instant.parse("2026-12-31T00:00:00Z"),
    ) = Subscription(
        id = "sub-1",
        userId = "user-1",
        platform = SubscriptionPlatform.IOS,
        productId = "skeinly.pro.monthly",
        status = status,
        originalTransactionId = "txn-1",
        expiresAt = expiresAt,
        lastVerifiedAt = now,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `unauthenticated user returns false`() {
        val r = resolver(userId = null)
        assertFalse(r.isPro())
    }

    @Test
    fun `authenticated user with no cached sub returns false`() {
        val r = resolver(sub = null)
        assertFalse(r.isPro())
    }

    @Test
    fun `active sub with future expiresAt returns true`() {
        val r = resolver(sub = activeSub())
        assertTrue(r.isPro())
    }

    @Test
    fun `in_grace_period sub returns true`() {
        val r = resolver(sub = activeSub(status = SubscriptionStatus.IN_GRACE_PERIOD))
        assertTrue(r.isPro())
    }

    @Test
    fun `expired sub returns false`() {
        val r = resolver(sub = activeSub(status = SubscriptionStatus.EXPIRED))
        assertFalse(r.isPro())
    }

    @Test
    fun `canceled sub with future expiresAt returns false - design contract anchor`() {
        // Design contract (Subscription.kt isActiveAt KDoc): the verify-receipt
        // Edge Function only writes status='canceled' AFTER the paid period
        // ends. Apple StoreKit 2 keeps emitting 'subscribed' until expiry;
        // Google Play keeps emitting 'ACTIVE' with autoRenewing=false. So a
        // CANCELED row with future expiresAt should never legitimately appear
        // in production — but if it ever does (Edge Function regression /
        // direct admin SQL pass), this test pins the gate behavior: NO Pro.
        // If this test ever needs to flip to assertTrue, the verify-receipt
        // contract has changed and isActiveAt MUST move CANCELED into the
        // active allow-list to avoid silently revoking Pro mid-period.
        val futureExpiry = Instant.parse("2026-12-31T00:00:00Z")
        val r = resolver(sub = activeSub(status = SubscriptionStatus.CANCELED, expiresAt = futureExpiry))
        assertFalse(r.isPro())
    }

    @Test
    fun `canceled sub with past expiresAt returns false`() {
        val past = Instant.parse("2026-01-01T00:00:00Z")
        val r = resolver(sub = activeSub(status = SubscriptionStatus.CANCELED, expiresAt = past))
        assertFalse(r.isPro())
    }

    @Test
    fun `refunded sub returns false`() {
        val r = resolver(sub = activeSub(status = SubscriptionStatus.REFUNDED))
        assertFalse(r.isPro())
    }

    @Test
    fun `in_billing_retry sub returns false`() {
        val r = resolver(sub = activeSub(status = SubscriptionStatus.IN_BILLING_RETRY))
        assertFalse(r.isPro())
    }

    @Test
    fun `expiresAt in past returns false even when status is active`() {
        val past = Instant.parse("2026-01-01T00:00:00Z")
        val r = resolver(sub = activeSub(expiresAt = past))
        assertFalse(r.isPro())
    }

    @Test
    fun `null expiresAt with active status returns true for alpha-grant`() {
        val perpetual = activeSub(expiresAt = null)
        val r = resolver(sub = perpetual)
        assertTrue(r.isPro())
    }

    @Test
    fun `repository is queried with the current authenticated userId`() {
        val auth =
            FakeAuthRepository().apply {
                setAuthState(AuthState.Authenticated(userId = "specific-user", email = "e@x"))
            }
        val repo = StubSubscriptionRepository(cachedFor = "specific-user", sub = activeSub())
        val r = EntitlementResolver(repo, auth, frozenClock)

        assertTrue(r.isPro())
        // Verify the repo was queried for the authenticated user, not some
        // other id.
        assertTrue(repo.lastQueriedUserId == "specific-user")
    }

    @Test
    fun `repository sub for different user does not leak across`() {
        val repo = StubSubscriptionRepository(cachedFor = "other-user", sub = activeSub())
        val auth =
            FakeAuthRepository().apply {
                setAuthState(AuthState.Authenticated(userId = "user-1", email = "e@x"))
            }
        val r = EntitlementResolver(repo, auth, frozenClock)

        assertFalse(r.isPro())
    }

    private class StubSubscriptionRepository(
        private val cachedFor: String?,
        private val sub: Subscription?,
    ) : SubscriptionRepository {
        var lastQueriedUserId: String? = null
            private set

        override fun cachedActiveSubscription(userId: String): Subscription? {
            lastQueriedUserId = userId
            return if (userId == cachedFor) sub else null
        }

        override fun observeActiveSubscription(userId: String): Flow<Subscription?> = flowOf(null)

        override suspend fun refresh(userId: String): Result<Subscription?> = Result.success(null)

        override suspend fun clearLocalCache(userId: String) {}
    }
}
