// Phase 39 W5 — index.ts (handler) test suite.
//
// Coverage: HTTP method handling, env-var-missing path, input
// validation matrix, rate limiting, GitHub round-trip integration
// (success / GitHub auth fail / GitHub api fail).

import { assert, assertEquals, assertStringIncludes } from "@std/assert";

import { _resetCachesForTests } from "./github_app.ts";
import { _resetRateLimitMapForTests, handleRequest } from "./index.ts";
import { generateTestGithubAppCredentials, installFakeFetch, jsonResponse } from "./_fakes.ts";

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

async function setEnvFromTestCreds(): Promise<void> {
    const creds = await generateTestGithubAppCredentials();
    Deno.env.set("SKEINLY_BUGREPORT_APP_ID", creds.appId);
    Deno.env.set("SKEINLY_BUGREPORT_INSTALLATION_ID", creds.installationId);
    Deno.env.set("SKEINLY_BUGREPORT_PRIVATE_KEY_PEM", creds.privateKeyPem);
}

function clearEnv(): void {
    Deno.env.delete("SKEINLY_BUGREPORT_APP_ID");
    Deno.env.delete("SKEINLY_BUGREPORT_INSTALLATION_ID");
    Deno.env.delete("SKEINLY_BUGREPORT_PRIVATE_KEY_PEM");
}

function buildRequest(body: unknown, init?: RequestInit): Request {
    return new Request("https://test.invalid/functions/v1/submit-bug-report", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "Authorization": "Bearer sb_anon_test",
            "x-real-ip": "203.0.113.42",
        },
        body: JSON.stringify(body),
        ...init,
    });
}

function resetState(): void {
    _resetCachesForTests();
    _resetRateLimitMapForTests();
}

// ---------------------------------------------------------------------
// HTTP method handling
// ---------------------------------------------------------------------

Deno.test("handleRequest OPTIONS returns 204", async () => {
    resetState();
    const resp = await handleRequest(new Request("https://t/", { method: "OPTIONS" }));
    assertEquals(resp.status, 204);
});

Deno.test("handleRequest GET returns 405", async () => {
    resetState();
    const resp = await handleRequest(new Request("https://t/", { method: "GET" }));
    assertEquals(resp.status, 405);
});

// ---------------------------------------------------------------------
// Config missing
// ---------------------------------------------------------------------

Deno.test("handleRequest returns CONFIG_MISSING when env vars absent", async () => {
    resetState();
    clearEnv();
    const resp = await handleRequest(buildRequest({ title: "t", body: "b" }));
    assertEquals(resp.status, 200);
    const json = await resp.json();
    assertEquals(json.ok, false);
    assertEquals(json.code, "CONFIG_MISSING");
});

// ---------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------

Deno.test("handleRequest rejects non-string title with VALIDATION_FAILED", async () => {
    resetState();
    await setEnvFromTestCreds();
    const resp = await handleRequest(buildRequest({ title: 42, body: "b" }));
    const json = await resp.json();
    assertEquals(json.code, "VALIDATION_FAILED");
});

Deno.test("handleRequest rejects empty title with VALIDATION_FAILED", async () => {
    resetState();
    await setEnvFromTestCreds();
    const resp = await handleRequest(buildRequest({ title: "", body: "b" }));
    const json = await resp.json();
    assertEquals(json.code, "VALIDATION_FAILED");
});

Deno.test("handleRequest rejects title with newline", async () => {
    resetState();
    await setEnvFromTestCreds();
    const resp = await handleRequest(buildRequest({ title: "line1\nline2", body: "b" }));
    const json = await resp.json();
    assertEquals(json.code, "VALIDATION_FAILED");
    assertStringIncludes(json.message, "newline");
});

Deno.test("handleRequest rejects oversized title", async () => {
    resetState();
    await setEnvFromTestCreds();
    const longTitle = "x".repeat(257);
    const resp = await handleRequest(buildRequest({ title: longTitle, body: "b" }));
    const json = await resp.json();
    assertEquals(json.code, "VALIDATION_FAILED");
});

Deno.test("handleRequest rejects oversized body", async () => {
    resetState();
    await setEnvFromTestCreds();
    const longBody = "x".repeat(65_537);
    const resp = await handleRequest(buildRequest({ title: "t", body: longBody }));
    const json = await resp.json();
    assertEquals(json.code, "VALIDATION_FAILED");
});

Deno.test("handleRequest rejects non-array labels", async () => {
    resetState();
    await setEnvFromTestCreds();
    const resp = await handleRequest(buildRequest({ title: "t", body: "b", labels: "not-array" }));
    const json = await resp.json();
    assertEquals(json.code, "VALIDATION_FAILED");
});

Deno.test("handleRequest rejects too many labels", async () => {
    resetState();
    await setEnvFromTestCreds();
    const resp = await handleRequest(
        buildRequest({ title: "t", body: "b", labels: ["a", "b", "c", "d", "e", "f"] }),
    );
    const json = await resp.json();
    assertEquals(json.code, "VALIDATION_FAILED");
});

Deno.test("handleRequest rejects malformed JSON body", async () => {
    resetState();
    await setEnvFromTestCreds();
    const req = new Request("https://t/", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "Authorization": "Bearer x",
            "x-real-ip": "1.2.3.4",
        },
        body: "{not json",
    });
    const resp = await handleRequest(req);
    const json = await resp.json();
    assertEquals(json.code, "VALIDATION_FAILED");
});

