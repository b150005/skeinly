// Phase 24.3 (ADR-018 §3.6): integration tests for the end-to-end
// dispatch path. Exercises the cross-section of resolveTokens →
// sendOne (apns/fcm) → processOutcome → DELETE-on-token-cleanup with
// fake Supabase + fake fetch.

import { assertEquals } from "jsr:@std/assert@^1";
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

import { dispatchAll } from "./index.ts";
import type { NotificationDispatch } from "./mapping.ts";
import { _resetApnsJwtCacheForTests } from "./apns.ts";
import { _resetFcmAccessTokenCacheForTests } from "./fcm.ts";
import {
    createFetchFake,
    generateTestApnsCredentials,
    generateTestServiceAccount,
} from "./_fakes.ts";

// ---------------------------------------------------------------------
// Fake Supabase client
// ---------------------------------------------------------------------

interface FakeDeviceTokenRow {
    id: string;
    user_id: string;
    platform: "ios" | "android";
    token: string;
    locale: string;
}

interface FakeSupabaseHandle {
    client: SupabaseClient;
    deletedTokens: string[];
}

function makeFakeSupabase(seed: FakeDeviceTokenRow[]): FakeSupabaseHandle {
    const rows = [...seed];
    const deletedTokens: string[] = [];

    const tableHandle = {
        select(_cols: string) {
            return {
                eq(_col: string, val: string) {
                    return Promise.resolve({
                        data: rows.filter((r) => r.user_id === val),
                        error: null,
                    });
                },
            };
        },
        delete() {
            return {
                eq(col: string, val: string) {
                    if (col !== "token") {
                        return Promise.resolve({
                            data: null,
                            error: { message: `unexpected delete column: ${col}` },
                        });
                    }
                    const idx = rows.findIndex((r) => r.token === val);
                    if (idx >= 0) rows.splice(idx, 1);
                    deletedTokens.push(val);
                    return Promise.resolve({ data: null, error: null });
                },
            };
        },
    };

    const fake = {
        from(table: string) {
            if (table !== "device_tokens") {
                throw new Error(`fake supabase: unexpected table: ${table}`);
            }
            return tableHandle;
        },
    };

    return {
        client: fake as unknown as SupabaseClient,
        deletedTokens,
    };
}

// ---------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------

Deno.test("dispatchAll: single recipient, single iOS token, success", async () => {
    _resetApnsJwtCacheForTests();
    _resetFcmAccessTokenCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        const apnsCreds = await generateTestApnsCredentials();
        const fcmSa = await generateTestServiceAccount();
        const supa = makeFakeSupabase([{
            id: "row-1",
            user_id: "user-A",
            platform: "ios",
            token: "ios-tok",
            locale: "en-US",
        }]);
        fake.setApnsResponse("ios-tok", { status: 200 });
        const dispatches: NotificationDispatch[] = [{
            recipientUserId: "user-A",
            templateKey: "pr_commented",
            params: { actor: "Alice", pr_title: "Sweater PR" },
            route: "pull-request/pr-A",
        }];
        const stats = await dispatchAll(supa.client, dispatches, apnsCreds, fcmSa);
        assertEquals(stats.success, 1);
        assertEquals(stats.delete_token, 0);
        assertEquals(stats.transient_error, 0);
        assertEquals(supa.deletedTokens.length, 0);
    } finally {
        fake.restore();
    }
});

Deno.test("dispatchAll: single recipient, two tokens, one bad → DELETE only the bad one", async () => {
    _resetApnsJwtCacheForTests();
    _resetFcmAccessTokenCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        const apnsCreds = await generateTestApnsCredentials();
        const fcmSa = await generateTestServiceAccount();
        const supa = makeFakeSupabase([
            { id: "row-good", user_id: "user-B", platform: "ios", token: "good-tok", locale: "en-US" },
            { id: "row-bad", user_id: "user-B", platform: "ios", token: "bad-tok", locale: "ja-JP" },
        ]);
        fake.setApnsResponse("good-tok", { status: 200 });
        fake.setApnsResponse("bad-tok", { status: 410, reason: "Unregistered" });
        const dispatches: NotificationDispatch[] = [{
            recipientUserId: "user-B",
            templateKey: "pr_opened",
            params: { actor: "Bob", pattern: "Hat" },
            route: "pull-request/pr-B",
        }];
        const stats = await dispatchAll(supa.client, dispatches, apnsCreds, fcmSa);
        assertEquals(stats.success, 1);
        assertEquals(stats.delete_token, 1);
        assertEquals(supa.deletedTokens, ["bad-tok"]);
    } finally {
        fake.restore();
    }
});

