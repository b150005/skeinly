// Phase 24.1 (ADR-017 §3.7 + §6): Deno tests for the pure helpers in
// mapping.ts. No fetch, no DB, no env vars — runs offline via:
//   deno test supabase/functions/notify-on-write/

import { assert, assertEquals } from "jsr:@std/assert@1";
import {
    ACTOR_FALLBACK_EN,
    ACTOR_FALLBACK_JA,
    type PullRequestCommentRow,
    type PullRequestRow,
    PATTERN_FALLBACK_EN,
    PR_TITLE_FALLBACK_JA,
    TEMPLATES,
    type TemplateKey,
    computePrCommentedDispatches,
    computePrOpenedDispatches,
    computePrStatusChangeDispatches,
    pullRequestRoute,
    renderBody,
} from "./mapping.ts";

// ---------------------------------------------------------------------
// Template parity (ADR-017 §3.7 Trade-off mitigation)
// ---------------------------------------------------------------------

Deno.test("template parity: EN and JA tables share the same key set", () => {
    const enKeys = Object.keys(TEMPLATES["en-US"]).sort();
    const jaKeys = Object.keys(TEMPLATES["ja-JP"]).sort();
    assertEquals(enKeys, jaKeys);
});

Deno.test("template parity: every key produces non-empty body in both locales", () => {
    const sampleParams = { actor: "Alice", pattern: "Granny Square", pr_title: "Add bobble row" };
    for (const key of Object.keys(TEMPLATES["en-US"]) as TemplateKey[]) {
        const en = renderBody("en-US", key, sampleParams);
        const ja = renderBody("ja-JP", key, sampleParams);
        assert(en.length > 0, `EN template ${key} produced empty string`);
        assert(ja.length > 0, `JA template ${key} produced empty string`);
    }
});

// ---------------------------------------------------------------------
// renderBody (locale resolution + parameter substitution)
// ---------------------------------------------------------------------

Deno.test("renderBody: pr_opened in EN substitutes actor + pattern", () => {
    const body = renderBody("en-US", "pr_opened", { actor: "Alice", pattern: "Granny Square" });
    assertEquals(body, "Alice opened a pull request on Granny Square");
});

Deno.test("renderBody: pr_opened in JA inserts さん + に around pattern", () => {
    const body = renderBody("ja-JP", "pr_opened", { actor: "Alice", pattern: "Granny Square" });
    assertEquals(body, "AliceさんがGranny Squareにプルリクエストを開きました");
});

Deno.test("renderBody: pr_commented in EN substitutes actor + pr_title", () => {
    const body = renderBody("en-US", "pr_commented", { actor: "Bob", pr_title: "Fix bobble row" });
    assertEquals(body, "Bob commented on Fix bobble row");
});

Deno.test("renderBody: pr_merged_to_author in EN", () => {
    const body = renderBody("en-US", "pr_merged_to_author", { actor: "Alice", pattern: "Cardigan" });
    assertEquals(body, "Alice merged your pull request on Cardigan");
});

Deno.test("renderBody: pr_closed_to_author in EN", () => {
    const body = renderBody("en-US", "pr_closed_to_author", { actor: "Alice", pattern: "Cardigan" });
    assertEquals(body, "Alice closed your pull request on Cardigan");
});

Deno.test("renderBody: pr_closed_to_owner in EN", () => {
    const body = renderBody("en-US", "pr_closed_to_owner", { actor: "Bob", pattern: "Cardigan" });
    assertEquals(body, "Bob closed their pull request on Cardigan");
});

Deno.test("renderBody: unknown locale falls back to en-US", () => {
    const body = renderBody("fr-FR", "pr_opened", { actor: "Alice", pattern: "Square" });
    // fr-FR has no entry; falls back to EN_TEMPLATES.
    assertEquals(body, "Alice opened a pull request on Square");
});

Deno.test("renderBody: null actor falls back to ACTOR_FALLBACK_EN in EN", () => {
    const body = renderBody("en-US", "pr_opened", { actor: null, pattern: "Square" });
    assertEquals(body, `${ACTOR_FALLBACK_EN} opened a pull request on Square`);
});