// ---------------------------------------------------------------------
// Rate limit
// ---------------------------------------------------------------------

Deno.test("handleRequest rate-limits after 5 successful submissions from same source", async () => {
    resetState();
    await setEnvFromTestCreds();
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const fake = installFakeFetch((req) => {
        if (req.url.includes("/access_tokens")) {
            return jsonResponse({ token: "ghs_x", expires_at: expiresAt });
        }
        return jsonResponse({ number: 1, html_url: "u" }, 201);
    });
    try {
        for (let i = 0; i < 5; i++) {
            const resp = await handleRequest(buildRequest({ title: `t${i}`, body: "b" }));
            const json = await resp.json();
            assertEquals(json.ok, true, `submission ${i + 1} should succeed`);
        }
        const sixth = await handleRequest(buildRequest({ title: "t6", body: "b" }));
        const json = await sixth.json();
        assertEquals(json.ok, false);
        assertEquals(json.code, "RATE_LIMITED");
        assertStringIncludes(json.message, "minute");
    } finally {
        fake.restore();
    }
});

Deno.test("handleRequest rate limit is per-source-hash (different IPs independent)", async () => {
    resetState();
    await setEnvFromTestCreds();
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const fake = installFakeFetch((req) => {
        if (req.url.includes("/access_tokens")) {
            return jsonResponse({ token: "ghs_x", expires_at: expiresAt });
        }
        return jsonResponse({ number: 1, html_url: "u" }, 201);
    });
    try {
        // Exhaust IP A
        for (let i = 0; i < 5; i++) {
            await handleRequest(buildRequest({ title: `a${i}`, body: "b" }));
        }
        // IP B is independent
        const req = new Request("https://t/", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "Authorization": "Bearer sb_anon_test",
                "x-real-ip": "198.51.100.7",
            },
            body: JSON.stringify({ title: "b1", body: "b" }),
        });
        const resp = await handleRequest(req);
        const json = await resp.json();
        assertEquals(json.ok, true, "different IP should not be rate-limited");
    } finally {
        fake.restore();
    }
});

// ---------------------------------------------------------------------
// Happy path
// ---------------------------------------------------------------------

Deno.test("handleRequest success returns issue_number + html_url envelope", async () => {
    resetState();
    await setEnvFromTestCreds();
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const fake = installFakeFetch((req) => {
        if (req.url.includes("/access_tokens")) {
            return jsonResponse({ token: "ghs_x", expires_at: expiresAt });
        }
        if (req.url.includes("/repos/b150005/skeinly/issues")) {
            return jsonResponse(
                { number: 42, html_url: "https://github.com/b150005/skeinly/issues/42" },
                201,
            );
        }
        return jsonResponse({ error: "unexpected" }, 500);
    });
    try {
        const resp = await handleRequest(buildRequest({ title: "[Beta] tap save crashes", body: "## Description\n…" }));
        assertEquals(resp.status, 200);
        const json = await resp.json();
        assertEquals(json.ok, true);
        assertEquals(json.issue_number, 42);
        assertEquals(json.html_url, "https://github.com/b150005/skeinly/issues/42");
    } finally {
        fake.restore();
    }
});

Deno.test("handleRequest defaults labels to beta-bug when omitted", async () => {
    resetState();
    await setEnvFromTestCreds();
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const fake = installFakeFetch((req) => {
        if (req.url.includes("/access_tokens")) {
            return jsonResponse({ token: "ghs_x", expires_at: expiresAt });
        }
        return jsonResponse({ number: 1, html_url: "u" }, 201);
    });
    try {
        await handleRequest(buildRequest({ title: "t", body: "b" }));
        const issueCall = fake.calls.find((c) => c.url.includes("/issues"));
        assert(issueCall !== undefined);
        const payload = JSON.parse(issueCall.body ?? "{}");
        assertEquals(payload.labels, ["beta-bug"]);
    } finally {
        fake.restore();
    }
});

// ---------------------------------------------------------------------
// GitHub error envelope mapping
// ---------------------------------------------------------------------

Deno.test("handleRequest GitHub auth failure surfaces as GITHUB_AUTH_FAILED", async () => {
    resetState();
    await setEnvFromTestCreds();
    const fake = installFakeFetch(() => jsonResponse({ message: "Bad credentials" }, 401));
    try {
        const resp = await handleRequest(buildRequest({ title: "t", body: "b" }));
        const json = await resp.json();
        assertEquals(json.ok, false);
        assertEquals(json.code, "GITHUB_AUTH_FAILED");
    } finally {
        fake.restore();
    }
});

Deno.test("handleRequest GitHub 5xx surfaces as GITHUB_API_FAILED", async () => {
    resetState();
    await setEnvFromTestCreds();
    const fake = installFakeFetch(() => jsonResponse({ error: "down" }, 503));
    try {
        const resp = await handleRequest(buildRequest({ title: "t", body: "b" }));
        const json = await resp.json();
        assertEquals(json.ok, false);
        assertEquals(json.code, "GITHUB_API_FAILED");
    } finally {
        fake.restore();
    }
});
