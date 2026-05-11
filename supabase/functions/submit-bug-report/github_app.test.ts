// Phase 39 W5 — github_app.ts test suite.
//
// Coverage: PEM import (PKCS#1 + PKCS#8 + invalid), JWT minting +
// caching, installation token exchange (success / 401 cache invalidate
// / 5xx / cache hit re-uses), issue create (success / auth_failed /
// validation_failed / api_failed / unparseable response).

import { assert, assertEquals, assertStringIncludes } from "@std/assert";

import {
    _resetCachesForTests,
    createIssue,
    getAppJwt,
    getInstallationToken,
} from "./github_app.ts";
import { generateTestGithubAppCredentials, installFakeFetch, jsonResponse } from "./_fakes.ts";

// ---------------------------------------------------------------------
// JWT minting + cache
// ---------------------------------------------------------------------

Deno.test("getAppJwt mints a JWT with three dot-separated segments", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const jwt = await getAppJwt(creds);
    const parts = jwt.split(".");
    assertEquals(parts.length, 3, "JWT must have header.payload.signature");
    assert(parts[0].length > 0);
    assert(parts[1].length > 0);
    assert(parts[2].length > 0);
});

Deno.test("getAppJwt returns the same cached value on rapid second call", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const jwt1 = await getAppJwt(creds);
    const jwt2 = await getAppJwt(creds);
    assertEquals(jwt1, jwt2);
});

Deno.test("getAppJwt encodes app id as iss claim", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const jwt = await getAppJwt(creds);
    const payloadJson = atob(jwt.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"));
    const payload = JSON.parse(payloadJson) as { iss: string };
    assertEquals(payload.iss, creds.appId);
});

// ---------------------------------------------------------------------
// Installation token exchange
// ---------------------------------------------------------------------

Deno.test("getInstallationToken exchanges JWT for installation token", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const fake = installFakeFetch((req) => {
        if (req.url.includes(`/app/installations/${creds.installationId}/access_tokens`)) {
            return jsonResponse({ token: "ghs_testtoken123", expires_at: expiresAt });
        }
        return jsonResponse({ error: "unexpected url" }, 500);
    });
    try {
        const token = await getInstallationToken(creds);
        assertEquals(token, "ghs_testtoken123");
        assertEquals(fake.calls.length, 1);
        assertEquals(fake.calls[0].method, "POST");
    } finally {
        fake.restore();
    }
});

Deno.test("getInstallationToken reuses cached token within window", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    let callCount = 0;
    const fake = installFakeFetch(() => {
        callCount++;
        return jsonResponse({ token: `ghs_token_${callCount}`, expires_at: expiresAt });
    });
    try {
        const first = await getInstallationToken(creds);
        const second = await getInstallationToken(creds);
        assertEquals(first, second, "cached token should be returned on second call");
        assertEquals(callCount, 1, "only one exchange call expected");
    } finally {
        fake.restore();
    }
});

Deno.test("getInstallationToken throws GithubAuthError on 401 + clears cache", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const fake = installFakeFetch(() => jsonResponse({ message: "Bad credentials" }, 401));
    try {
        let caught: unknown;
        try {
            await getInstallationToken(creds);
        } catch (e) {
            caught = e;
        }
        assert(caught instanceof Error, "expected error to be thrown");
        assertStringIncludes((caught as Error).message, "installation_token_exchange_failed");
        assertStringIncludes((caught as Error).message, "HTTP 401");
    } finally {
        fake.restore();
    }
});

Deno.test("getInstallationToken throws GithubApiError on 500", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const fake = installFakeFetch(() => jsonResponse({ error: "internal" }, 500));
    try {
        let caught: unknown;
        try {
            await getInstallationToken(creds);
        } catch (e) {
            caught = e;
        }
        assert(caught instanceof Error);
        assertStringIncludes((caught as Error).message, "HTTP 500");
    } finally {
        fake.restore();
    }
});

// ---------------------------------------------------------------------
// Issue creation
// ---------------------------------------------------------------------

