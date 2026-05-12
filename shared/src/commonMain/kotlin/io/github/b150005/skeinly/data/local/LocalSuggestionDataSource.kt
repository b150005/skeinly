package io.github.b150005.skeinly.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.b150005.skeinly.data.mapper.toDbString
import io.github.b150005.skeinly.data.mapper.toDomain
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionComment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class LocalSuggestionDataSource(
    private val db: SkeinlyDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val prQueries get() = db.suggestionQueries
    private val commentQueries get() = db.suggestionCommentQueries

    // ---- suggestions ----

    suspend fun getById(id: String): Suggestion? =
        withContext(ioDispatcher) {
            prQueries.getById(id).executeAsOneOrNull()?.toDomain()
        }

    suspend fun getByTargetPattern(patternId: String): List<Suggestion> =
        withContext(ioDispatcher) {
            prQueries.getByTargetPattern(patternId).executeAsList().map { it.toDomain() }
        }

    suspend fun getBySourcePattern(patternId: String): List<Suggestion> =
        withContext(ioDispatcher) {
            prQueries.getBySourcePattern(patternId).executeAsList().map { it.toDomain() }
        }

    suspend fun getIncomingForOwner(ownerId: String): List<Suggestion> =
        withContext(ioDispatcher) {
            prQueries.getIncomingForOwner(ownerId).executeAsList().map { it.toDomain() }
        }

    suspend fun getOutgoingForOwner(ownerId: String): List<Suggestion> =
        withContext(ioDispatcher) {
            prQueries.getOutgoingForOwner(ownerId).executeAsList().map { it.toDomain() }
        }

    fun observeIncomingForOwner(ownerId: String): Flow<List<Suggestion>> =
        prQueries
            .observeIncomingForOwner(ownerId)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    fun observeOutgoingForOwner(ownerId: String): Flow<List<Suggestion>> =
        prQueries
            .observeOutgoingForOwner(ownerId)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun countOpenIncomingForOwner(ownerId: String): Long =
        withContext(ioDispatcher) {
            prQueries.countOpenIncomingForOwner(ownerId).executeAsOne()
        }

    /**
     * Idempotent INSERT-OR-REPLACE. Used by both the open-suggestion path and
     * the Realtime backfill path: a re-applied event simply overwrites with
     * the same row (suggestion carries its own updatedAt so consumers can
     * detect drift).
     */
    suspend fun upsert(suggestion: Suggestion): Suggestion =
        withContext(ioDispatcher) {
            prQueries.upsert(
                id = suggestion.id,
                source_pattern_id = suggestion.sourcePatternId,
                source_branch_id = suggestion.sourceBranchId,
                source_tip_revision_id = suggestion.sourceTipRevisionId,
                target_pattern_id = suggestion.targetPatternId,
                target_branch_id = suggestion.targetBranchId,
                common_ancestor_revision_id = suggestion.commonAncestorRevisionId,
                author_id = suggestion.authorId,
                title = suggestion.title,
                description = suggestion.description,
                status = suggestion.status.toDbString(),
                merged_revision_id = suggestion.mergedRevisionId,
                merged_at = suggestion.mergedAt?.toString(),
                closed_at = suggestion.closedAt?.toString(),
                created_at = suggestion.createdAt.toString(),
                updated_at = suggestion.updatedAt.toString(),
            )
            suggestion
        }

    suspend fun updateStatus(
        id: String,
        status: io.github.b150005.skeinly.domain.model.SuggestionStatus,
        closedAt: Instant?,
        updatedAt: Instant,
    ): Unit =
        withContext(ioDispatcher) {
            prQueries.updateStatus(
                status = status.toDbString(),
                closed_at = closedAt?.toString(),
                updated_at = updatedAt.toString(),
                id = id,
            )
        }

    suspend fun deleteById(id: String): Unit =
        withContext(ioDispatcher) {
            prQueries.deleteById(id)
        }

    suspend fun deleteByPatternId(patternId: String): Unit =
        withContext(ioDispatcher) {
            prQueries.deleteByPatternId(patternId, patternId)
        }

    // ---- suggestion_comments ----

    suspend fun getCommentById(id: String): SuggestionComment? =
        withContext(ioDispatcher) {
            commentQueries.getById(id).executeAsOneOrNull()?.toDomain()
        }

    suspend fun getCommentsForSuggestion(suggestionId: String): List<SuggestionComment> =
        withContext(ioDispatcher) {
            commentQueries.getBySuggestionId(suggestionId).executeAsList().map { it.toDomain() }
        }

    fun observeCommentsForSuggestion(suggestionId: String): Flow<List<SuggestionComment>> =
        commentQueries
            .observeBySuggestionId(suggestionId)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun countCommentsForSuggestion(suggestionId: String): Long =
        withContext(ioDispatcher) {
            commentQueries.countBySuggestionId(suggestionId).executeAsOne()
        }

    /**
     * Idempotent INSERT OR IGNORE — comments are append-only at RLS, so a
     * re-applied Realtime event for an already-stored comment is a silent
     * no-op rather than a constraint violation. PRIMARY KEY on `id` is the
     * dedup key.
     */
    suspend fun upsertComment(comment: SuggestionComment): SuggestionComment =
        withContext(ioDispatcher) {
            commentQueries.upsert(
                id = comment.id,
                pull_request_id = comment.suggestionId,
                author_id = comment.authorId,
                body = comment.body,
                created_at = comment.createdAt.toString(),
            )
            comment
        }

    suspend fun deleteCommentsForSuggestion(suggestionId: String): Unit =
        withContext(ioDispatcher) {
            commentQueries.deleteBySuggestionId(suggestionId)
        }
}