Deno.test("dispatchAll: two recipients fan-out", async () => {
    _resetApnsJwtCacheForTests();
    _resetFcmAccessTokenCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        const apnsCreds = await generateTestApnsCredentials();
        const fcmSa = await generateTestServiceAccount();
        const supa = makeFakeSupabase([
            { id: "r1", user_id: "alice", platform: "ios", token: "alice-ios", locale: "en-US" },
            { id: "r2", user_id: "bob", platform: "android", token: "bob-droid", locale: "ja-JP" },
        ]);
        fake.setApnsResponse("alice-ios", { status: 200 });
        fake.setFcmResponse("bob-droid", { status: 200 });
        const dispatches: NotificationDispatch[] = [
            {
                recipientUserId: "alice",
                templateKey: "pr_commented",
                params: { actor: "Carol", pr_title: "PR-1" },
                route: "pull-request/pr-1",
            },
            {
                recipientUserId: "bob",
                templateKey: "pr_commented",
                params: { actor: "Carol", pr_title: "PR-1" },
                route: "pull-request/pr-1",
            },
        ];
        const stats = await dispatchAll(supa.client, dispatches, apnsCreds, fcmSa);
        assertEquals(stats.success, 2);
        // OAuth fetched once across both Android pushes — verifies cache.
        assertEquals(fake.oauthCallCount(), 1);
    } finally {
        fake.restore();
    }
});

Deno.test("dispatchAll: missing APNs creds → config_error stats, no DELETE, no fetch", async () => {
    _resetApnsJwtCacheForTests();
    _resetFcmAccessTokenCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        const fcmSa = await generateTestServiceAccount();
        const supa = makeFakeSupabase([
            { id: "r1", user_id: "user-X", platform: "ios", token: "ios-tok", locale: "en-US" },
        ]);
        const dispatches: NotificationDispatch[] = [{
            recipientUserId: "user-X",
            templateKey: "pr_opened",
            params: { actor: "Alice", pattern: "Hat" },
            route: "pull-request/pr-X",
        }];
        const stats = await dispatchAll(supa.client, dispatches, null, fcmSa);
        assertEquals(stats.skipped_no_creds, 1);
        assertEquals(stats.success, 0);
        assertEquals(supa.deletedTokens.length, 0);
        // No HTTP traffic should have left the function.
        assertEquals(fake.snapshotRequests().length, 0);
    } finally {
        fake.restore();
    }
});

Deno.test("dispatchAll: recipient with zero tokens skips silently", async () => {
    _resetApnsJwtCacheForTests();
    _resetFcmAccessTokenCacheForTests();
    const fake = createFetchFake();
    fake.install();
    try {
        const apnsCreds = await generateTestApnsCredentials();
        const fcmSa = await generateTestServiceAccount();
        const supa = makeFakeSupabase([
            { id: "r1", user_id: "alice", platform: "ios", token: "alice-tok", locale: "en-US" },
        ]);
        fake.setApnsResponse("alice-tok", { status: 200 });
        const dispatches: NotificationDispatch[] = [
            // alice has tokens
            {
                recipientUserId: "alice",
                templateKey: "pr_commented",
                params: { actor: "Carol", pr_title: "PR-2" },
                route: "pull-request/pr-2",
            },
            // ghost has no rows in device_tokens
            {
                recipientUserId: "ghost",
                templateKey: "pr_commented",
                params: { actor: "Carol", pr_title: "PR-2" },
                route: "pull-request/pr-2",
            },
        ];
        const stats = await dispatchAll(supa.client, dispatches, apnsCreds, fcmSa);
        assertEquals(stats.success, 1);
        assertEquals(stats.skipped_no_creds, 0);
        assertEquals(stats.transient_error, 0);
    } finally {
        fake.restore();
    }
});
