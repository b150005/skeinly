// Pure mapping helpers extracted from `index.ts` so they can be tested
// in isolation without booting Deno.serve or stubbing SupabaseClient.

/**
 * RevenueCat webhook event payload — only the fields Skeinly's webhook
 * actually consumes. Reference:
 * https://www.revenuecat.com/docs/integrations/webhooks/event-flow-and-events
 *
 * The `event_timestamp_ms` field is required (it drives the ordering
 * guard in the upsert RPC); all others are optional from RevenueCat's
 * side and the webhook handler must defend against missing values.
 */
export interface RevenueCatWebhookPayload {
    api_version?: string;
    event?: RevenueCatEvent;
}

export interface RevenueCatEvent {
    id?: string;
    type?: string;
    event_timestamp_ms?: number;
    app_user_id?: string;
    original_transaction_id?: string;
    product_id?: string;
    store?: string;
    environment?: "SANDBOX" | "PRODUCTION";
    expiration_at_ms?: number | null;
    purchased_at_ms?: number;
    is_in_trial?: boolean;
    auto_renew_status?: boolean;
    cancel_reason?: string;
}

/**
 * Type-narrowed event with required fields for downstream processing.
 * `extractWebhookEvent` returns null when any required field is missing,
 * so callers can early-exit with a 400.
 */
export interface ExtractedEvent {
    id: string;
    type: string;
    event_timestamp_ms: number;
    app_user_id?: string;
    original_transaction_id?: string;
    product_id: string;
    store?: string;
    environment?: "SANDBOX" | "PRODUCTION";
    expiration_at_ms?: number | null;
    is_in_trial?: boolean;
    auto_renew_status?: boolean;
    cancel_reason?: string;
}

/**
 * Validate + extract the event with the required fields present.
 * Returns null if the payload doesn't carry the minimum information
 * (event id, type, event_timestamp_ms, product_id) needed to process
 * any event class. TEST events skip product_id check since RevenueCat
 * dashboard test events don't carry one.
 */
export function extractWebhookEvent(payload: RevenueCatWebhookPayload): ExtractedEvent | null {
    const event = payload.event;
    if (!event) return null;
    if (typeof event.id !== "string" || event.id.length === 0) return null;
    if (typeof event.type !== "string" || event.type.length === 0) return null;
    if (
        typeof event.event_timestamp_ms !== "number" ||
        !Number.isFinite(event.event_timestamp_ms)
    ) {
        return null;
    }
    // TEST + SUBSCRIBER_ALIAS + TRANSFER events don't carry product_id.
    // For all other event types, product_id is required.
    const skipProductCheck =
        event.type === "TEST" || event.type === "SUBSCRIBER_ALIAS" || event.type === "TRANSFER";
    if (
        !skipProductCheck &&
        (typeof event.product_id !== "string" || event.product_id.length === 0)
    ) {
        return null;
    }

    return {
        id: event.id,
        type: event.type,
        event_timestamp_ms: event.event_timestamp_ms,
        app_user_id: event.app_user_id,
        original_transaction_id: event.original_transaction_id,
        product_id: event.product_id ?? "",
        store: event.store,
        environment: event.environment,
        expiration_at_ms: event.expiration_at_ms,
        is_in_trial: event.is_in_trial,
        auto_renew_status: event.auto_renew_status,
        cancel_reason: event.cancel_reason,
    };
}

/**
 * Map RevenueCat `event.environment` → Skeinly's `subscriptions.environment`
 * column. RevenueCat sends uppercase ("SANDBOX" / "PRODUCTION"); Postgres
 * convention is lowercase (mirrors `platform` 'ios'/'android' +
 * `suggestions.status` per Phase D terminology audit Migration 027).
 * Defensive default: missing / unknown values
 * map to 'production' so a future RevenueCat schema addition doesn't
 * accidentally classify real production receipts as sandbox.
 *
 * The RPC's CHECK constraint will RAISE on any value other than
 * 'production'/'sandbox', so this mapping is the single normalization
 * point — every code path downstream sees one of those two literals.
 */
export function mapEnvironment(environment: string | undefined): "production" | "sandbox" {
    if (environment === "SANDBOX") return "sandbox";
    return "production";
}

/**
 * Map RevenueCat `store` enum → Skeinly's `platform` enum.
 * Returns null for unsupported stores (Amazon, RC_BILLING, etc.) so the
 * caller can skip the upsert.
 */
export function mapStoreToPlatform(store: string | undefined): "ios" | "android" | null {
    switch (store) {
        case "APP_STORE":
        case "MAC_APP_STORE":
            return "ios";
        case "PLAY_STORE":
            return "android";
        default:
            return null;
    }
}

/**
 * Map RevenueCat event type + cancel_reason → Skeinly's `subscription.status`
 * enum. Returns null for events that should not write a state change
 * (TEST, SUBSCRIBER_ALIAS, TRANSFER are filtered upstream; this catches
 * any future event type the codebase doesn't yet know about).
 *
 * Mapping reference (per RevenueCat docs + ADR-016 §3.1 status enum):
 *   - INITIAL_PURCHASE / RENEWAL / UNCANCELLATION / PRODUCT_CHANGE → 'active'
 *   - CANCELLATION (cancel_reason=BILLING_ERROR)                  → 'in_billing_retry'
 *   - CANCELLATION (cancel_reason=REFUND or REFUNDED_FOR_ISSUE)   → 'refunded'
 *   - CANCELLATION (cancel_reason=other / unspecified)            → 'canceled'
 *   - BILLING_ISSUE                                                → 'in_billing_retry'
 *   - EXPIRATION                                                   → 'expired'
 *   - SUBSCRIPTION_PAUSED                                          → 'canceled'
 *   - REFUND                                                       → 'refunded'
 *   - TEST / SUBSCRIBER_ALIAS / TRANSFER → null (filtered upstream)
 */
export function mapEventToStatus(
    eventType: string,
    cancelReason?: string,
): "active" | "expired" | "canceled" | "in_grace_period" | "in_billing_retry" | "refunded" | null {
    switch (eventType) {
        case "INITIAL_PURCHASE":
        case "RENEWAL":
        case "UNCANCELLATION":
        case "PRODUCT_CHANGE":
            return "active";
        case "CANCELLATION":
            switch (cancelReason) {
                case "BILLING_ERROR":
                    return "in_billing_retry";
                case "REFUND":
                case "REFUNDED_FOR_ISSUE":
                    return "refunded";
                default:
                    return "canceled";
            }
        case "BILLING_ISSUE":
            return "in_billing_retry";
        case "EXPIRATION":
            return "expired";
        case "SUBSCRIPTION_PAUSED":
            return "canceled";
        case "REFUND":
            return "refunded";
        default:
            return null;
    }
}
