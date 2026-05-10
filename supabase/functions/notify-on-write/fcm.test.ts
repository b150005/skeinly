// Phase 24.3 (ADR-018 §3.6): unit tests for FCM HTTP v1 send path.
//
// Covers: SA RS256 JWT signing happy path, OAuth access token cache,
// response classification matrix, end-to-end sendFcm under fetch fake,
// 401 retry shape.

import { assertEquals, assertNotEquals, assertStringIncludes } from "@std/assert";
import { createFetchFake, generateTestServiceAccount } from "./_fakes.ts";
import {
    _resetFcmAccessTokenCacheForTests,
    classifyFcmResponse,
    getFcmAccessToken,
    sendFcm,
} from "./fcm.ts";

// ---------------------------------------------------------------------
// OAuth access token caching (§3.2)
// ---------------------------------------------------------------------

Deno.test("getFcmAccessToken: cold call hits OAuth endpoint", async () => {
    _resetFcmAccessTokenCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        fake.setOAuthResponse({ status: 200, accessToken: "fresh-token-1", expiresIn: 3600 });
        const sa = await generateTestServiceAccount();
        const token = await getFcmAccessToken(sa);
        assertEquals(token, "fresh-token-1");
        assertEquals(fake.oauthCallCount(), 1);
    } finally {
        fake.restore();
    }
});

Deno.test("getFcmAccessToken: warm call within margin reuses cache", async () => {
    _resetFcmAccessTokenCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        fake.setOAuthResponse({ status: 200, accessToken: "cached-token", expiresIn: 3600 });
        const sa = await generateTestServiceAccount();
        await getFcmAccessToken(sa);
        await getFcmAccessToken(sa);
        await getFcmAccessToken(sa);
        assertEquals(fake.oauthCallCount(), 1, "OAuth endpoint should be hit only once");
    } finally {
        fake.restore();
    }
});

Deno.test("getFcmAccessToken: cache reset forces refresh", async () => {
    _resetFcmAccessTokenCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        fake.setOAuthResponse({ status: 200, accessToken: "first-token", expiresIn: 3600 });
        const sa = await generateTestServiceAccount();
        const first = await getFcmAccessToken(sa);
        _resetFcmAccessTokenCacheForTests();
        fake.setOAuthResponse({ status: 200, accessToken: "second-token", expiresIn: 3600 });
        const second = await getFcmAccessToken(sa);
        assertNotEquals(first, second);
        assertEquals(fake.oauthCallCount(), 2);
    } finally {
        fake.restore();
    }
});

// ---------------------------------------------------------------------
// Response classification (ADR-018 §3.4)
// ---------------------------------------------------------------------

Deno.test("classifyFcmResponse: 200 → success", () => {
    assertEquals(classifyFcmResponse(200, null), { kind: "success" });
});

Deno.test("classifyFcmResponse: 404 UNREGISTERED → delete_token", () => {
    const out = classifyFcmResponse(404, "UNREGISTERED");
    assertEquals(out.kind, "delete_token");
    if (out.kind === "delete_token") assertEquals(out.reason, "UNREGISTERED");
});

Deno.test("classifyFcmResponse: 403 SENDER_ID_MISMATCH → delete_token", () => {
    const out = classifyFcmResponse(403, "SENDER_ID_MISMATCH");
    assertEquals(out.kind, "delete_token");
});

Deno.test("classifyFcmResponse: 401 UNAUTHENTICATED → transient retry sentinel", () => {
    const out = classifyFcmResponse(401, "UNAUTHENTICATED");
    assertEquals(out.kind, "transient_error");
    if (out.kind === "transient_error") assertEquals(out.reason, "fcm_unauth_retry_pending");
});

Deno.test("classifyFcmResponse: 403 without SENDER_ID_MISMATCH → config_error", () => {
    const out = classifyFcmResponse(403, "PERMISSION_DENIED");
    assertEquals(out.kind, "config_error");
});

Deno.test("classifyFcmResponse: 500 INTERNAL → transient_error", () => {
    const out = classifyFcmResponse(500, "INTERNAL");
    assertEquals(out.kind, "transient_error");
});

Deno.test("classifyFcmResponse: unknown 4xx with null code → transient (not delete)", () => {
    const out = classifyFcmResponse(418, null);
    assertEquals(out.kind, "transient_error");
});

// ---------------------------------------------------------------------
// End-to-end sendFcm
// ---------------------------------------------------------------------

Deno.test("sendFcm: success path", async () => {
    _resetFcmAccessTokenCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        const sa = await generateTestServiceAccount("test-project");
        fake.setOAuthResponse({ status: 200, accessToken: "fcm-tok", expiresIn: 3600 });
        fake.setFcmResponse("good-android-token", { status: 200 });
        const outcome = await sendFcm(sa, {
            deviceToken: "good-android-token",
            body: "test body",
            templateKey: "pr_opened",
            route: "pull-request/pr-test",
        });
        assertEquals(outcome, { kind: "success" });
        // FCM URL targets the SA's project_id.
        const fcmReqs = fake
            .snapshotRequests()
            .filter((r) => r.url.startsWith("https://fcm.googleapis.com/"));
        assertEquals(fcmReqs.length, 1);
        assertStringIncludes(fcmReqs[0].url, "/projects/test-project/messages:send");
    } finally {
        fake.restore();
    }
});

Deno.test("sendFcm: 404 UNREGISTERED → delete_token", async () => {
    _resetFcmAccessTokenCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        const sa = await generateTestServiceAccount();
        fake.setOAuthResponse({ status: 200, accessToken: "tok", expiresIn: 3600 });
        fake.setFcmResponse("dead-token", {
            status: 404,
            errorCode: "UNREGISTERED",
            statusName: "NOT_FOUND",
        });
        const outcome = await sendFcm(sa, {
            deviceToken: "dead-token",
            body: "test",
            templateKey: "pr_commented",
            route: "pull-request/pr-test",
        });
        assertEquals(outcome.kind, "delete_token");
    } finally {
        fake.restore();
    }
});

Deno.test("sendFcm: 401 triggers single retry after cache reset", async () => {
    _resetFcmAccessTokenCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        const sa = await generateTestServiceAccount();
        fake.setOAuthResponse({ status: 200, accessToken: "tok-1", expiresIn: 3600 });
        // First attempt: 401. After retry the FCM fake returns success.
        // We have to flip the FCM fake response between the two attempts.
        // Trick: pre-seed token "retry-token" to 401, then test must
        // observe retry by changing the response after the first call.
        fake.setFcmResponse("retry-token", {
            status: 401,
            errorCode: "UNAUTHENTICATED",
            statusName: "UNAUTHENTICATED",
        });
        // We don't have a per-call replay mechanism in the fake; the
        // 401 retry surfaces the second attempt as a fresh OAuth +
        // FCM call. Without flipping mid-flight, both attempts get
        // the same 401 — outcome is still transient_error but with
        // sentinel-stripped reason. Verify that path.
        const outcome = await sendFcm(sa, {
            deviceToken: "retry-token",
            body: "test",
            templateKey: "pr_opened",
            route: "pull-request/pr-test",
        });
        // After two 401s, the second attempt's classification still
        // returns transient (fcm_unauth_retry_pending sentinel).
        assertEquals(outcome.kind, "transient_error");
        // OAuth endpoint hit twice: once for first try, once after cache reset.
        assertEquals(fake.oauthCallCount(), 2, "OAuth must refresh on 401 retry");
    } finally {
        fake.restore();
    }
});
