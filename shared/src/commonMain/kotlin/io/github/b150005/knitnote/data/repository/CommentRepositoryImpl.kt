package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.realtime.ChangeFilter
import io.github.b150005.knitnote.data.realtime.ChannelHandle
import io.github.b150005.knitnote.data.realtime.RealtimeChannelProvider
import io.github.b150005.knitnote.data.remote.CommentDataSourceOperations
import io.github.b150005.knitnote.domain.model.Comment
import io.github.b150005.knitnote.domain.model.CommentTargetType
import io.github.b150005.knitnote.domain.repository.CommentRepository
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.decodeOldRecord
import io.github.jan.supabase.realtime.decodeRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Remote-only Comment repository with Realtime subscription for live updates.
 * Call [closeChannel] on user logout to release subscriptions and clear cached state.
 */
class CommentRepositoryImpl(
    private val remote: CommentDataSourceOperations,
    private val channelProvider: RealtimeChannelProvider,
    private val scope: CoroutineScope,
) : CommentRepository {
    private var commentChannel: ChannelHandle? = null
    private var subscribedTargetKey: String? = null
    private val channelMutex = Mutex()
    private val _comments = MutableStateFlow<List<Comment>>(emptyList())

    override suspend fun getById(id: String): Comment? = remote.getById(id)

    override suspend fun getByTarget(
        targetType: CommentTargetType,
        targetId: String,
    ): List<Comment> = remote.getByTarget(targetType, targetId)

    override fun observeByTarget(
        targetType: CommentTargetType,
        targetId: String,
    ): Flow<List<Comment>> {
        launchRealtimeSubscription(targetType, targetId)
        return _comments.map { comments ->
            comments.filter { it.targetType == targetType && it.targetId == targetId }
        }
    }

    override suspend fun create(comment: Comment): Comment = remote.insert(comment)

    override suspend fun delete(id: String) = remote.delete(id)

    /**
     * Unsubscribe from the Realtime channel and clear cached state.
     */
    override suspend fun closeChannel() =
        channelMutex.withLock {
            closeChannelInternal()
        }

    private suspend fun closeChannelInternal() {
        commentChannel?.unsubscribe()
        commentChannel = null
        subscribedTargetKey = null
        _comments.value = emptyList()
    }

    private fun launchRealtimeSubscription(
        targetType: CommentTargetType,
        targetId: String,
    ) {
        val targetKey = "${targetType.name}:$targetId"
        scope.launch {
            channelMutex.withLock {
                // If subscribed to a different target, close old channel
                if (commentChannel != null && subscribedTargetKey != targetKey) {
                    closeChannelInternal()
                }
                if (commentChannel != null) return@withLock

                val handle = channelProvider.createChannel("comments-$targetKey")
                commentChannel = handle
                subscribedTargetKey = targetKey

                // Set up the change flow before subscribing
                handle
                    .postgresChangeFlow(
                        table = "comments",
                        filter = ChangeFilter("target_id", targetId),
                    ).onEach { action ->
                        handleCommentAction(action, targetType, targetId)
                    }.catch { e ->
                        if (e is CancellationException) throw e
                    }.launchIn(scope)

                // Subscribe first, then seed — subscribe-then-fetch pattern
                // prevents missed events between fetch and subscribe
                handle.subscribe()

                // Seed with initial fetch after subscription is active
                _comments.value = remote.getByTarget(targetType, targetId)
            }
        }
    }

    private fun handleCommentAction(
        action: PostgresAction,
        targetType: CommentTargetType,
        targetId: String,
    ) {
        when (action) {
            is PostgresAction.Insert -> {
                val comment = action.decodeRecord<Comment>()
                if (comment.targetType == targetType && comment.targetId == targetId) {
                    // Atomic read-modify-write via update {}
                    _comments.update { current -> current + comment }
                }
            }
            is PostgresAction.Delete -> {
                try {
                    val old = action.decodeOldRecord<Comment>()
                    _comments.update { current -> current.filter { it.id != old.id } }
                } catch (_: Exception) {
                    scope.launch {
                        _comments.value = remote.getByTarget(targetType, targetId)
                    }
                }
            }
            is PostgresAction.Update -> {
                // Comments are immutable — no update handling needed
            }
            is PostgresAction.Select -> { /* no-op */ }
        }
    }
}
