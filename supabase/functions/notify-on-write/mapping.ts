// Phase 24.1 (ADR-017 §3.4 + §3.7): pure helpers for translating Database
// Webhook payloads from `pull_requests` / `pull_request_comments` table
// writes into recipient sets + localized notification bodies.
//
// Pure functions only — no fetch / no DB access / no env var reads. Deno
// tests in `mapping.test.ts` exercise the full matrix without spinning
// up Supabase.

// ---------------------------------------------------------------------
// Database Webhook payload shape
// ---------------------------------------------------------------------

/**
 * Subset of the Supabase Database Webhook payload shape we consume.
 * Reference: https://supabase.com/docs/guides/database/webhooks
 *
 * The full payload also carries `schema` ("public") which we don't
 * branch on (Phase 24.1 only consumes `public.*` tables).
 */
export interface WebhookPayload<TRow = Record<string, unknown>> {
    type: "INSERT" | "UPDATE" | "DELETE";
    table: string;
    schema?: string;
    record: TRow;
    old_record?: TRow | null;
}

/** Minimal projection of `public.pull_requests` row that the recipient
 * computation depends on. The full row carries 12+ columns; we only
 * read the 5 fields below. */
export interface PullRequestRow {
    id: string;
    author_id: string | null;
    target_pattern_id: string;
    source_pattern_id?: string;
    status: "open" | "merged" | "closed";
}

/** Minimal projection of `public.pull_request_comments` row. */
export interface PullRequestCommentRow {
    id: string;
    pull_request_id: string;
    author_id: string | null;
    body: string;
}

// ---------------------------------------------------------------------
// Notification template registry (per ADR-017 §3.7)
// ---------------------------------------------------------------------

/**
 * Closed enum of localized notification template keys. New event types
 * MUST add a key here AND a corresponding entry to both `EN_TEMPLATES`
 * and `JA_TEMPLATES` below; the `assertTemplateParity` test in
 * `mapping.test.ts` enforces the parity at CI time.
 */
export type TemplateKey =
    | "pr_opened"
    | "pr_commented"
    | "pr_merged_to_author"
    | "pr_closed_to_author"
    | "pr_closed_to_owner";

/** Display-name fallback string when an actor's name is null (e.g.
 * the row carries `author_id` but the join to `users` returns no
 * row, or the user explicitly cleared their display_name). */
export const ACTOR_FALLBACK_EN = "Someone";
export const ACTOR_FALLBACK_JA = "誰か";

/** Pattern-title fallback for the same reason. */
export const PATTERN_FALLBACK_EN = "a pattern";
export const PATTERN_FALLBACK_JA = "パターン";

/** PR-title fallback. */
export const PR_TITLE_FALLBACK_EN = "a pull request";
export const PR_TITLE_FALLBACK_JA = "プルリクエスト";

const EN_TEMPLATES: Record<TemplateKey, (params: TemplateParams) => string> = {
    pr_opened: (p) =>
        `${p.actor ?? ACTOR_FALLBACK_EN} opened a pull request on ${p.pattern ?? PATTERN_FALLBACK_EN}`,
    pr_commented: (p) =>
        `${p.actor ?? ACTOR_FALLBACK_EN} commented on ${p.pr_title ?? PR_TITLE_FALLBACK_EN}`,
    pr_merged_to_author: (p) =>
        `${p.actor ?? ACTOR_FALLBACK_EN} merged your pull request on ${p.pattern ?? PATTERN_FALLBACK_EN}`,
    pr_closed_to_author: (p) =>
        `${p.actor ?? ACTOR_FALLBACK_EN} closed your pull request on ${p.pattern ?? PATTERN_FALLBACK_EN}`,
    pr_closed_to_owner: (p) =>
        `${p.actor ?? ACTOR_FALLBACK_EN} closed their pull request on ${p.pattern ?? PATTERN_FALLBACK_EN}`,
};

