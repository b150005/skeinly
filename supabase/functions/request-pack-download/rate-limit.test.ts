// Deno tests for the pure helpers in the `request-pack-download`
// Edge Function. Run locally with:
//
//   deno test supabase/functions/request-pack-download/
//
// Not currently CI-gated — these are regression anchors for the rate
// limiter logic that protects against the §10 Q6 abuse path. The
// Kotlin client envelope tests live in Phase 41.2 alongside the
// SymbolPackSyncManager that consumes the API.

import { assertEquals, assertFalse, assert as assertTrue } from "@std/assert";
import {
    checkAndRecordRateLimit,
    RATE_LIMIT_MAX_CALLS,
    RATE_LIMIT_WINDOW_MS,
    rateLimitState,
} from "./rate-limit.ts";

function reset(): void {
    rateLimitState.clear();
}

Deno.test("rate limiter accepts up to RATE_LIMIT_MAX_CALLS within the window", () => {
    reset();
    const t0 = 1_000_000;
    for (let i = 0; i < RATE_LIMIT_MAX_CALLS; i++) {
        assertTrue(
            checkAndRecordRateLimit("user-A", t0 + i),
            `call #${i} expected to be accepted (under cap)`,
        );
    }
});

Deno.test("rate limiter rejects the call that exceeds the cap and persists window", () => {
    reset();
    const t0 = 2_000_000;
    for (let i = 0; i < RATE_LIMIT_MAX_CALLS; i++) {
        checkAndRecordRateLimit("user-B", t0 + i);
    }

    assertFalse(
        checkAndRecordRateLimit("user-B", t0 + RATE_LIMIT_MAX_CALLS),
        "call #11 within the same window must be rejected",
    );

    // The trimmed window is persisted so memory does not grow unbounded
    // even when a hot user keeps probing post-cap.
    const persisted = rateLimitState.get("user-B");
    assertTrue(persisted !== undefined, "rejected call still persists previous-window state");
    assertEquals(
        persisted?.length,
        RATE_LIMIT_MAX_CALLS,
        "rejected call must NOT add itself to the persisted timestamps",
    );
});

Deno.test("rate limiter clears entries that fall outside the sliding window", () => {
    reset();
    const t0 = 3_000_000;
    for (let i = 0; i < RATE_LIMIT_MAX_CALLS; i++) {
        checkAndRecordRateLimit("user-C", t0 + i);
    }

    // After the window passes (set `future` well past the LAST entry's
    // window edge so all 10 prior entries fall off), prior entries are
    // pruned and a fresh call should be accepted.
    const future = t0 + RATE_LIMIT_MAX_CALLS + RATE_LIMIT_WINDOW_MS + 1;
    assertTrue(
        checkAndRecordRateLimit("user-C", future),
        "call after window expires should be accepted",
    );

    // Earlier entries should have been pruned — only the new one remains.
    const persisted = rateLimitState.get("user-C");
    assertEquals(persisted?.length, 1, "all earlier window entries should have been pruned");
});

Deno.test("rate limiter scopes per user_id — saturating user A does not affect user B", () => {
    reset();
    const t0 = 4_000_000;
    for (let i = 0; i < RATE_LIMIT_MAX_CALLS; i++) {
        checkAndRecordRateLimit("user-A", t0 + i);
    }

    // User A is saturated...
    assertFalse(checkAndRecordRateLimit("user-A", t0 + RATE_LIMIT_MAX_CALLS));
    // ...but User B has untouched quota.
    assertTrue(checkAndRecordRateLimit("user-B", t0 + RATE_LIMIT_MAX_CALLS));
});

Deno.test("rate limiter constants match the documented closed-beta scale (10 calls / 60s)", () => {
    // Anchors the documented semantics — drift here breaks ADR-016 §3.3
    // and §10 Q6 simultaneously, surfacing in tests rather than at
    // load time.
    assertEquals(RATE_LIMIT_MAX_CALLS, 10);
    assertEquals(RATE_LIMIT_WINDOW_MS, 60_000);
});
