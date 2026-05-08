// Deno tests for the pure mapping helpers in `revenuecat-webhook`.
// Run locally with:
//
//   deno test supabase/functions/revenuecat-webhook/
//
// Not currently CI-gated — these are regression anchors for the event-
// type → status mapping that drives `subscriptions.status` writes.

import {
    extractWebhookEvent,
    mapEnvironment,
    mapEventToStatus,
    mapStoreToPlatform,
} from "./mapping.ts";
import { assertEquals } from "jsr:@std/assert@1";

// ---------------------------------------------------------------------
// extractWebhookEvent
// ---------------------------------------------------------------------

Deno.test("extractWebhookEvent: returns null when event is missing", () => {
    assertEquals(extractWebhookEvent({}), null);
});

Deno.test("extractWebhookEvent: returns null when event.id is missing", () => {
    assertEquals(
        extractWebhookEvent({
            event: {
                type: "INITIAL_PURCHASE",
                event_timestamp_ms: 1700000000000,
                product_id: "skeinly.pro.monthly",
            },
        }),
        null,
    );
});

Deno.test("extractWebhookEvent: returns null when event.type is missing", () => {
    assertEquals(
        extractWebhookEvent({
            event: {
                id: "evt-1",
                event_timestamp_ms: 1700000000000,
                product_id: "skeinly.pro.monthly",
            },
        }),
        null,
    );
});

Deno.test("extractWebhookEvent: returns null when event_timestamp_ms is missing or non-finite", () => {
    assertEquals(
        extractWebhookEvent({
            event: {
                id: "evt-1",
                type: "RENEWAL",
                product_id: "skeinly.pro.monthly",
            },
        }),
        null,
    );
    assertEquals(
        extractWebhookEvent({
            event: {
                id: "evt-1",
                type: "RENEWAL",
                event_timestamp_ms: NaN,
                product_id: "skeinly.pro.monthly",
            },
        }),
        null,
    );
});

Deno.test("extractWebhookEvent: returns null when product_id missing on a state-change event", () => {
    assertEquals(
        extractWebhookEvent({
            event: {
                id: "evt-1",
                type: "INITIAL_PURCHASE",
                event_timestamp_ms: 1700000000000,
            },
        }),
        null,
    );
});

Deno.test("extractWebhookEvent: skips product_id check for TEST events", () => {
    const result = extractWebhookEvent({
        event: {
            id: "evt-test",
            type: "TEST",
            event_timestamp_ms: 1700000000000,
        },
    });
    assertEquals(result?.type, "TEST");
});

Deno.test("extractWebhookEvent: skips product_id check for SUBSCRIBER_ALIAS events", () => {
    const result = extractWebhookEvent({
        event: {
            id: "evt-alias",
            type: "SUBSCRIBER_ALIAS",
            event_timestamp_ms: 1700000000000,
        },
    });
    assertEquals(result?.type, "SUBSCRIBER_ALIAS");
});

Deno.test("extractWebhookEvent: full happy path preserves all fields", () => {
    const result = extractWebhookEvent({
        api_version: "1.0",
        event: {
            id: "evt-7",
            type: "RENEWAL",
            event_timestamp_ms: 1700000000000,
            app_user_id: "11111111-1111-1111-1111-111111111111",
            original_transaction_id: "tx-1234",
            product_id: "skeinly.pro.monthly",
            store: "APP_STORE",
            environment: "SANDBOX",
            expiration_at_ms: 1702592000000,
            is_in_trial: false,
            auto_renew_status: true,
        },
    });
    assertEquals(result?.id, "evt-7");
    assertEquals(result?.type, "RENEWAL");
    assertEquals(result?.app_user_id, "11111111-1111-1111-1111-111111111111");
    assertEquals(result?.product_id, "skeinly.pro.monthly");
    assertEquals(result?.store, "APP_STORE");
    assertEquals(result?.environment, "SANDBOX");
    assertEquals(result?.expiration_at_ms, 1702592000000);
});

// ---------------------------------------------------------------------
// mapEnvironment
// ---------------------------------------------------------------------

Deno.test("mapEnvironment: SANDBOX → sandbox", () => {
    assertEquals(mapEnvironment("SANDBOX"), "sandbox");
});

Deno.test("mapEnvironment: PRODUCTION → production", () => {
    assertEquals(mapEnvironment("PRODUCTION"), "production");
});

