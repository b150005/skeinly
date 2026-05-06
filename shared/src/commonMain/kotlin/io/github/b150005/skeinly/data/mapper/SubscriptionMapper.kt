package io.github.b150005.skeinly.data.mapper

import io.github.b150005.skeinly.db.SubscriptionEntity
import io.github.b150005.skeinly.domain.model.Subscription
import io.github.b150005.skeinly.domain.model.SubscriptionPlatform
import io.github.b150005.skeinly.domain.model.SubscriptionStatus
import kotlin.time.Instant

internal fun SubscriptionEntity.toDomain(): Subscription =
    Subscription(
        id = id,
        userId = user_id,
        platform = platform.toSubscriptionPlatform(),
        productId = product_id,
        status = status.toSubscriptionStatus(),
        originalTransactionId = original_transaction_id,
        expiresAt = expires_at?.let { Instant.parse(it) },
        isInTrial = is_in_trial != 0L,
        autoRenewStatus = auto_renew_status != 0L,
        lastVerifiedAt = Instant.parse(last_verified_at),
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )

internal fun String.toSubscriptionStatus(): SubscriptionStatus =
    when (this) {
        "active" -> SubscriptionStatus.ACTIVE
        "expired" -> SubscriptionStatus.EXPIRED
        "canceled" -> SubscriptionStatus.CANCELED
        "in_grace_period" -> SubscriptionStatus.IN_GRACE_PERIOD
        "in_billing_retry" -> SubscriptionStatus.IN_BILLING_RETRY
        "refunded" -> SubscriptionStatus.REFUNDED
        // Hard fail rather than silent fall-through to ACTIVE — if migration 017
        // adds a new state we want it surfaced loudly so the client can be
        // taught the new transition before silently mis-treating it as Pro.
        else -> error("Unknown SubscriptionStatus wire value: '$this'")
    }

internal fun SubscriptionStatus.toDbString(): String =
    when (this) {
        SubscriptionStatus.ACTIVE -> "active"
        SubscriptionStatus.EXPIRED -> "expired"
        SubscriptionStatus.CANCELED -> "canceled"
        SubscriptionStatus.IN_GRACE_PERIOD -> "in_grace_period"
        SubscriptionStatus.IN_BILLING_RETRY -> "in_billing_retry"
        SubscriptionStatus.REFUNDED -> "refunded"
    }

internal fun String.toSubscriptionPlatform(): SubscriptionPlatform =
    when (this) {
        "ios" -> SubscriptionPlatform.IOS
        "android" -> SubscriptionPlatform.ANDROID
        "alpha-grant" -> SubscriptionPlatform.ALPHA_GRANT
        else -> error("Unknown SubscriptionPlatform wire value: '$this'")
    }

internal fun SubscriptionPlatform.toDbString(): String =
    when (this) {
        SubscriptionPlatform.IOS -> "ios"
        SubscriptionPlatform.ANDROID -> "android"
        SubscriptionPlatform.ALPHA_GRANT -> "alpha-grant"
    }