Deno.test("renderBody: null actor falls back to ACTOR_FALLBACK_JA in JA", () => {
    const body = renderBody("ja-JP", "pr_opened", { actor: null, pattern: "Square" });
    assert(body.startsWith(ACTOR_FALLBACK_JA));
});

Deno.test("renderBody: null pattern falls back to PATTERN_FALLBACK_EN in EN", () => {
    const body = renderBody("en-US", "pr_opened", { actor: "Alice", pattern: null });
    assertEquals(body, `Alice opened a pull request on ${PATTERN_FALLBACK_EN}`);
});

Deno.test("renderBody: null pr_title falls back to PR_TITLE_FALLBACK in JA", () => {
    const body = renderBody("ja-JP", "pr_commented", { actor: "Alice", pr_title: null });
    assert(body.includes(PR_TITLE_FALLBACK_JA));
});

// ---------------------------------------------------------------------
// computePrOpenedDispatches
// ---------------------------------------------------------------------

const samplePr: PullRequestRow = {
    id: "pr-1",
    author_id: "alice-uuid",
    target_pattern_id: "pattern-1",
    source_pattern_id: "pattern-2",
    status: "open",
};

Deno.test("computePrOpenedDispatches: notifies target owner with pr_opened template", () => {
    const dispatches = computePrOpenedDispatches(samplePr, "bob-uuid", "Alice", "Granny Square");
    assertEquals(dispatches, [{
        recipientUserId: "bob-uuid",
        templateKey: "pr_opened",
        params: { actor: "Alice", pattern: "Granny Square" },
        route: "pull-request/pr-1",
    }]);
});

Deno.test("computePrOpenedDispatches: skips when author == target owner (self-PR)", () => {
    const selfPr = { ...samplePr, author_id: "alice-uuid" };
    const dispatches = computePrOpenedDispatches(selfPr, "alice-uuid", "Alice", "Square");
    assertEquals(dispatches, []);
});

Deno.test("computePrOpenedDispatches: passes null params through to template", () => {
    const dispatches = computePrOpenedDispatches(samplePr, "bob-uuid", null, null);
    assertEquals(dispatches[0].params.actor, null);
    assertEquals(dispatches[0].params.pattern, null);
});

// ---------------------------------------------------------------------
// computePrCommentedDispatches
// ---------------------------------------------------------------------

const sampleComment: PullRequestCommentRow = {
    id: "c-1",
    pull_request_id: "pr-1",
    author_id: "carol-uuid",
    body: "Looks good!",
};

Deno.test("computePrCommentedDispatches: notifies both pr.author + target owner when comment author is third party", () => {
    const dispatches = computePrCommentedDispatches(
        sampleComment,
        "alice-uuid", // pr.author_id
        "bob-uuid",   // target owner
        "Carol",
        "Add bobble",
    );
    const recipients = dispatches.map((d) => d.recipientUserId).sort();
    assertEquals(recipients, ["alice-uuid", "bob-uuid"]);
    for (const dispatch of dispatches) {
        assertEquals(dispatch.templateKey, "pr_commented");
        assertEquals(dispatch.params.actor, "Carol");
        assertEquals(dispatch.params.pr_title, "Add bobble");
    }
});

Deno.test("computePrCommentedDispatches: excludes comment author from recipients", () => {
    // Alice (PR author) commented on her own PR. Only Bob (target owner) should be notified.
    const aliceComment = { ...sampleComment, author_id: "alice-uuid" };
    const dispatches = computePrCommentedDispatches(
        aliceComment,
        "alice-uuid",
        "bob-uuid",
        "Alice",
        "Add bobble",
    );
    assertEquals(dispatches.length, 1);
    assertEquals(dispatches[0].recipientUserId, "bob-uuid");
});