Deno.test("mapEnvironment: undefined defaults to production", () => {
    assertEquals(mapEnvironment(undefined), "production");
});

Deno.test("mapEnvironment: unknown value defaults to production (defensive)", () => {
    assertEquals(mapEnvironment("STAGING"), "production");
});

Deno.test("mapEnvironment: lowercase sandbox does NOT match (case-sensitive)", () => {
    // RevenueCat always uppercases — we do not accept lowercase to keep
    // the contract tight.
    assertEquals(mapEnvironment("sandbox"), "production");
});

// ---------------------------------------------------------------------
// mapStoreToPlatform
// ---------------------------------------------------------------------

Deno.test("mapStoreToPlatform: APP_STORE maps to ios", () => {
    assertEquals(mapStoreToPlatform("APP_STORE"), "ios");
});

Deno.test("mapStoreToPlatform: MAC_APP_STORE also maps to ios", () => {
    assertEquals(mapStoreToPlatform("MAC_APP_STORE"), "ios");
});

Deno.test("mapStoreToPlatform: PLAY_STORE maps to android", () => {
    assertEquals(mapStoreToPlatform("PLAY_STORE"), "android");
});

Deno.test("mapStoreToPlatform: AMAZON returns null (unsupported)", () => {
    assertEquals(mapStoreToPlatform("AMAZON"), null);
});

Deno.test("mapStoreToPlatform: undefined returns null", () => {
    assertEquals(mapStoreToPlatform(undefined), null);
});

// ---------------------------------------------------------------------
// mapEventToStatus
// ---------------------------------------------------------------------

Deno.test("mapEventToStatus: INITIAL_PURCHASE → active", () => {
    assertEquals(mapEventToStatus("INITIAL_PURCHASE"), "active");
});

Deno.test("mapEventToStatus: RENEWAL → active", () => {
    assertEquals(mapEventToStatus("RENEWAL"), "active");
});

Deno.test("mapEventToStatus: UNCANCELLATION → active", () => {
    assertEquals(mapEventToStatus("UNCANCELLATION"), "active");
});

Deno.test("mapEventToStatus: PRODUCT_CHANGE → active", () => {
    assertEquals(mapEventToStatus("PRODUCT_CHANGE"), "active");
});

Deno.test("mapEventToStatus: CANCELLATION default → canceled", () => {
    assertEquals(mapEventToStatus("CANCELLATION"), "canceled");
});

Deno.test("mapEventToStatus: CANCELLATION + BILLING_ERROR → in_billing_retry", () => {
    assertEquals(mapEventToStatus("CANCELLATION", "BILLING_ERROR"), "in_billing_retry");
});

Deno.test("mapEventToStatus: CANCELLATION + REFUND → refunded", () => {
    assertEquals(mapEventToStatus("CANCELLATION", "REFUND"), "refunded");
});

Deno.test("mapEventToStatus: CANCELLATION + REFUNDED_FOR_ISSUE → refunded", () => {
    assertEquals(
        mapEventToStatus("CANCELLATION", "REFUNDED_FOR_ISSUE"),
        "refunded",
    );
});

Deno.test("mapEventToStatus: BILLING_ISSUE → in_billing_retry", () => {
    assertEquals(mapEventToStatus("BILLING_ISSUE"), "in_billing_retry");
});

Deno.test("mapEventToStatus: EXPIRATION → expired", () => {
    assertEquals(mapEventToStatus("EXPIRATION"), "expired");
});

Deno.test("mapEventToStatus: SUBSCRIPTION_PAUSED → canceled", () => {
    assertEquals(mapEventToStatus("SUBSCRIPTION_PAUSED"), "canceled");
});

Deno.test("mapEventToStatus: REFUND → refunded", () => {
    assertEquals(mapEventToStatus("REFUND"), "refunded");
});

Deno.test("mapEventToStatus: TEST → null (filtered upstream)", () => {
    assertEquals(mapEventToStatus("TEST"), null);
});

Deno.test("mapEventToStatus: SUBSCRIBER_ALIAS → null (filtered upstream)", () => {
    assertEquals(mapEventToStatus("SUBSCRIBER_ALIAS"), null);
});

Deno.test("mapEventToStatus: TRANSFER → null (filtered upstream)", () => {
    assertEquals(mapEventToStatus("TRANSFER"), null);
});

Deno.test("mapEventToStatus: unknown future type → null", () => {
    assertEquals(mapEventToStatus("FUTURE_UNKNOWN_TYPE"), null);
});