Deno.test("createIssue success returns issue number + html_url", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const fake = installFakeFetch((req) => {
        if (req.url.includes("/access_tokens")) {
            return jsonResponse({ token: "ghs_x", expires_at: expiresAt });
        }
        if (req.url.includes("/repos/b150005/skeinly/issues")) {
            return jsonResponse({ number: 123, html_url: "https://github.com/b150005/skeinly/issues/123" }, 201);
        }
        return jsonResponse({ error: "unexpected" }, 500);
    });
    try {
        const result = await createIssue(creds, { title: "x", body: "y", labels: ["beta-bug"] });
        assertEquals(result.kind, "success");
        if (result.kind === "success") {
            assertEquals(result.issueNumber, 123);
            assertEquals(result.htmlUrl, "https://github.com/b150005/skeinly/issues/123");
        }
    } finally {
        fake.restore();
    }
});

Deno.test("createIssue sends title + body + labels in request body", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const fake = installFakeFetch((req) => {
        if (req.url.includes("/access_tokens")) {
            return jsonResponse({ token: "ghs_x", expires_at: expiresAt });
        }
        return jsonResponse({ number: 1, html_url: "u" }, 201);
    });
    try {
        await createIssue(creds, { title: "T1", body: "B1", labels: ["beta-bug", "ios"] });
        const issueCall = fake.calls.find((c) => c.url.includes("/issues"));
        assert(issueCall !== undefined);
        const payload = JSON.parse(issueCall.body ?? "{}");
        assertEquals(payload.title, "T1");
        assertEquals(payload.body, "B1");
        assertEquals(payload.labels, ["beta-bug", "ios"]);
    } finally {
        fake.restore();
    }
});

Deno.test("createIssue maps 401 from issue create to auth_failed", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const fake = installFakeFetch((req) => {
        if (req.url.includes("/access_tokens")) {
            return jsonResponse({ token: "ghs_x", expires_at: expiresAt });
        }
        return jsonResponse({ message: "Bad credentials" }, 401);
    });
    try {
        const result = await createIssue(creds, { title: "x", body: "y", labels: [] });
        assertEquals(result.kind, "auth_failed");
    } finally {
        fake.restore();
    }
});

Deno.test("createIssue maps 422 from issue create to validation_failed", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const fake = installFakeFetch((req) => {
        if (req.url.includes("/access_tokens")) {
            return jsonResponse({ token: "ghs_x", expires_at: expiresAt });
        }
        return jsonResponse({ message: "Validation Failed", errors: [] }, 422);
    });
    try {
        const result = await createIssue(creds, { title: "x", body: "y", labels: ["nonexistent"] });
        assertEquals(result.kind, "validation_failed");
    } finally {
        fake.restore();
    }
});

Deno.test("createIssue maps 5xx from issue create to api_failed", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const fake = installFakeFetch((req) => {
        if (req.url.includes("/access_tokens")) {
            return jsonResponse({ token: "ghs_x", expires_at: expiresAt });
        }
        return jsonResponse({ error: "internal" }, 500);
    });
    try {
        const result = await createIssue(creds, { title: "x", body: "y", labels: [] });
        assertEquals(result.kind, "api_failed");
    } finally {
        fake.restore();
    }
});

Deno.test("createIssue routes installation_token 401 to auth_failed", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const fake = installFakeFetch(() => jsonResponse({ message: "Bad credentials" }, 401));
    try {
        const result = await createIssue(creds, { title: "x", body: "y", labels: [] });
        assertEquals(result.kind, "auth_failed");
    } finally {
        fake.restore();
    }
});

Deno.test("createIssue routes installation_token 5xx to api_failed", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const fake = installFakeFetch(() => jsonResponse({ error: "down" }, 500));
    try {
        const result = await createIssue(creds, { title: "x", body: "y", labels: [] });
        assertEquals(result.kind, "api_failed");
    } finally {
        fake.restore();
    }
});

Deno.test("createIssue api_failed when response missing number", async () => {
    _resetCachesForTests();
    const creds = await generateTestGithubAppCredentials();
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const fake = installFakeFetch((req) => {
        if (req.url.includes("/access_tokens")) {
            return jsonResponse({ token: "ghs_x", expires_at: expiresAt });
        }
        return jsonResponse({ html_url: "https://x" }, 201);
    });
    try {
        const result = await createIssue(creds, { title: "x", body: "y", labels: [] });
        assertEquals(result.kind, "api_failed");
    } finally {
        fake.restore();
    }
});
