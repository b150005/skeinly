package io.github.b150005.skeinly.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.b150005.skeinly.data.mapper.toDbString
import io.github.b150005.skeinly.data.mapper.toDomain
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.domain.model.PullRequest
import io.github.b150005.skeinly.domain.model.PullRequestComment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class LocalPullRequestDataSource(
    private val db: SkeinlyDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val prQueries get() = db.pullRequestQueries
    private val commentQueries get() = db.pullRequestCommentQueries

    // ---- pull_requests ----

    suspend fun getById(id: String): PullRequest? =
        withContext(ioDispatcher) {
            prQueries.getById(id).executeAsOneOrNull()?.toDomain()
        }

    suspend fun getByTargetPattern(patternId: String): List<PullRequest> =
        withContext(ioDispatcher) {
            prQueries.getByTargetPattern(patternId).executeAsList().map { it.toDomain() }
        }

    suspend fun getBySourcePattern(patternId: String): List<PullRequest> =
        withContext(ioDispatcher) {
            prQueries.getBySourcePattern(patternId).executeAsList().map { it.toDomain() }
        }

    suspend fun getIncomingForOwner(ownerId: String): List<PullRequest> =
        withContext(ioDispatcher) {
            prQueries.getIncomingForOwner(ownerId).executeAsList().map { it.toDomain() }
        }

    suspend fun getOutgoingForOwner(ownerId: String): List<PullRequest> =
        withContext(ioDispatcher) {
            prQueries.getOutgoingForOwner(ownerId).executeAsList().map { it.toDomain() }
        }

    fun observeIncomingForOwner(ownerId: String): Flow<List<PullRequest>> =
        prQueries
            .observeIncomingForOwner(ownerId)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    fun observeOutgoingForOwner(ownerId: String): Flow<List<PullRequest>> =
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
     * Idempotent INSERT-OR-REPLACE. Used by both the open-PR path and the
     * Realtime backfill path: a re-applied event simply overwrites with the
     * same row (PR carries its own updatedAt so consumers can detect drift).
     */
    suspend fun upsert(pullRequest: PullRequest): PullRequest =
        withContext(ioDispatcher) {
            prQueries.upsert(
                id = pullRequest.id,
                source_pattern_id = pullRequest.sourcePatternId,
                source_branch_id = pullRequest.sourceBranchId,
                source_tip_revision_id = pullRequest.sourceTipRevisionId,
                target_pattern_id = pullRequest.targetPatternId,
                target_branch_id = pullRequest.targetBranchId,
                common_ancestor_revision_id = pullRequest.commonAncestorRevisionId,
                author_id = pullRequest.authorId,
                title = pullRequest.title,
                description = pullRequest.description,
                status = pullRequest.status.toDbString(),
                merged_revision_id = pullRequest.mergedRevisionId,
                merged_at = pullRequest.mergedAt?.toString(),
                closed_at = pullRequest.closedAt?.toString(),
                created_at = pullRequest.createdAt.toString(),
                updated_at = pullRequest.updatedAt.toString(),
            )
            pullRequest
        }

    suspend fun updateStatus(
        id: String,
        status: io.github.b150005.skeinly.domain.model.PullRequestStatus,
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

    // ---- pull_request_comments ----

    suspend fun getCommentById(id: String): PullRequestComment? =
        withContext(ioDispatcher) {
            commentQueries.getById(id).executeAsOneOrNull()?.toDomain()
        }

    suspend fun getCommentsForPullRequest(pullRequestId: String): List<PullRequestComment> =
        withContext(ioDispatcher) {
            commentQueries.getByPullRequestId(pullRequestId).executeAsList().map { it.toDomain() }
        }

    fun observeCommentsForPullRequest(pullRequestId: String): Flow<List<PullRequestComment>> =
        commentQueries
            .observeByPullRequestId(pullRequestId)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun countCommentsForPullRequest(pullRequestId: String): Long =
        withContext(ioDispatcher) {
            commentQueries.countByPullRequestId(pullRequestId).executeAsOne()
        }

    /**
     * Idempotent INSERT OR IGNORE — comments are append-only at RLS, so a
     * re-applied Realtime event for an already-stored comment is a silent
     * no-op rather than a constraint violation. PRIMARY KEY on `id` is the
     * dedup key.
     */
    suspend fun upsertComment(comment: PullRequestComment): PullRequestComment =
        withContext(ioDispatcher) {
            commentQueries.upsert(
                id = comment.id,
                pull_request_id = comment.pullRequestId,
                author_id = comment.authorId,
                body = comment.body,
                created_at = comment.createdAt.toString(),
            )
            comment
        }

    suspend fun deleteCommentsForPullRequest(pullRequestId: String): Unit =
        withContext(ioDispatcher) {
            commentQueries.deleteByPullRequestId(pullRequestId)
        }
}
