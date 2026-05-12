// Pre-alpha A1/A5 — index.ts (handler) test suite.
//
// Coverage: HTTP method handling, env-var-missing path, JWT extraction,
// rate limiting per auth.uid(), validation matrix, INSERT-fail handling,
// GitHub Issue success/failure mapping, github_issue_url backfill.

import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import { fakeBearerForUser, generateTestGithubAppCredentials, installFakeFetch, jsonResponse } from "./_fakes.ts";
import { _resetCachesForTests } from "./github_app.ts";
import {
    _resetRateLimitMapForTests,
    extractUserIdFromAuthHeader,
    handleRequest,
} from "./index.ts";

// ---------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------

const TEST_USER_A = "11111111-1111-1111-1111-111111111111";
const TEST_USER_B = "22222222-2222-2222-2222-222222222222";
const TEST_SUPABASE_URL = "https://test.supabase.invalid";
const TEST_TARGET_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeffff0000";

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

async function setEnvFromTestCreds(): Promise<void> {
    const creds = await generateTestGithubAppCredentials();
    Deno.env.set("SUPABASE_URL", TEST_SUPABASE_URL);
    Deno.env.set("SUPABASE_ANON_KEY", "anon-test-key");
    Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "service-test-key");
    Deno.env.set("SKEINLY_BUGREPORT_APP_ID", creds.appId);
    Deno.env.set("SKEINLY_BUGREPORT_INSTALLATION_ID", creds.installationId);
    Deno.env.set("SKEINLY_BUGREPORT_PRIVATE_KEY_PEM", creds.privateKeyPem);
}

function clearEnv(): void {
    Deno.env.delete("SUPABASE_URL");
    Deno.env.delete("SUPABASE_ANON_KEY");
    Deno.env.delete("SUPABASE_SERVICE_ROLE_KEY");
    Deno.env.delete("SKEINLY_BUGREPORT_APP_ID");
    Deno.env.delete("SKEINLY_BUGREPORT_INSTALLATION_ID");
    Deno.env.delete("SKEINLY_BUGREPORT_PRIVATE_KEY_PEM");
}

function buildRequest(
    body: unknown,
    userId: string = TEST_USER_A,
    overrides?: { method?: string; authHeader?: string | null },
): Request {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (overrides?.authHeader !== null) {
        headers.Authorization = overrides?.authHeader ?? fakeBearerForUser(userId);
    }
    return new Request("https://test.invalid/functions/v1/submit-ugc-report", {
        method: overrides?.method ?? "POST",
        headers,
        body: typeof body === "string" ? body : JSON.stringify(body),
    });
}

function validBody(overrides?: Record<string, unknown>): Record<string, unknown> {
    return {
        target_type: "pattern",
        target_id: TEST_TARGET_UUID,
        reason: "Spam pattern.",
        reason_category: "spam",
        ...overrides,
    };
}

function resetState(): void {
    _resetCachesForTests();
    _resetRateLimitMapForTests();
}

interface FakeOptions {
    insertId?: string;
    insertStatus?: number;
    issueNumber?: number;
    issueHtmlUrl?: string;
    issueStatus?: number;
    tokenExpiresAt?: string;
    updateStatus?: number;
}

/** Standard fake responder for the happy path. Caller can override
 *  individual responses to simulate failures. Tracks each call so
 *  tests can assert ordering. */
function installStandardFakes(opts: FakeOptions = {}) {
    const insertId = opts.insertId ?? "33333333-3333-3333-3333-333333333333";
    const insertStatus = opts.insertStatus ?? 201;
    const issueNumber = opts.issueNumber ?? 7;
    const issueHtmlUrl = opts.issueHtmlUrl ?? "https://github.com/b150005/skeinly/issues/7";
    const issueStatus = opts.issueStatus ?? 201;
    const tokenExpiresAt = opts.tokenExpiresAt ?? new Date(Date.now() + 60 * 60 * 1000).toISOString();
    const updateStatus = opts.updateStatus ?? 200;

    return installFakeFetch((req) => {
        const url = req.url;
        if (url.includes("/access_tokens")) {
            return jsonResponse({ token: "ghs_x", expires_at: tokenExpiresAt });
        }
        if (url.includes("/repos/b150005/skeinly/issues")) {
            if (issueStatus >= 400) {
                return jsonResponse({ message: "synthetic failure" }, issueStatus);
            }
            return jsonResponse({ number: issueNumber, html_url: issueHtmlUrl }, issueStatus);
        }
        if (url.includes("/rest/v1/ugc_reports")) {
            if (req.method === "POST") {
                if (insertStatus >= 400) {
                    return jsonResponse({ message: "insert failed" }, insertStatus);
                }
                return jsonResponse({ id: insertId }, insertStatus);
            }
            if (req.method === "PATCH") {
                return new Response(null, { status: updateStatus });
            }
        }
        return jsonResponse({ error: "unexpected_url", url }, 500);
    });
}

