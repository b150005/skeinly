// Phase 24.3 (ADR-018 §3.6): unit tests for APNs send path.
//
// Covers: ES256 JWT signing happy path, in-instance JWT cache,
// response classification matrix, end-to-end sendApns under fetch
// fake.

import { assertEquals, assertNotEquals, assertStringIncludes } from "jsr:@std/assert@^1";

import {
    _resetApnsJwtCacheForTests,
    classifyApnsResponse,
    getApnsJwt,
    sendApns,
} from "./apns.ts";
import {
    createFetchFake,
    generateTestApnsCredentials,
} from "./_fakes.ts";

// ---------------------------------------------------------------------
// JWT signing
// ---------------------------------------------------------------------

Deno.test("getApnsJwt produces a parseable header.payload.signature triple", async () => {
    _resetApnsJwtCacheForTests();
    const creds = await generateTestApnsCredentials();
    const jwt = await getApnsJwt(creds);
    const parts = jwt.split(".");
    assertEquals(parts.length, 3, "JWT must be header.payload.signature");
    const headerJson = JSON.parse(atob(padBase64Url(parts[0])));
    const payloadJson = JSON.parse(atob(padBase64Url(parts[1])));
    assertEquals(headerJson.alg, "ES256");
    assertEquals(headerJson.kid, creds.keyId);
    assertEquals(headerJson.typ, "JWT");
    assertEquals(payloadJson.iss, creds.teamId);
    assertEquals(typeof payloadJson.iat, "number");
});

Deno.test("getApnsJwt caches across calls within margin", async () => {
    _resetApnsJwtCacheForTests();
    const creds = await generateTestApnsCredentials();
    const first = await getApnsJwt(creds);
    const second = await getApnsJwt(creds);
    assertEquals(first, second, "second call should return cached JWT");
});

Deno.test("getApnsJwt mints fresh after cache reset (iat advances)", async () => {
    _resetApnsJwtCacheForTests();
    const creds = await generateTestApnsCredentials();
    const first = await getApnsJwt(creds);
    // Reset simulates Edge Function instance recycle. Sleep > 1s so
    // the next mint's `iat` claim (in seconds since epoch) advances —
    // proves the cache miss path executed without depending on
    // ECDSA-P-256 signature randomness (Deno's WebCrypto returns
    // deterministic signatures for identical inputs in some runtime
    // versions, so signature-byte-equality is not a reliable signal).
    _resetApnsJwtCacheForTests();
    await new Promise((resolve) => setTimeout(resolve, 1100));
    const second = await getApnsJwt(creds);
    const firstIat = decodeIat(first);
    const secondIat = decodeIat(second);
    assertNotEquals(
        firstIat,
        secondIat,
        "iat should advance between fresh mints separated by >1s",
    );
});

// ---------------------------------------------------------------------
// Response classification (ADR-018 §3.4)
// ---------------------------------------------------------------------

Deno.test("classifyApnsResponse: 200 → success", () => {
    assertEquals(classifyApnsResponse(200, null), { kind: "success" });
});

Deno.test("classifyApnsResponse: 410 Unregistered → delete_token", () => {
    const out = classifyApnsResponse(410, "Unregistered");
    assertEquals(out.kind, "delete_token");
    if (out.kind === "delete_token") assertEquals(out.reason, "Unregistered");
});

Deno.test("classifyApnsResponse: 400 BadDeviceToken → delete_token", () => {
    const out = classifyApnsResponse(400, "BadDeviceToken");
    assertEquals(out.kind, "delete_token");
});

Deno.test("classifyApnsResponse: 400 DeviceTokenNotForTopic → delete_token", () => {
    const out = classifyApnsResponse(400, "DeviceTokenNotForTopic");
    assertEquals(out.kind, "delete_token");
});

Deno.test("classifyApnsResponse: 403 InvalidProviderToken → config_error", () => {
    const out = classifyApnsResponse(403, "InvalidProviderToken");
    assertEquals(out.kind, "config_error");
});

Deno.test("classifyApnsResponse: 429 TooManyRequests → transient_error", () => {
    const out = classifyApnsResponse(429, "TooManyRequests");
    assertEquals(out.kind, "transient_error");
});

Deno.test("classifyApnsResponse: 503 ServiceUnavailable → transient_error", () => {
    const out = classifyApnsResponse(503, "ServiceUnavailable");
    assertEquals(out.kind, "transient_error");
});

Deno.test("classifyApnsResponse: unknown 4xx reason falls through to transient_error not delete", () => {
    // ADR-018 §3.4 fail-safe: never delete on unknown reason.
    const out = classifyApnsResponse(400, "FutureUnknownReason");
    assertEquals(out.kind, "transient_error");
});

Deno.test("classifyApnsResponse: unknown 5xx with null reason → transient_error", () => {
    const out = classifyApnsResponse(599, null);
    assertEquals(out.kind, "transient_error");
});

// ---------------------------------------------------------------------
// End-to-end sendApns
// ---------------------------------------------------------------------

Deno.test("sendApns: success path", async () => {
    _resetApnsJwtCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        const creds = await generateTestApnsCredentials();
        fake.setApnsResponse("good-token", { status: 200 });
        const outcome = await sendApns(creds, {
            deviceToken: "good-token",
            body: "Alice opened a pull request on Sweater",
            templateKey: "pr_opened",
        });
        assertEquals(outcome, { kind: "success" });
        // Verify the URL targeted production host.
        const requests = fake.snapshotRequests();
        assertEquals(requests.length, 1);
        assertStringIncludes(requests[0].url, "https://api.push.apple.com/3/device/good-token");
    } finally {
        fake.restore();
    }
});

Deno.test("sendApns: 410 Unregistered → delete_token outcome", async () => {
    _resetApnsJwtCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        const creds = await generateTestApnsCredentials();
        fake.setApnsResponse("dead-token", { status: 410, reason: "Unregistered" });
        const outcome = await sendApns(creds, {
            deviceToken: "dead-token",
            body: "test",
            templateKey: "pr_commented",
        });
        assertEquals(outcome.kind, "delete_token");
    } finally {
        fake.restore();
    }
});

Deno.test("sendApns: 400 BadDeviceToken → delete_token outcome", async () => {
    _resetApnsJwtCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        const creds = await generateTestApnsCredentials();
        fake.setApnsResponse("malformed", { status: 400, reason: "BadDeviceToken" });
        const outcome = await sendApns(creds, {
            deviceToken: "malformed",
            body: "test",
            templateKey: "pr_opened",
        });
        assertEquals(outcome.kind, "delete_token");
    } finally {
        fake.restore();
    }
});

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

function padBase64Url(input: string): string {
    let s = input.replace(/-/g, "+").replace(/_/g, "/");
    while (s.length % 4 !== 0) s += "=";
    return s;
}

function decodeIat(jwt: string): number {
    const payload = JSON.parse(atob(padBase64Url(jwt.split(".")[1])));
    return payload.iat as number;
}
