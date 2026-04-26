package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.data.sync.RemotePullRequestCommentSyncOperations
import io.github.b150005.knitnote.data.sync.RemotePullRequestSyncOperations
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestComment
import io.github.b150005.knitnote.domain.repository.PullRequestMergeOperations
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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
    RemotePullRequestCommentSyncOperations,
    PullRequestMergeOperations {
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

    /**
     * Phase 38.4 (ADR-014 §5) — invoke the SECURITY DEFINER `merge_pull_request`
     * RPC. Returns the new revision id minted by the RPC. Throws on RPC errors
     * which the caller (`MergePullRequestUseCase`) translates to
     * [io.github.b150005.knitnote.domain.usecase.UseCaseError] subtypes.
     *
     * The RPC accepts the resolved JSONB document directly per migration 016 —
     * the client already has the catalog and renderers, so the resolver builds
     * the merged document and hands it through ("thin server, rich client" per
     * ADR-001). Server validates structural preconditions (PR open, caller is
     * target owner, source tip unchanged) but not the merge content.
     *
     * Argument names match the SQL function signature exactly
     * (`p_pull_request_id`, `p_strategy`, `p_merged_document`,
     * `p_merged_content_hash`, `p_resolved_revision_id`) — supabase-kt's
     * `rpc(name, parameters)` overload sends these as JSON keys.
     */
    override suspend fun merge(
        pullRequestId: String,
        strategy: String,
        mergedDocument: JsonElement,
        mergedContentHash: String,
        resolvedRevisionId: String,
    ): String {
        val params: JsonObject =
            buildJsonObject {
                put("p_pull_request_id", JsonPrimitive(pullRequestId))
                put("p_strategy", JsonPrimitive(strategy))
                put("p_merged_document", mergedDocument)
                put("p_merged_content_hash", JsonPrimitive(mergedContentHash))
                put("p_resolved_revision_id", JsonPrimitive(resolvedRevisionId))
            }
        val result = supabaseClient.postgrest.rpc("merge_pull_request", params)
        // The RPC's RETURNS UUID surfaces as a JSON-encoded string in the
        // response body (e.g. `"550e8400-..."`). decodeAs<String> unwraps the
        // wrapping quotes via kotlinx.serialization. The RPC's atomic write
        // means a successful return implies all 4 mutations (revision INSERT,
        // branch tip UPDATE, chart_documents UPDATE, PR row UPDATE) committed.
        return result.decodeAs<String>()
    }

    @kotlinx.serialization.Serializable
    private data class PatternIdRow(
        val id: String,
    )
}
