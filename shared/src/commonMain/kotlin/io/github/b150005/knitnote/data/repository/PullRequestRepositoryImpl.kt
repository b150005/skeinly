package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.local.LocalPullRequestDataSource
import io.github.b150005.knitnote.data.remote.RemotePullRequestDataSource
import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncManagerOperations
import io.github.b150005.knitnote.data.sync.SyncOperation
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestComment
import io.github.b150005.knitnote.domain.model.PullRequestStatus
import io.github.b150005.knitnote.domain.repository.PullRequestRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

class PullRequestRepositoryImpl(
    private val local: LocalPullRequestDataSource,
    private val remote: RemotePullRequestDataSource?,
    private val isOnline: StateFlow<Boolean>,
    private val syncManager: SyncManagerOperations,
    private val json: Json,
) : PullRequestRepository {
    override suspend fun getById(id: String): PullRequest? {
        val cached = local.getById(id)
        if (cached != null || remote == null || !isOnline.value) return cached
        return try {
            remote.getById(id)?.also { local.upsert(it) }
        } catch (_: Exception) {
            // Network failure on a cache miss — caller treats as "not found"
            // and the next Realtime backfill heals.
            null
        }
    }

    override suspend fun getIncomingForOwner(ownerId: String): List<PullRequest> {
        val cached = local.getIncomingForOwner(ownerId)
        if (cached.isNotEmpty() || remote == null || !isOnline.value) return cached
        return try {
            val remoteRows = remote.getIncomingForOwner(ownerId)
            remoteRows.forEach { local.upsert(it) }
            remoteRows
        } catch (_: Exception) {
            cached
        }
    }

    override suspend fun getOutgoingForOwner(ownerId: String): List<PullRequest> {
        val cached = local.getOutgoingForOwner(ownerId)
        if (cached.isNotEmpty() || remote == null || !isOnline.value) return cached
        return try {
            val remoteRows = remote.getOutgoingForOwner(ownerId)
            remoteRows.forEach { local.upsert(it) }
            remoteRows
        } catch (_: Exception) {
            cached
        }
    }

    override fun observeIncomingForOwner(ownerId: String): Flow<List<PullRequest>> = local.observeIncomingForOwner(ownerId)

    override fun observeOutgoingForOwner(ownerId: String): Flow<List<PullRequest>> = local.observeOutgoingForOwner(ownerId)

    override suspend fun getCommentsForPullRequest(pullRequestId: String): List<PullRequestComment> {
        val cached = local.getCommentsForPullRequest(pullRequestId)
        if (cached.isNotEmpty() || remote == null || !isOnline.value) return cached
        return try {
            val remoteRows = remote.getCommentsForPullRequest(pullRequestId)
            remoteRows.forEach { local.upsertComment(it) }
            remoteRows
        } catch (_: Exception) {
            cached
        }
    }

    override fun observeCommentsForPullRequest(pullRequestId: String): Flow<List<PullRequestComment>> =
        local.observeCommentsForPullRequest(pullRequestId)

    override suspend fun openPullRequest(pullRequest: PullRequest): PullRequest {
        local.upsert(pullRequest)
        syncManager.syncOrEnqueue(
            SyncEntityType.PULL_REQUEST,
            pullRequest.id,
            SyncOperation.INSERT,
            json.encodeToString(pullRequest),
        )
        return pullRequest
    }

    override suspend fun closePullRequest(pullRequest: PullRequest): PullRequest {
        // Caller hands back the full PR with status = CLOSED + closedAt set.
        // Local write replays the row through upsert; sync layer enqueues an
        // UPDATE that maps to remote upsert (idempotent on `id`).
        val now = Clock.System.now()
        val closed =
            pullRequest.copy(
                status = PullRequestStatus.CLOSED,
                closedAt = pullRequest.closedAt ?: now,
                updatedAt = now,
            )
        local.upsert(closed)
        syncManager.syncOrEnqueue(
            SyncEntityType.PULL_REQUEST,
            closed.id,
            SyncOperation.UPDATE,
            json.encodeToString(closed),
        )
        return closed
    }

    override suspend fun postComment(comment: PullRequestComment): PullRequestComment {
        local.upsertComment(comment)
        syncManager.syncOrEnqueue(
            SyncEntityType.PULL_REQUEST_COMMENT,
            comment.id,
            SyncOperation.INSERT,
            json.encodeToString(comment),
        )
        return comment
    }
}
