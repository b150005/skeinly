// Pure sliding-window rate limiter for `request-pack-download`
// (ADR-016 §3.3 + §10 Q6). Extracted from `index.ts` so the test
// file can import the helper without triggering the top-level
// `Deno.serve(...)` port-bind.
//
// Per-Edge-Function-instance state. Cold starts reset the limiter
// (acceptable for v1 closed-beta scale; revisit with Upstash Redis
// or a Postgres `edge_function_rate_limit` table once subscriber
// count justifies). Tests reset `rateLimitState` between cases.

export const RATE_LIMIT_WINDOW_MS = 60_000;
export const RATE_LIMIT_MAX_CALLS = 10;

export const rateLimitState = new Map<string, number[]>();

/**
 * Returns true if this call is allowed; false if the user has hit the
 * cap. The trimmed window is persisted on rejection so memory does not
 * grow unbounded when a hot user keeps probing post-cap.
 */
export function checkAndRecordRateLimit(userId: string, now: number): boolean {
    const cutoff = now - RATE_LIMIT_WINDOW_MS;
    const previous = rateLimitState.get(userId) ?? [];
    const inWindow = previous.filter((ts) => ts > cutoff);

    if (inWindow.length >= RATE_LIMIT_MAX_CALLS) {
        rateLimitState.set(userId, inWindow);
        return false;
    }

    inWindow.push(now);
    rateLimitState.set(userId, inWindow);
    return true;
}
