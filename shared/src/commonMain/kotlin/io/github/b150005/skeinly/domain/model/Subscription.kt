package io.github.b150005.skeinly.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Lifecycle of a [Subscription] mirroring the Postgres CHECK constraint in
 * migration 017 (ADR-016 §3.1).
 *
 * Closed enum — six values reflecting the StoreKit 2 / Play Billing state
 * machine plus a [REFUNDED] terminal state pushed by the Apple / Google
 * webhook fan-in. The wire format uses the lowercase string per
 * `subscriptions.status IN ('active','expired','canceled','in_grace_period','in_billing_retry','refunded')`.
 *
 * - [ACTIVE]: subscription is paid + within its current period. The user
 *   holds Pro entitlement.
 * - [IN_GRACE_PERIOD]: payment retry is in flight; the platform extends Pro
 *   access for ~16 days (Apple) / ~30 days (Google) before downgrading. The
 *   client treats this as Pro per ADR-016 §4.2 to match Apple/Google policy.
 * - [IN_BILLING_RETRY]: payment failed and grace period elapsed; the
 *   platform retries silently. The client treats this as NOT Pro — entitlement
 *   has lapsed.
 * - [EXPIRED]: subscription period ended without renewal. Not Pro.
 * - [CANCELED]: terminal mid-state — user has cancelled, the paid period
 *   has fully elapsed, and auto-renew is off. The client does NOT grant
 *   Pro on CANCELED regardless of [Subscription.expiresAt]. **Edge Function
 *   contract:** `verify-receipt` writes `canceled` only after the paid
 *   period ends; during the until-expiry window after a user cancellation
 *   the row remains [ACTIVE] (with `auto_renew_status = false`). Apple
 *   StoreKit 2 keeps emitting `subscribed` until expiry; Google Play
 *   keeps emitting `ACTIVE` with `autoRenewing = false`. Neither platform
 *   emits a "canceled-but-still-paid-for" status. The implementation
 *   trusts the verifier's status field rather than re-deriving Pro from
 *   [Subscription.expiresAt] alone — see [Subscription.isActiveAt].
 * - [REFUNDED]: terminal. Apple / Google issued a refund. The
 *   `verify-receipt` webhook flips the row immediately; client revokes Pro
 *   on the next refresh.
 */
@Serializable
enum class SubscriptionStatus {
    @SerialName("active")
    ACTIVE,

    @SerialName("expired")
    EXPIRED,

    @SerialName("canceled")
    CANCELED,

    @SerialName("in_grace_period")
    IN_GRACE_PERIOD,

    @SerialName("in_billing_retry")
    IN_BILLING_RETRY,

    @SerialName("refunded")
    REFUNDED,
}

/**
 * Origin platform for a [Subscription] mirroring the Postgres CHECK constraint
 * in migration 017 (ADR-016 §3.1).
 *
 * - [IOS]: StoreKit 2 receipt validated by `verify-receipt`.
 * - [ANDROID]: Play Billing receipt validated by `verify-receipt`.
 * - [ALPHA_GRANT]: sentinel platform for the alpha-tester auto-Pro grant
 *   minted via the `grant_alpha_pro(uid)` RPC. [Subscription.expiresAt] is
 *   `null` (perpetual until alpha closes).
 */
@Serializable
enum class SubscriptionPlatform {
    @SerialName("ios")
    IOS,

    @SerialName("android")
    ANDROID,

    @SerialName("alpha-grant")
    ALPHA_GRANT,
}

/**
 * One subscription row mirroring `public.subscriptions` (migration 017,
 * ADR-016 §3.1 + §4.2).
 *
 * **Read-only at the client.** The only writer is the `verify-receipt` Edge
 * Function running with the service-role key — Apple App Store Server
 * Notifications V2 and Google Play Real-Time Developer Notifications fan
 * into the same function. The client mirrors active rows for offline
 * EntitlementResolver lookups; [Subscription] never originates from a
 * client write.
 *
 * **Refresh model (ADR-016 §10 Q2 resolution, 41.2a).** Migration 017
 * deliberately disables Realtime for `subscriptions` — the 8th-channel
 * cost was not justified. Refreshes happen via:
 * - App-foreground hook (`SubscriptionRepository.refresh`)
 * - Post-purchase RevenueCat callback (Phase 41.3 wires the call)
 * - Cold-launch on app boot
 *
 * **Authorship.** [originalTransactionId] uniquely identifies the
 * subscription within its [platform]:
 * - Apple: `originalTransactionId` from the JWS payload.
 * - Google: `purchaseToken` from `Subscription.purchaseToken`.
 * - alpha-grant: `alpha-<user_id>` (deterministic, idempotent).
 *
 * Nullable in this client mirror because postgrest may surface the column
 * as `null` if the writer omits it; the server CHECK does not require it.
 *
 * **Why the receipt JSONB is omitted from this domain class.** The raw
 * receipt blob is server-side audit data. The client does not parse it
 * and exposing it through the domain layer would inflate the type without
 * value. If a future debug surface needs it, add a sibling
 * [SubscriptionReceiptDetails] type rather than widening this class.
 */
@Serializable
data class Subscription(
    val id: String,
    @SerialName("user_id") val userId: String,
    val platform: SubscriptionPlatform,
    @SerialName("product_id") val productId: String,
    val status: SubscriptionStatus,
    @SerialName("original_transaction_id") val originalTransactionId: String?,
    @SerialName("expires_at") val expiresAt: Instant?,
    @SerialName("is_in_trial") val isInTrial: Boolean = false,
    @SerialName("auto_renew_status") val autoRenewStatus: Boolean = true,
    @SerialName("last_verified_at") val lastVerifiedAt: Instant,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
) {
    /**
     * Pro-entitlement gate evaluated against [now]. Pure function — no
     * network, no clock side effect — so EntitlementResolver can call it
     * synchronously on the catalog hot path.
     *
     * Returns true when the row is in an active billing state AND the
     * subscription period has not elapsed:
     * - [SubscriptionStatus.ACTIVE] / [SubscriptionStatus.IN_GRACE_PERIOD]
     *   are the active billing states.
     * - [SubscriptionStatus.CANCELED] is intentionally NOT included.
     *   **Contract assumed of `verify-receipt`:** `canceled` is only
     *   written to the row after the paid period has fully elapsed —
     *   during the until-expiry window after a user cancellation the row
     *   remains [SubscriptionStatus.ACTIVE] (with `auto_renew_status =
     *   false`). Apple StoreKit 2 emits `subscribed` until expiry; Google
     *   Play emits `ACTIVE` with `autoRenewing = false`. Neither platform
     *   ships a "canceled-but-still-paid-for" status. If a future Edge
     *   Function change starts synthesizing `canceled` mid-period from
     *   `autoRenewStatus = false`, this gate AND the
     *   [SubscriptionStatusTest] coverage must move CANCELED into the
     *   active allow-list — failure to do so would silently revoke Pro
     *   for users still in their paid period.
     * - [expiresAt] is `null` for the alpha-grant sentinel — perpetual until
     *   the alpha closes via an admin SQL pass.
     */
    fun isActiveAt(now: Instant): Boolean {
        if (status != SubscriptionStatus.ACTIVE && status != SubscriptionStatus.IN_GRACE_PERIOD) {
            return false
        }
        return expiresAt == null || expiresAt > now
    }

    /** Convenience for callers that want to evaluate against the current wall clock. */
    fun isActiveNow(clock: Clock = Clock.System): Boolean = isActiveAt(clock.now())
}
