package io.github.b150005.knitnote.domain.repository

import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestComment
import kotlinx.coroutines.flow.Flow

/**
 * Pull request repository surface (ADR-014 §1, §3).
 *
 * Phase 38.1 reads:
 * - [getById] resolves a single PR by id (used by [io.github.b150005.knitnote.domain.usecase.GetPullRequestUseCase] in Phase 38.3).
 * - [getIncomingForOwner] / [getOutgoingForOwner] feed the
 *   [io.github.b150005.knitnote.domain.usecase.GetIncomingPullRequestsUseCase] /
 *   [io.github.b150005.knitnote.domain.usecase.GetOutgoingPullRequestsUseCase]
 *   pair (Phase 38.2).
 * - [observeIncomingForOwner] / [observeOutgoingForOwner] drive
 *   `PullRequestListViewModel` updates while the user is on the list screen.
 * - [getCommentsForPullRequest] / [observeCommentsForPullRequest] feed
 *   `PullRequestDetailViewModel` (Phase 38.3).
 *
 * Phase 38.1 writes:
 * - [openPullRequest] writes a new PR row + enqueues a CHART/PR INSERT
 *   through the sync layer.
 * - [closePullRequest] flips status → CLOSED + enqueues UPDATE.
 * - [postComment] writes a new comment row + enqueues INSERT.
 *
 * **Merge is NOT a repository method** — Phase 38.4 wires the SECURITY
 * DEFINER `merge_pull_request` RPC through a dedicated `MergePullRequestUseCase`
 * that bypasses local-then-sync orchestration (the RPC is the only writer
 * permitted to produce `chart_revisions.author_id != owner_id` rows; the
 * caller cannot pre-write a stub locally and reconcile). When the RPC
 * returns, Realtime echoes the merged PR row back through this repo's
 * Realtime subscription.
 */
interface PullRequestRepository {
    suspend fun getById(id: String): PullRequest?

    suspend fun getIncomingForOwner(ownerId: String): List<PullRequest>

    suspend fun getOutgoingForOwner(ownerId: String): List<PullRequest>

    /**
     * Local-only observer — emits whatever is currently in the SQLDelight
     * cache. Callers that need a remote-seeded list (e.g. cold-launch
     * `PullRequestListViewModel`) should invoke [getIncomingForOwner] first
     * to backfill the cache, then collect this Flow for live updates. The
     * Realtime channel `pull-requests-incoming-<ownerId>` keeps the cache
     * warm thereafter.
     */
    fun observeIncomingForOwner(ownerId: String): Flow<List<PullRequest>>

    /** See [observeIncomingForOwner] for the cold-launch seeding contract. */
    fun observeOutgoingForOwner(ownerId: String): Flow<List<PullRequest>>

    suspend fun getCommentsForPullRequest(pullRequestId: String): List<PullRequestComment>

    fun observeCommentsForPullRequest(pullRequestId: String): Flow<List<PullRequestComment>>

    suspend fun openPullRequest(pullRequest: PullRequest): PullRequest

    suspend fun closePullRequest(pullRequest: PullRequest): PullRequest

    suspend fun postComment(comment: PullRequestComment): PullRequestComment
}