const JA_TEMPLATES: Record<TemplateKey, (params: TemplateParams) => string> = {
    pr_opened: (p) =>
        `${p.actor ?? ACTOR_FALLBACK_JA}さんが${p.pattern ?? PATTERN_FALLBACK_JA}にプルリクエストを開きました`,
    pr_commented: (p) =>
        `${p.actor ?? ACTOR_FALLBACK_JA}さんが${p.pr_title ?? PR_TITLE_FALLBACK_JA}にコメントしました`,
    pr_merged_to_author: (p) =>
        `${p.actor ?? ACTOR_FALLBACK_JA}さんがあなたの${p.pattern ?? PATTERN_FALLBACK_JA}へのプルリクエストをマージしました`,
    pr_closed_to_author: (p) =>
        `${p.actor ?? ACTOR_FALLBACK_JA}さんがあなたの${p.pattern ?? PATTERN_FALLBACK_JA}へのプルリクエストをクローズしました`,
    pr_closed_to_owner: (p) =>
        `${p.actor ?? ACTOR_FALLBACK_JA}さんが${p.pattern ?? PATTERN_FALLBACK_JA}へのプルリクエストをクローズしました`,
};

/** Locale-keyed registry. Keep parity between EN + JA tables — enforced
 * by the `assertTemplateParity` test. */
export const TEMPLATES = {
    "en-US": EN_TEMPLATES,
    "ja-JP": JA_TEMPLATES,
} as const;

/** Closed enum of supported BCP-47 locales mirroring the
 * `device_tokens.locale` CHECK constraint. */
export type SupportedLocale = keyof typeof TEMPLATES;

export interface TemplateParams {
    actor?: string | null;
    pattern?: string | null;
    pr_title?: string | null;
}

/**
 * Render a localized notification body. Unknown locale → falls back to
 * `en-US`. Unknown template key cannot occur thanks to the
 * `TemplateKey` type union.
 */
export function renderBody(
    locale: string,
    key: TemplateKey,
    params: TemplateParams,
): string {
    const table = (TEMPLATES as Record<string, typeof EN_TEMPLATES>)[locale]
        ?? TEMPLATES["en-US"];
    return table[key](params);
}

// ---------------------------------------------------------------------
// Recipient computation (per ADR-017 §3.4)
// ---------------------------------------------------------------------

/**
 * Per-recipient dispatch instruction. Phase 24.1 ships log-only;
 * Phase 24.3 wires the actual APNs / FCM call paths;
 * Phase 24.5 adds [route] for tap-to-navigate deep linking.
 *
 * `route` follows the host-relative scheme `pull-request/<prId>`. All
 * Phase 24 events are PR-scoped so the route family is uniform; future
 * non-PR events (Phase 24+) can extend the scheme (`pattern/<patternId>`,
 * `share/<shareId>` etc.) without restructuring this interface.
 */
export interface NotificationDispatch {
    recipientUserId: string;
    templateKey: TemplateKey;
    params: TemplateParams;
    route: string;
}

/**
 * Phase 24.5 — build a deep-link route string for a PR-scoped event.
 * All MVP events route to PR detail; future event sources extend the
 * scheme.
 */
export function pullRequestRoute(prId: string): string {
    return `pull-request/${prId}`;
}

/**
 * Compute dispatches for a `pull_requests` INSERT — target owner
 * receives `pr_opened`. The actor (author) is intentionally excluded;
 * a user does not get pushed for their own action.
 *
 * @param row the inserted PR row
 * @param targetOwnerId the resolved target_pattern.owner_id (caller resolves via JOIN)
 * @param actorDisplayName author's display_name (caller resolves via JOIN — null if missing)
 * @param patternTitle target_pattern.title (caller resolves via JOIN — null if missing)
 */
export function computePrOpenedDispatches(
    row: PullRequestRow,
    targetOwnerId: string,
    actorDisplayName: string | null,
    patternTitle: string | null,
): NotificationDispatch[] {
    if (row.author_id === targetOwnerId) {
        // Self-PR (author opened a PR against their own pattern). Should
        // not normally happen given the v1 fork-routing invariant, but
        // defense-in-depth: don't notify a user about their own action.
        return [];
    }
    return [{
        recipientUserId: targetOwnerId,
        templateKey: "pr_opened",
        params: { actor: actorDisplayName, pattern: patternTitle },
        route: pullRequestRoute(row.id),
    }];
}

