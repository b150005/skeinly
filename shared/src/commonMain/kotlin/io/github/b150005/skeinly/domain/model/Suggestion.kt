package io.github.b150005.skeinly.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Lifecycle of a [PullRequest] (ADR-014 §1, §3).
 *
 * Closed enum — three values, no DRAFT for v1.
 *
 * - [OPEN]: actionable. Both parties can comment; target owner can merge or
 *   close; source author can close.
 * - [MERGED]: terminal. Reached only via the SECURITY DEFINER
 *   `merge_pull_request` RPC — never via a client-side UPDATE. The PR row's
 *   [PullRequest.mergedRevisionId] points at the resulting commit.
 * - [CLOSED]: terminal. Reached when either party closes the PR without
 *   merging. Comments survive but no further state transitions are valid.
 *
 * Wire format uses lowercase string per the Postgres CHECK constraint in
 * migration 016 (`status IN ('open', 'merged', 'closed')`).
 */
@Serializable
enum class PullRequestStatus {
    @SerialName("open")
    OPEN,

    @SerialName("merged")
    MERGED,

    @SerialName("closed")
    CLOSED,
}

/**
 * One pull request between a forked pattern and its upstream (ADR-014 §1, §3).
 *
 * **Routing.** Phase 38 v1 routes PRs only fork → upstream — [targetPatternId]
 * is always the source's [Pattern.parentPatternId] (ADR-012 §1). Internal
 * cross-branch PRs within the same pattern and arbitrary cross-fork routing
 * are out of scope.
 *
 * **Authorship.** [authorId] is the source pattern's owner at PR-open time.
 * Nullable to mirror the Postgres `ON DELETE SET NULL` FK to `profiles` —
 * PR rows survive author account deletion the same way revisions do
 * (ADR-013 §1). Local writes always populate it; null only appears after a
 * remote-side cascade.
 *
 * **Snapshots.** [sourceTipRevisionId] is the source branch tip at PR-open;
 * [commonAncestorRevisionId] is the most recent revision present in BOTH
 * histories (the source's `parent_revision_id` chain walked back to a row
 * still present in the target's history). Both are captured client-side at
 * PR-open and stored as immutable snapshots so the diff is reproducible
 * even if the source tip drifts forward while the PR is open. The merge RPC
 * re-validates [sourceTipRevisionId] against the live source branch tip and
 * raises if it has drifted (resolver re-runs).
 *
 * **No `target_tip_revision_id` snapshot.** The target tip moves under the
 * PR as the target owner commits to their own branch; the merge RPC always
 * uses the *current* target tip as the merge base.
 *
 * **Merge result.** [mergedRevisionId] / [mergedAt] are populated by the
 * RPC when status flips to [PullRequestStatus.MERGED]. NULL on OPEN and
 * CLOSED PRs.
 */
@Serializable
data class PullRequest(
    val id: String,
    @SerialName("source_pattern_id") val sourcePatternId: String,
    @SerialName("source_branch_id") val sourceBranchId: String,
    @SerialName("source_tip_revision_id") val sourceTipRevisionId: String,
    @SerialName("target_pattern_id") val targetPatternId: String,
    @SerialName("target_branch_id") val targetBranchId: String,
    @SerialName("common_ancestor_revision_id") val commonAncestorRevisionId: String,
    @SerialName("author_id") val authorId: String?,
    val title: String,
    val description: String?,
    val status: PullRequestStatus,
    @SerialName("merged_revision_id") val mergedRevisionId: String?,
    @SerialName("merged_at") val mergedAt: Instant?,
    @SerialName("closed_at") val closedAt: Instant?,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
) {
    /**
     * Gates the merge button on [PullRequestDetailScreen] (Phase 38.3).
     *
     * Phase 38.1 ships this as a domain-side helper so the ViewModel doesn't
     * need to learn the [targetPatternId] → owner lookup. The merge RPC
     * re-validates server-side regardless.
     *
     * **Caller contract**: [targetOwnerId] must be resolved from
     * [targetPatternId] by the consumer (e.g. via `PatternRepository.getById`).
     * This helper trusts the caller to supply a correct value — passing the
     * wrong owner id silently flips the gate and exposes a merge button to
     * a non-owner. The RPC's `WHERE owner_id = v_caller` check is the actual
     * security boundary; this method is UI-affordance gating only.
     */
    fun canMerge(
        currentUserId: String,
        targetOwnerId: String,
    ): Boolean = status == PullRequestStatus.OPEN && currentUserId == targetOwnerId
}

/**
 * One flat append-only comment on a pull request (ADR-014 §1, §3).
 *
 * **Append-only.** RLS forbids UPDATE / DELETE — comments are immutable
 * once posted, mirroring [ChartRevision]'s history-immutability invariant
 * (ADR-013 §1). A typo-laden comment is permanent until the PR is closed
 * (closure preserves the comment chain). Soft-delete is post-v1.
 *
 * **Authorship.** [authorId] nullable for the same reason as
 * [PullRequest.authorId] — the FK to `profiles` is `ON DELETE SET NULL`.
 *
 * **Plain text only.** Markdown rendering would diverge between Compose
 * `Text` and SwiftUI `Text` and there is no shared sanitizer; v1 keeps
 * `body` as plain text with line breaks. Soft 5000-char limit enforced
 * UI-side; server CHECK accepts up to 5000 (migration 016).
 */
@Serializable
data class PullRequestComment(
    val id: String,
    @SerialName("pull_request_id") val pullRequestId: String,
    @SerialName("author_id") val authorId: String?,
    val body: String,
    @SerialName("created_at") val createdAt: Instant,
)