// ---------------------------------------------------------------------
// extractUserIdFromAuthHeader (unit)
// ---------------------------------------------------------------------

Deno.test("extractUserIdFromAuthHeader returns null on missing header", () => {
    assertEquals(extractUserIdFromAuthHeader(null), null);
});

Deno.test("extractUserIdFromAuthHeader returns null on non-Bearer prefix", () => {
    assertEquals(extractUserIdFromAuthHeader("Basic abcdef"), null);
});

Deno.test("extractUserIdFromAuthHeader returns null on malformed JWT (< 3 parts)", () => {
    assertEquals(extractUserIdFromAuthHeader("Bearer only.two"), null);
});

Deno.test("extractUserIdFromAuthHeader returns null when payload has no sub", () => {
    const payload = btoa(JSON.stringify({ role: "authenticated" })).replace(/=/g, "");
    assertEquals(extractUserIdFromAuthHeader(`Bearer header.${payload}.sig`), null);
});

Deno.test("extractUserIdFromAuthHeader decodes a valid sub", () => {
    const header = fakeBearerForUser(TEST_USER_A);
    assertEquals(extractUserIdFromAuthHeader(header), TEST_USER_A);
});

// ---------------------------------------------------------------------
// HTTP method handling
// ---------------------------------------------------------------------

Deno.test("handleRequest OPTIONS returns 204", async () => {
    resetState();
    await setEnvFromTestCreds();
    const resp = await handleRequest(new Request("https://t/", { method: "OPTIONS" }));
    assertEquals(resp.status, 204);
});

Deno.test("handleRequest GET returns 405", async () => {
    resetState();
    await setEnvFromTestCreds();
    const resp = await handleRequest(new Request("https://t/", { method: "GET" }));
    assertEquals(resp.status, 405);
});

// ---------------------------------------------------------------------
// Config missing
// ---------------------------------------------------------------------

Deno.test("handleRequest returns CONFIG_MISSING when env vars absent", async () => {
    resetState();
    clearEnv();
    const resp = await handleRequest(buildRequest(validBody()));
    assertEquals(resp.status, 200);
    const json = await resp.json();
    assertEquals(json.ok, false);
    assertEquals(json.code, "CONFIG_MISSING");
});

// ---------------------------------------------------------------------
// Authentication
// ---------------------------------------------------------------------

Deno.test("handleRequest returns UNAUTHORIZED when Authorization header missing", async () => {
    resetState();
    await setEnvFromTestCreds();
    const resp = await handleRequest(buildRequest(validBody(), TEST_USER_A, { authHeader: null }));
    const json = await resp.json();
    assertEquals(json.code, "UNAUTHORIZED");
});

Deno.test("handleRequest returns UNAUTHORIZED when Bearer JWT malformed", async () => {
    resetState();
    await setEnvFromTestCreds();
    const resp = await handleRequest(
        buildRequest(validBody(), TEST_USER_A, { authHeader: "Bearer garbage" }),
    );
    const json = await resp.json();
    assertEquals(json.code, "UNAUTHORIZED");
});

// ---------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------

Deno.test("handleRequest rejects unknown target_type with VALIDATION_FAILED", async () => {
    resetState();
    await setEnvFromTestCreds();
    const resp = await handleRequest(buildRequest(validBody({ target_type: "screenshot" })));
    const json = await resp.json();
    assertEquals(json.code, "VALIDATION_FAILED");
});

Deno.test("handleRequest rejects non-UUID target_id", async () => {
    resetState();
    await setEnvFromTestCreds();
    const resp = await handleRequest(buildRequest(validBody({ target_id: "not-a-uuid" })));
    const json = await resp.json();
    assertEquals(json.code, "VALIDATION_FAILED");
});

Deno.test("handleRequest rejects malformed JSON body", async () => {
    resetState();
    await setEnvFromTestCreds();
    const resp = await handleRequest(buildRequest("{not json", TEST_USER_A));
    const json = await resp.json();
    assertEquals(json.code, "VALIDATION_FAILED");
});

// ---------------------------------------------------------------------
// Rate limit
// ---------------------------------------------------------------------

Deno.test("handleRequest rate-limits after 10 successful submissions from same user", async () => {
    resetState();
    await setEnvFromTestCreds();
    const fake = installStandardFakes();
    try {
        for (let i = 0; i < 10; i++) {
            const resp = await handleRequest(buildRequest(validBody({ reason: `r${i}` })));
            const json = await resp.json();
            assertEquals(json.ok, true, `submission ${i + 1} should succeed`);
        }
        const eleventh = await handleRequest(buildRequest(validBody({ reason: "r11" })));
        const json = await eleventh.json();
        assertEquals(json.ok, false);
        assertEquals(json.code, "RATE_LIMITED");
        assertStringIncludes(json.message, "minute");
    } finally {
        fake.restore();
    }
});

