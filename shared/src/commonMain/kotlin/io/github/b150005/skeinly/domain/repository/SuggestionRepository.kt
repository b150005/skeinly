package io.github.b150005.skeinly.domain.repository

import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionComment
import kotlinx.coroutines.flow.Flow

/**
 * Pull request repository surface (ADR-014 §1, §3).
 *
 * Phase 38.1 reads:
 * - [getById] resolves a single PR by id (used by [io.github.b150005.skeinly.domain.usecase.GetSuggestionUseCase] in Phase 38.3).
 * - [getIncomingForOwner] / [getOutgoingForOwner] feed the
 *   [io.github.b150005.skeinly.domain.usecase.GetIncomingSuggestionsUseCase] /
 *   [io.github.b150005.skeinly.domain.usecase.GetOutgoingSuggestionsUseCase]
 *   pair (Phase 38.2).
 * - [observeIncomingForOwner] / [observeOutgoingForOwner] drive
 *   `SuggestionListViewModel` updates while the user is on the list screen.
 * - [getCommentsForSuggestion] / [observeCommentsForSuggestion] feed
 *   `SuggestionDetailViewModel` (Phase 38.3).
 *
 * Phase 38.1 writes:
 * - [openSuggestion] writes a new PR row + enqueues a CHART/PR INSERT
 *   through the sync layer.
 * - [closeSuggestion] flips status → CLOSED + enqueues UPDATE.
 * - [postComment] writes a new comment row + enqueues INSERT.
 *
 * **Merge is NOT a repository method** — Phase 38.4 wires the SECURITY
 * DEFINER `merge_pull_request` RPC through a dedicated `ApplySuggestionUseCase`
 * that bypasses local-then-sync orchestration (the RPC is the only writer
 * permitted to produce `chart_revisions.author_id != owner_id` rows; the
 * caller cannot pre-write a stub locally and reconcile). When the RPC
 * returns, Realtime echoes the merged PR row back through this repo's
 * Realtime subscription.
 */
interface SuggestionRepository {
    suspend fun getById(id: String): Suggestion?

    suspend fun getIncomingForOwner(ownerId: String): List<Suggestion>

    suspend fun getOutgoingForOwner(ownerId: String): List<Suggestion>

    /**
     * Local-only observer — emits whatever is currently in the SQLDelight
     * cache. Callers that need a remote-seeded list (e.g. cold-launch
     * `SuggestionListViewModel`) should invoke [getIncomingForOwner] first
     * to backfill the cache, then collect this Flow for live updates. The
     * Realtime channel `pull-requests-incoming-<ownerId>` keeps the cache
     * warm thereafter.
     */
    fun observeIncomingForOwner(ownerId: String): Flow<List<Suggestion>>

    /** See [observeIncomingForOwner] for the cold-launch seeding contract. */
    fun observeOutgoingForOwner(ownerId: String): Flow<List<Suggestion>>

    suspend fun getCommentsForSuggestion(suggestionId: String): List<SuggestionComment>

    fun observeCommentsForSuggestion(suggestionId: String): Flow<List<SuggestionComment>>

    suspend fun openSuggestion(suggestion: Suggestion): Suggestion

    suspend fun closeSuggestion(suggestion: Suggestion): Suggestion

    suspend fun postComment(comment: SuggestionComment): SuggestionComment

    /**
     * Open the per-PR Realtime channel `pull-request-comments-<prId>` (ADR-014
     * §7) so INSERTs to `pull_request_comments` for this PR land in the local
     * SQLDelight cache. Calling [observeCommentsForSuggestion] alone is not
     * enough — that observer reads from the cache; without the channel, no peer
     * comment ever reaches the cache. Idempotent on the same `prId`; calling on
     * a different `prId` swaps the active channel.
     *
     * Local-only mode (no `RealtimeChannelProvider`) is a silent no-op so the
     * Phase 38.3 [SuggestionDetailViewModel] does not need a configuration check.
     */
    suspend fun subscribeToCommentsChannel(suggestionId: String)

    /**
     * Tear down the channel opened by [subscribeToCommentsChannel]. Called by
     * the ViewModel in `onCleared()`. Safe to call when no channel is open.
     */
    suspend fun closeCommentsChannel()
}
