package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.data.sync.RemotePullRequestCommentSyncOperations
import io.github.b150005.knitnote.data.sync.RemotePullRequestSyncOperations
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestComment
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

/**
 * Remote data source for [PullRequest] + [PullRequestComment] (ADR-014 §1).
 *
 * Implements both sync operations interfaces — PRs route writes through
 * `upsert` (INSERT and CLOSE both) per ADR-014 §7, comments are append-only.
 * The merge RPC is NOT exposed here; it's invoked from the
 * `MergePullRequestUseCase` (Phase 38.4) directly against
 * [SupabaseClient.postgrest.rpc].
 */
class RemotePullRequestDataSource(
    private val supabaseClient: SupabaseClient,
) : RemotePullRequestSyncOperations,
    RemotePullRequestCommentSyncOperations {
    private val prTable get() = supabaseClient.postgrest["pull_requests"]
    private val commentTable get() = supabaseClient.postgrest["pull_request_comments"]

    suspend fun getById(id: String): PullRequest? =
        prTable
            .select {
                filter { eq("id", id) }
            }.decodeSingleOrNull()

    suspend fun getIncomingForOwner(ownerId: String): List<PullRequest> {
        // Two-step fetch: first the owner's pattern ids, then PRs targeting any
        // of them. RLS on pull_requests already restricts visibility to source
        // owner OR target owner, but the explicit pattern-id filter is the
        // semantically-correct "incoming" scope (excludes outgoing PRs the
        // owner authored against patterns owned by someone else — an edge case
        // that requires the user to own a fork of a pattern they then author
        // a PR against).
        val patternIds =
            supabaseClient.postgrest["patterns"]
                .select(
                    io.github.jan.supabase.postgrest.query.Columns
                        .raw("id"),
                ) {
                    filter { eq("owner_id", ownerId) }
                }.decodeList<PatternIdRow>()
                .map { it.id }
        if (patternIds.isEmpty()) return emptyList()
        return prTable
            .select {
                filter { isIn("target_pattern_id", patternIds) }
                order("created_at", Order.DESCENDING)
            }.decodeList()
    }

    suspend fun getOutgoingForOwner(ownerId: String): List<PullRequest> =
        prTable
            .select {
                filter { eq("author_id", ownerId) }
                order("created_at", Order.DESCENDING)
            }.decodeList()

    suspend fun getCommentsForPullRequest(pullRequestId: String): List<PullRequestComment> =
        commentTable
            .select {
                filter { eq("pull_request_id", pullRequestId) }
                order("created_at", Order.ASCENDING)
            }.decodeList()

    /**
     * Idempotent on `id` — `onConflict` targets the PRIMARY KEY so a
     * re-enqueued upsert (PendingSync retry after the request landed but the
     * response was lost) is a silent overwrite with the same row.
     */
    override suspend fun upsert(pullRequest: PullRequest): PullRequest =
        prTable
            .upsert(pullRequest) {
                onConflict = "id"
                select()
            }.decodeSingle()

    override suspend fun appendComment(comment: PullRequestComment): PullRequestComment =
        commentTable
            .upsert(comment) {
                onConflict = "id"
                ignoreDuplicates = true
                select()
            }.decodeSingleOrNull() ?: comment

    @kotlinx.serialization.Serializable
    private data class PatternIdRow(
        val id: String,
    )
}
