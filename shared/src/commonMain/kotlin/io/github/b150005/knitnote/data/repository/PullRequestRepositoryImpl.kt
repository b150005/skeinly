package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.local.LocalPullRequestDataSource
import io.github.b150005.knitnote.data.realtime.ChangeFilter
import io.github.b150005.knitnote.data.realtime.ChannelHandle
import io.github.b150005.knitnote.data.realtime.RealtimeChannelProvider
import io.github.b150005.knitnote.data.remote.RemotePullRequestDataSource
import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncManagerOperations
import io.github.b150005.knitnote.data.sync.SyncOperation
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestComment
import io.github.b150005.knitnote.domain.model.PullRequestStatus
import io.github.b150005.knitnote.domain.repository.PullRequestRepository
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.decodeRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * [channelProvider] + [scope] are nullable so test fakes that construct the
 * impl directly (without DI) and the local-only mode (Supabase not configured)
 * both keep [subscribeToCommentsChannel] / [closeCommentsChannel] as silent
 * no-ops. Production wiring in `RepositoryModule` always provides non-null
 * when Supabase is configured.
 */
class PullRequestRepositoryImpl(
    private val local: LocalPullRequestDataSource,
    private val remote: RemotePullRequestDataSource?,
    private val isOnline: StateFlow<Boolean>,
    private val syncManager: SyncManagerOperations,
    private val json: Json,
    private val channelProvider: RealtimeChannelProvider? = null,
    private val scope: CoroutineScope? = null,
) : PullRequestRepository {
    private var commentsChannel: ChannelHandle? = null
    private var subscribedPrId: String? = null
    private val channelMutex = Mutex()

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

    override suspend fun subscribeToCommentsChannel(pullRequestId: String) {
        val provider = channelProvider ?: return
        val scope = scope ?: return
        channelMutex.withLock {
            // Idempotent on the same prId; swap channels when prId changes
            // (PullRequestDetailViewModel is parameterized on prId so this is
            // mostly a defensive guard against in-flight init re-entry).
            if (commentsChannel != null && subscribedPrId == pullRequestId) return
            if (commentsChannel != null) {
                commentsChannel?.unsubscribe()
                commentsChannel = null
                subscribedPrId = null
            }

            val handle = provider.createChannel("pull-request-comments-$pullRequestId")
            commentsChannel = handle
            subscribedPrId = pullRequestId

            handle
                .postgresChangeFlow(
                    table = "pull_request_comments",
                    filter = ChangeFilter("pull_request_id", pullRequestId),
                ).onEach { handleCommentAction(it) }
                .catch { e -> if (e is CancellationException) throw e }
                .launchIn(scope)

            handle.subscribe()

            // Subscribe-then-fetch pattern (mirrors CommentRepositoryImpl):
            // subscribing first guarantees no INSERT lands between the seed
            // fetch and the channel attachment. Failures here are tolerated
            // — the local cache stays whatever it was before subscription
            // and Realtime fills the gap as events arrive.
            scope.launch {
                try {
                    remote?.getCommentsForPullRequest(pullRequestId)?.forEach { local.upsertComment(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Non-fatal: cache stays as-is; Realtime will heal.
                }
            }
        }
    }

    override suspend fun closeCommentsChannel() {
        channelMutex.withLock {
            commentsChannel?.unsubscribe()
            commentsChannel = null
            subscribedPrId = null
        }
    }

    private suspend fun handleCommentAction(action: PostgresAction) {
        when (action) {
            is PostgresAction.Insert -> {
                val comment = action.decodeRecord<PullRequestComment>()
                local.upsertComment(comment)
            }
            // Comments are append-only at RLS — UPDATE / DELETE never fire
            // server-side, but the Realtime SDK still surfaces the variants
            // so the `when` stays exhaustive.
            is PostgresAction.Update -> Unit
            is PostgresAction.Delete -> Unit
            is PostgresAction.Select -> Unit
        }
    }
}