/**
 * Compute dispatches for a `pull_request_comments` INSERT — every PR
 * participant EXCEPT the comment author receives `pr_commented`.
 * Participant set = { pr.author_id, pr.target_owner_id }.
 *
 * @param comment the inserted comment row
 * @param prAuthorId pr.author_id (joined upstream)
 * @param targetOwnerId pr.target_pattern.owner_id (joined upstream)
 * @param actorDisplayName comment author's display_name
 * @param prTitle pr.title (joined upstream)
 */
export function computePrCommentedDispatches(
    comment: PullRequestCommentRow,
    prAuthorId: string | null,
    targetOwnerId: string,
    actorDisplayName: string | null,
    prTitle: string | null,
): NotificationDispatch[] {
    const recipients = new Set<string>();
    if (prAuthorId !== null) recipients.add(prAuthorId);
    recipients.add(targetOwnerId);
    if (comment.author_id !== null) recipients.delete(comment.author_id);

    const route = pullRequestRoute(comment.pull_request_id);
    return Array.from(recipients).map((recipientUserId) => ({
        recipientUserId,
        templateKey: "pr_commented" as const,
        params: { actor: actorDisplayName, pr_title: prTitle },
        route,
    }));
}

/**
 * Compute dispatches for a `pull_requests` UPDATE where status flipped
 * from 'open' → 'merged' or 'closed'. Branches on actor vs participant
 * to pick the correct template.
 *
 * @param row the updated PR row (post-update state)
 * @param oldRow the pre-update PR row (we read old_record.status)
 * @param actorUserId who issued the merge/close (for merged: target_owner; for closed: either party)
 * @param targetOwnerId pr.target_pattern.owner_id
 * @param actorDisplayName the actor's display_name
 * @param patternTitle pr.target_pattern.title
 */
export function computePrStatusChangeDispatches(
    row: PullRequestRow,
    oldRow: PullRequestRow | null,
    actorUserId: string | null,
    targetOwnerId: string,
    actorDisplayName: string | null,
    patternTitle: string | null,
): NotificationDispatch[] {
    // Only fire on the open → terminal transition. Other UPDATEs (e.g.
    // updated_at touch, future status flips like draft → open) silently
    // no-op.
    if (oldRow?.status !== "open") return [];
    if (row.status !== "merged" && row.status !== "closed") return [];

    const route = pullRequestRoute(row.id);
    if (row.status === "merged") {
        // Per ADR-014 §5: merge is performed by the target owner only.
        // Notify the PR author. Defense-in-depth: even if actorUserId is
        // somehow the author (would indicate RPC bypass), still notify.
        if (row.author_id === null) return [];
        if (row.author_id === actorUserId) return []; // never push self
        return [{
            recipientUserId: row.author_id,
            templateKey: "pr_merged_to_author",
            params: { actor: actorDisplayName, pattern: patternTitle },
            route,
        }];
    }

    // status === "closed": either party may close (ADR-014 §1).
    // Branch on actor identity.
    if (actorUserId === row.author_id) {
        // Author closed their own PR; notify target owner.
        if (targetOwnerId === actorUserId) return []; // never push self
        return [{
            recipientUserId: targetOwnerId,
            templateKey: "pr_closed_to_owner",
            params: { actor: actorDisplayName, pattern: patternTitle },
            route,
        }];
    }
    if (actorUserId === targetOwnerId) {
        // Target owner closed an incoming PR; notify the author.
        if (row.author_id === null) return [];
        if (row.author_id === actorUserId) return [];
        return [{
            recipientUserId: row.author_id,
            templateKey: "pr_closed_to_author",
            params: { actor: actorDisplayName, pattern: patternTitle },
            route,
        }];
    }

    // Actor is neither author nor target owner. Should not happen given
    // RLS UPDATE policy on pull_requests, but defense-in-depth: no push.
    return [];
}