Deno.test("handleRequest rate limit is per-user (USER_A and USER_B independent)", async () => {
    resetState();
    await setEnvFromTestCreds();
    const fake = installStandardFakes();
    try {
        // Exhaust USER_A
        for (let i = 0; i < 10; i++) {
            await handleRequest(buildRequest(validBody({ reason: `a${i}` }), TEST_USER_A));
        }
        // USER_B is independent
        const resp = await handleRequest(buildRequest(validBody({ reason: "b1" }), TEST_USER_B));
        const json = await resp.json();
        assertEquals(json.ok, true);
    } finally {
        fake.restore();
    }
});

// ---------------------------------------------------------------------
// Happy path + GitHub Issue + UPDATE
// ---------------------------------------------------------------------

Deno.test("handleRequest happy path returns ok with report_id + github_issue_url", async () => {
    resetState();
    await setEnvFromTestCreds();
    const fake = installStandardFakes({
        insertId: "44444444-4444-4444-4444-444444444444",
        issueHtmlUrl: "https://github.com/b150005/skeinly/issues/42",
    });
    try {
        const resp = await handleRequest(buildRequest(validBody()));
        assertEquals(resp.status, 200);
        const json = await resp.json();
        assertEquals(json.ok, true);
        assertEquals(json.report_id, "44444444-4444-4444-4444-444444444444");
        assertEquals(json.github_issue_url, "https://github.com/b150005/skeinly/issues/42");
    } finally {
        fake.restore();
    }
});

Deno.test("handleRequest applies label 'ugc-report' to GitHub Issue", async () => {
    resetState();
    await setEnvFromTestCreds();
    const fake = installStandardFakes();
    try {
        await handleRequest(buildRequest(validBody()));
        const issueCall = fake.calls.find((c) => c.url.includes("/repos/b150005/skeinly/issues"));
        assert(issueCall !== undefined);
        const payload = JSON.parse(issueCall!.body ?? "{}");
        assertEquals(payload.labels, ["ugc-report"]);
    } finally {
        fake.restore();
    }
});

Deno.test("handleRequest INSERTs reporter_id derived from JWT sub claim", async () => {
    resetState();
    await setEnvFromTestCreds();
    const fake = installStandardFakes();
    try {
        await handleRequest(buildRequest(validBody(), TEST_USER_B));
        const insertCall = fake.calls.find(
            (c) => c.url.includes("/rest/v1/ugc_reports") && c.method === "POST",
        );
        assert(insertCall !== undefined);
        const payload = JSON.parse(insertCall!.body ?? "{}");
        assertEquals(payload.reporter_id, TEST_USER_B);
    } finally {
        fake.restore();
    }
});

Deno.test("handleRequest UPDATEs github_issue_url after Issue POST success", async () => {
    resetState();
    await setEnvFromTestCreds();
    const fake = installStandardFakes({
        insertId: "55555555-5555-5555-5555-555555555555",
        issueHtmlUrl: "https://github.com/b150005/skeinly/issues/99",
    });
    try {
        await handleRequest(buildRequest(validBody()));
        const updateCall = fake.calls.find(
            (c) => c.url.includes("/rest/v1/ugc_reports") && c.method === "PATCH",
        );
        assert(updateCall !== undefined, "expected PATCH to ugc_reports for github_issue_url backfill");
        const payload = JSON.parse(updateCall!.body ?? "{}");
        assertEquals(payload.github_issue_url, "https://github.com/b150005/skeinly/issues/99");
    } finally {
        fake.restore();
    }
});

// ---------------------------------------------------------------------
// Failure modes
// ---------------------------------------------------------------------

Deno.test("handleRequest returns DB_INSERT_FAILED when ugc_reports insert errors", async () => {
    resetState();
    await setEnvFromTestCreds();
    const fake = installStandardFakes({ insertStatus: 500 });
    try {
        const resp = await handleRequest(buildRequest(validBody()));
        const json = await resp.json();
        assertEquals(json.ok, false);
        assertEquals(json.code, "DB_INSERT_FAILED");
    } finally {
        fake.restore();
    }
});

Deno.test("handleRequest treats GitHub Issue failure as best-effort: returns ok + github_issue_url=null", async () => {
    resetState();
    await setEnvFromTestCreds();
    const fake = installStandardFakes({
        insertId: "66666666-6666-6666-6666-666666666666",
        issueStatus: 503,
    });
    try {
        const resp = await handleRequest(buildRequest(validBody()));
        const json = await resp.json();
        assertEquals(json.ok, true, "DB row is canonical — request should still succeed");
        assertEquals(json.report_id, "66666666-6666-6666-6666-666666666666");
        assertEquals(json.github_issue_url, null);
        // And we should not have attempted the github_issue_url backfill
        // because the Issue POST failed.
        const updateCall = fake.calls.find(
            (c) => c.url.includes("/rest/v1/ugc_reports") && c.method === "PATCH",
        );
        assertEquals(updateCall, undefined);
    } finally {
        fake.restore();
    }
});
