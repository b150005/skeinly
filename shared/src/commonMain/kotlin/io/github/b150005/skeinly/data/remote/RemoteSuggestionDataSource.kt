package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.data.sync.RemoteSuggestionCommentSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteSuggestionSyncOperations
import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionComment
import io.github.b150005.skeinly.domain.repository.SuggestionMergeOperations
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Remote data source for [Suggestion] + [SuggestionComment] (ADR-014 §1).
 *
 * Implements both sync operations interfaces — suggestions route writes
 * through `upsert` (INSERT and CLOSE both) per ADR-014 §7, comments are
 * append-only. The apply RPC is NOT exposed here; it's invoked from the
 * `ApplySuggestionUseCase` (Phase 38.4) directly against
 * [SupabaseClient.postgrest.rpc].
 */
class RemoteSuggestionDataSource(
    private val supabaseClient: SupabaseClient,
) : RemoteSuggestionSyncOperations,
    RemoteSuggestionCommentSyncOperations,
    SuggestionMergeOperations {
    private val prTable get() = supabaseClient.postgrest["suggestions"]
    private val commentTable get() = supabaseClient.postgrest["suggestion_comments"]

    suspend fun getById(id: String): Suggestion? =
        prTable
            .select {
                filter { eq("id", id) }
            }.decodeSingleOrNull()

    suspend fun getIncomingForOwner(ownerId: String): List<Suggestion> {
        // Two-step fetch: first the owner's pattern ids, then suggestions
        // targeting any of them. RLS on suggestions already restricts
        // visibility to source owner OR target owner, but the explicit
        // pattern-id filter is the semantically-correct "incoming" scope
        // (excludes outgoing suggestions the owner authored against patterns
        // owned by someone else — an edge case that requires the user to own
        // a fork of a pattern they then author a suggestion against).
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

    suspend fun getOutgoingForOwner(ownerId: String): List<Suggestion> =
        prTable
            .select {
                filter { eq("author_id", ownerId) }
                order("created_at", Order.DESCENDING)
            }.decodeList()

    suspend fun getCommentsForSuggestion(suggestionId: String): List<SuggestionComment> =
        commentTable
            .select {
                filter { eq("pull_request_id", suggestionId) }
                order("created_at", Order.ASCENDING)
            }.decodeList()

    /**
     * Idempotent on `id` — `onConflict` targets the PRIMARY KEY so a
     * re-enqueued upsert (PendingSync retry after the request landed but the
     * response was lost) is a silent overwrite with the same row.
     */
    override suspend fun upsert(suggestion: Suggestion): Suggestion =
        prTable
            .upsert(suggestion) {
                onConflict = "id"
                select()
            }.decodeSingle()

    override suspend fun appendComment(comment: SuggestionComment): SuggestionComment =
        commentTable
            .upsert(comment) {
                onConflict = "id"
                ignoreDuplicates = true
                select()
            }.decodeSingleOrNull() ?: comment

    /**
     * Phase 38.4 (ADR-014 §5) — invoke the SECURITY DEFINER `apply_suggestion`
     * RPC. Returns the new version id minted by the RPC. Throws on RPC errors
     * which the caller (`ApplySuggestionUseCase`) translates to
     * [io.github.b150005.skeinly.domain.usecase.UseCaseError] subtypes.
     *
     * The RPC accepts the resolved JSONB document directly per migration 016
     * + 027 — the client already has the catalog and renderers, so the
     * resolver builds the applied document and hands it through ("thin
     * server, rich client" per ADR-001). Server validates structural
     * preconditions (suggestion open, caller is target owner, source tip
     * unchanged) but not the applied content.
     *
     * Argument names match the SQL function signature exactly
     * (`p_suggestion_id`, `p_strategy`, `p_applied_document`,
     * `p_applied_content_hash`, `p_resolved_revision_id`) — supabase-kt's
     * `rpc(name, parameters)` overload sends these as JSON keys.
     */
    override suspend fun merge(
        suggestionId: String,
        strategy: String,
        mergedDocument: JsonElement,
        mergedContentHash: String,
        resolvedRevisionId: String,
    ): String {
        val params: JsonObject =
            buildJsonObject {
                put("p_suggestion_id", JsonPrimitive(suggestionId))
                put("p_strategy", JsonPrimitive(strategy))
                put("p_applied_document", mergedDocument)
                put("p_applied_content_hash", JsonPrimitive(mergedContentHash))
                put("p_resolved_revision_id", JsonPrimitive(resolvedRevisionId))
            }
        val result = supabaseClient.postgrest.rpc("apply_suggestion", params)
        // The RPC's RETURNS UUID surfaces as a JSON-encoded string in the
        // response body (e.g. `"550e8400-..."`). decodeAs<String> unwraps the
        // wrapping quotes via kotlinx.serialization. The RPC's atomic write
        // means a successful return implies all 4 mutations (version INSERT,
        // variation tip UPDATE, chart_documents UPDATE, suggestion row
        // UPDATE) committed.
        return result.decodeAs<String>()
    }

    @kotlinx.serialization.Serializable
    private data class PatternIdRow(
        val id: String,
    )
}