Deno.test("computePrCommentedDispatches: excludes comment author when author == target owner", () => {
    // Bob (target owner) commented on an incoming PR. Only Alice (PR author) should be notified.
    const bobComment = { ...sampleComment, author_id: "bob-uuid" };
    const dispatches = computePrCommentedDispatches(
        bobComment,
        "alice-uuid",
        "bob-uuid",
        "Bob",
        "Add bobble",
    );
    assertEquals(dispatches.length, 1);
    assertEquals(dispatches[0].recipientUserId, "alice-uuid");
});

Deno.test("computePrCommentedDispatches: handles null pr.author_id (account deleted)", () => {
    const dispatches = computePrCommentedDispatches(
        sampleComment,
        null, // pr.author_id was set to NULL via ON DELETE SET NULL
        "bob-uuid",
        "Carol",
        "Add bobble",
    );
    // Only target owner remains as recipient.
    assertEquals(dispatches.length, 1);
    assertEquals(dispatches[0].recipientUserId, "bob-uuid");
});

Deno.test("computePrCommentedDispatches: deduplicates when pr.author == target owner (edge case)", () => {
    // Should not happen given v1 fork-routing invariant (PR target is
    // source's parentPattern, so author and target owner are by definition
    // different users), but defense-in-depth.
    const dispatches = computePrCommentedDispatches(
        sampleComment,
        "alice-uuid",
        "alice-uuid", // same as author
        "Carol",
        "Add bobble",
    );
    assertEquals(dispatches.length, 1);
    assertEquals(dispatches[0].recipientUserId, "alice-uuid");
});

// ---------------------------------------------------------------------
// computePrStatusChangeDispatches
// ---------------------------------------------------------------------

Deno.test("computePrStatusChangeDispatches: open → merged notifies author with pr_merged_to_author", () => {
    const oldRow: PullRequestRow = { ...samplePr, status: "open" };
    const newRow: PullRequestRow = { ...samplePr, status: "applied" };
    const dispatches = computePrStatusChangeDispatches(
        newRow,
        oldRow,
        "bob-uuid", // actor (target owner per ADR-014 §5)
        "bob-uuid",
        "Bob",
        "Granny Square",
    );
    assertEquals(dispatches, [{
        recipientUserId: "alice-uuid",
        templateKey: "pr_merged_to_author",
        params: { actor: "Bob", pattern: "Granny Square" },
        route: "pull-request/pr-1",
    }]);
});

Deno.test("computePrStatusChangeDispatches: open → closed by author notifies target owner", () => {
    const oldRow: PullRequestRow = { ...samplePr, status: "open" };
    const newRow: PullRequestRow = { ...samplePr, status: "closed" };
    const dispatches = computePrStatusChangeDispatches(
        newRow,
        oldRow,
        "alice-uuid", // actor == author
        "bob-uuid",
        "Alice",
        "Granny Square",
    );
    assertEquals(dispatches, [{
        recipientUserId: "bob-uuid",
        templateKey: "pr_closed_to_owner",
        params: { actor: "Alice", pattern: "Granny Square" },
        route: "pull-request/pr-1",
    }]);
});

Deno.test("computePrStatusChangeDispatches: open → closed by target owner notifies author", () => {
    const oldRow: PullRequestRow = { ...samplePr, status: "open" };
    const newRow: PullRequestRow = { ...samplePr, status: "closed" };
    const dispatches = computePrStatusChangeDispatches(
        newRow,
        oldRow,
        "bob-uuid", // actor == target owner
        "bob-uuid",
        "Bob",
        "Granny Square",
    );
    assertEquals(dispatches, [{
        recipientUserId: "alice-uuid",
        templateKey: "pr_closed_to_author",
        params: { actor: "Bob", pattern: "Granny Square" },
        route: "pull-request/pr-1",
    }]);
});

