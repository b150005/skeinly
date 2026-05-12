package io.github.b150005.skeinly.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Lifecycle of a [Suggestion] (ADR-014 §1, §3).
 *
 * Closed enum — three values, no DRAFT for v1.
 *
 * - [OPEN]: actionable. Both parties can comment; target owner can apply or
 *   close; source author can close.
 * - [APPLIED]: terminal. Reached only via the SECURITY DEFINER
 *   `apply_suggestion` RPC — never via a client-side UPDATE. The suggestion
 *   row's [Suggestion.appliedVersionId] points at the resulting version.
 * - [CLOSED]: terminal. Reached when either party closes the suggestion
 *   without applying it. Comments survive but no further state transitions
 *   are valid.
 *
 * Wire format uses lowercase string per the Postgres CHECK constraint
 * applied by migration 027 (`status IN ('open', 'applied', 'closed')`).
 */
@Serializable
enum class SuggestionStatus {
    @SerialName("open")
    OPEN,

    @SerialName("applied")
    APPLIED,

    @SerialName("closed")
    CLOSED,
}

/**
 * One suggestion between a forked pattern and its upstream (ADR-014 §1, §3).
 *
 * **Routing.** Phase 38 v1 routes suggestions only fork → upstream —
 * [targetPatternId] is always the source's [Pattern.parentPatternId]
 * (ADR-012 §1). Internal cross-variation suggestions within the same
 * pattern and arbitrary cross-fork routing are out of scope.
 *
 * **Authorship.** [authorId] is the source pattern's owner at suggestion-open
 * time. Nullable to mirror the Postgres `ON DELETE SET NULL` FK to
 * `profiles` — suggestion rows survive author account deletion the same way
 * versions do (ADR-013 §1). Local writes always populate it; null only
 * appears after a remote-side cascade.
 *
 * **Snapshots.** [sourceTipRevisionId] is the source variation tip at
 * suggestion-open; [commonAncestorRevisionId] is the most recent version
 * present in BOTH histories (the source's `parent_revision_id` chain walked
 * back to a row still present in the target's history). Both are captured
 * client-side at suggestion-open and stored as immutable snapshots so the
 * diff is reproducible even if the source tip drifts forward while the
 * suggestion is open. The apply RPC re-validates [sourceTipRevisionId]
 * against the live source variation tip and raises if it has drifted
 * (resolver re-runs).
 *
 * **No `target_tip_revision_id` snapshot.** The target tip moves under the
 * suggestion as the target owner commits to their own variation; the apply
 * RPC always uses the *current* target tip as the apply base.
 *
 * **Apply result.** [appliedVersionId] / [appliedAt] (Postgres column names
 * `merged_revision_id` / `merged_at` preserved verbatim per migration 027's
 * "internal column names not renamed" scope; the Kotlin property names use
 * the post-Phase-D verbs) are populated by the RPC when status flips to
 * [SuggestionStatus.APPLIED]. NULL on OPEN and CLOSED suggestions.
 */
@Serializable
data class Suggestion(
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
    val status: SuggestionStatus,
    @SerialName("merged_revision_id") val appliedVersionId: String?,
    @SerialName("merged_at") val appliedAt: Instant?,
    @SerialName("closed_at") val closedAt: Instant?,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
) {
    /**
     * Gates the apply button on [SuggestionDetailScreen] (Phase 38.3).
     *
     * Phase 38.1 ships this as a domain-side helper so the ViewModel doesn't
     * need to learn the [targetPatternId] → owner lookup. The apply RPC
     * re-validates server-side regardless.
     *
     * **Caller contract**: [targetOwnerId] must be resolved from
     * [targetPatternId] by the consumer (e.g. via `PatternRepository.getById`).
     * This helper trusts the caller to supply a correct value — passing the
     * wrong owner id silently flips the gate and exposes an apply button to
     * a non-owner. The RPC's `WHERE owner_id = v_caller` check is the actual
     * security boundary; this method is UI-affordance gating only.
     */
    fun canApply(
        currentUserId: String,
        targetOwnerId: String,
    ): Boolean = status == SuggestionStatus.OPEN && currentUserId == targetOwnerId
}

/**
 * One flat append-only comment on a suggestion (ADR-014 §1, §3).
 *
 * **Append-only.** RLS forbids UPDATE / DELETE — comments are immutable
 * once posted, mirroring [ChartVersion]'s history-immutability invariant
 * (ADR-013 §1). A typo-laden comment is permanent until the suggestion is
 * closed (closure preserves the comment chain). Soft-delete is post-v1.
 *
 * **Authorship.** [authorId] nullable for the same reason as
 * [Suggestion.authorId] — the FK to `profiles` is `ON DELETE SET NULL`.
 *
 * **Plain text only.** Markdown rendering would diverge between Compose
 * `Text` and SwiftUI `Text` and there is no shared sanitizer; v1 keeps
 * `body` as plain text with line breaks. Soft 5000-char limit enforced
 * UI-side; server CHECK accepts up to 5000 (migration 016).
 *
 * **Wire format note.** The [suggestionId] field maps to the Postgres
 * column `pull_request_id` — migration 027 deliberately did NOT rename
 * internal column names (only the table and RPC). See ADR-014 amendment
 * + migration 027 header for the rationale.
 */
@Serializable
data class SuggestionComment(
    val id: String,
    @SerialName("pull_request_id") val suggestionId: String,
    @SerialName("author_id") val authorId: String?,
    val body: String,
    @SerialName("created_at") val createdAt: Instant,
)