Deno.test("computePrStatusChangeDispatches: silent no-op when oldRow.status was not 'open'", () => {
    // E.g. a status flip from 'merged' to something else (defensive
    // edge case — should not happen given the WITH CHECK on
    // pull_requests UPDATE policy + the merge_pull_request RPC's
    // FOR UPDATE lock, but if it does, no push).
    const oldRow: PullRequestRow = { ...samplePr, status: "applied" };
    const newRow: PullRequestRow = { ...samplePr, status: "closed" };
    const dispatches = computePrStatusChangeDispatches(
        newRow,
        oldRow,
        "bob-uuid",
        "bob-uuid",
        "Bob",
        "Granny Square",
    );
    assertEquals(dispatches, []);
});

Deno.test("computePrStatusChangeDispatches: silent no-op for non-status UPDATE", () => {
    // updated_at touch with no actual status change.
    const oldRow: PullRequestRow = { ...samplePr, status: "open" };
    const newRow: PullRequestRow = { ...samplePr, status: "open" };
    const dispatches = computePrStatusChangeDispatches(
        newRow,
        oldRow,
        "alice-uuid",
        "bob-uuid",
        "Alice",
        "Granny Square",
    );
    assertEquals(dispatches, []);
});

Deno.test("computePrStatusChangeDispatches: silent no-op when oldRow is null", () => {
    const newRow: PullRequestRow = { ...samplePr, status: "applied" };
    const dispatches = computePrStatusChangeDispatches(
        newRow,
        null,
        "bob-uuid",
        "bob-uuid",
        "Bob",
        "Granny Square",
    );
    assertEquals(dispatches, []);
});

Deno.test("computePrStatusChangeDispatches: skips merge when author_id is null (account deleted)", () => {
    const oldRow: PullRequestRow = { ...samplePr, status: "open", author_id: null };
    const newRow: PullRequestRow = { ...samplePr, status: "applied", author_id: null };
    const dispatches = computePrStatusChangeDispatches(
        newRow,
        oldRow,
        "bob-uuid",
        "bob-uuid",
        "Bob",
        "Granny Square",
    );
    assertEquals(dispatches, []);
});

Deno.test("computePrStatusChangeDispatches: ignores actor neither author nor target owner", () => {
    // Should not happen given RLS, but defense-in-depth.
    const oldRow: PullRequestRow = { ...samplePr, status: "open" };
    const newRow: PullRequestRow = { ...samplePr, status: "closed" };
    const dispatches = computePrStatusChangeDispatches(
        newRow,
        oldRow,
        "carol-uuid", // some random third party
        "bob-uuid",
        "Carol",
        "Granny Square",
    );
    assertEquals(dispatches, []);
});

// ---------------------------------------------------------------------
// Phase 24.5 — deep-link route construction
// ---------------------------------------------------------------------

Deno.test("pullRequestRoute: produces host-relative pull-request/<prId>", () => {
    assertEquals(pullRequestRoute("abc-123"), "pull-request/abc-123");
});

Deno.test("computePrCommentedDispatches: route is keyed off comment.pull_request_id, NOT comment.id", () => {
    // Defensive against a future refactor that confuses the two id
    // columns — the route MUST point at the parent PR, not the comment.
    const comment: PullRequestCommentRow = {
        id: "comment-7",
        pull_request_id: "pr-99",
        author_id: "carol-uuid",
        body: "lgtm",
    };
    const dispatches = computePrCommentedDispatches(
        comment,
        "alice-uuid",
        "bob-uuid",
        "Carol",
        "Granny Square",
    );
    for (const d of dispatches) {
        assertEquals(d.route, "pull-request/pr-99");
    }
});

Deno.test("computePrStatusChangeDispatches: route uses row.id (the PR id) on merge", () => {
    const row: PullRequestRow = {
        id: "pr-merged-42",
        author_id: "alice-uuid",
        target_pattern_id: "pattern-1",
        status: "applied",
    };
    const oldRow: PullRequestRow = { ...row, status: "open" };
    const dispatches = computePrStatusChangeDispatches(
        row,
        oldRow,
        "bob-uuid",
        "bob-uuid",
        "Bob",
        "Granny Square",
    );
    assertEquals(dispatches.length, 1);
    assertEquals(dispatches[0].route, "pull-request/pr-merged-42");
});
