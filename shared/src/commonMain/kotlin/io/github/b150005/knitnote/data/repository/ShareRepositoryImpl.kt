package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.realtime.ChangeFilter
import io.github.b150005.knitnote.data.realtime.ChannelHandle
import io.github.b150005.knitnote.data.realtime.RealtimeChannelProvider
import io.github.b150005.knitnote.data.remote.ShareDataSourceOperations
import io.github.b150005.knitnote.domain.model.Share
import io.github.b150005.knitnote.domain.model.ShareStatus
import io.github.b150005.knitnote.domain.repository.ShareRepository
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Remote-only Share repository. Sharing is inherently online — no local SQLDelight cache.
 * Uses Supabase Realtime for live updates instead of polling.
 *
 * Call [closeChannel] on user logout to release the Realtime subscription and clear cached state.
 */
class ShareRepositoryImpl(
    private val remote: ShareDataSourceOperations,
    private val channelProvider: RealtimeChannelProvider,
    private val scope: CoroutineScope,
) : ShareRepository {
    private var shareChannel: ChannelHandle? = null
    private var subscribedUserId: String? = null
    private val channelMutex = Mutex()
    private val _receivedShares = MutableStateFlow<List<Share>>(emptyList())

    override suspend fun getById(id: String): Share? = remote.getById(id)

    override suspend fun getByPatternId(patternId: String): List<Share> = remote.getByPatternId(patternId)

    override suspend fun getByToken(token: String): Share? = remote.getByToken(token)

    override suspend fun getReceivedByUserId(userId: String): List<Share> = remote.getReceivedByUserId(userId)

    override fun observeReceivedByUserId(userId: String): Flow<List<Share>> {
        launchRealtimeSubscription(userId)
        return _receivedShares.map { shares ->
            shares.filter { it.toUserId == userId }
        }
    }

    override suspend fun create(share: Share): Share = remote.insert(share)

    override suspend fun updateStatus(
        id: String,
        status: ShareStatus,
    ): Share {
        remote.updateStatus(id, status)
        return remote.getById(id) ?: error("Share not found after status update: $id")
    }

    override suspend fun delete(id: String) = remote.delete(id)

    /**
     * Unsubscribe from the Realtime channel and clear cached state.
     * Call on user logout to prevent stale data for the next user.
     */
    override suspend fun closeChannel() =
        channelMutex.withLock {
            closeChannelInternal()
        }

    private suspend fun closeChannelInternal() {
        shareChannel?.unsubscribe()
        shareChannel = null
        subscribedUserId = null
        _receivedShares.value = emptyList()
    }

    private fun launchRealtimeSubscription(userId: String) {
        scope.launch {
            channelMutex.withLock {
                // If already subscribed for a different user, close the old channel
                if (shareChannel != null && subscribedUserId != userId) {
                    closeChannelInternal()
                }
                if (shareChannel != null) return@withLock

                val handle = channelProvider.createChannel("shares-received-$userId")
                shareChannel = handle
                subscribedUserId = userId

                // Set up the change flow before subscribing
                handle
                    .postgresChangeFlow(
                        table = "shares",
                        filter = ChangeFilter("to_user_id", userId),
                    ).onEach { action ->
                        handleShareAction(action, userId)
                    }.catch { e ->
                        if (e is CancellationException) throw e
                        // Realtime error — flow terminates; will recover on next subscribe
                    }.launchIn(scope)

                // Subscribe first, then seed — subscribe-then-fetch pattern
                // prevents missed events between fetch and subscribe
                handle.subscribe()

                // Seed with initial fetch after subscription is active
                _receivedShares.value = remote.getReceivedByUserId(userId)
            }
        }
    }

    private fun handleShareAction(
        action: PostgresAction,
        userId: String,
    ) {
        when (action) {
            is PostgresAction.Insert -> {
                val share = action.decodeRecord<Share>()
                // Defensive: verify this share is actually for the subscribed user
                if (share.toUserId == userId) {
                    _receivedShares.value = _receivedShares.value + share
                }
            }
            is PostgresAction.Update -> {
                val share = action.decodeRecord<Share>()
                if (share.toUserId == userId) {
                    _receivedShares.value =
                        _receivedShares.value.map {
                            if (it.id == share.id) share else it
                        }
                }
            }
            is PostgresAction.Delete -> {
                // DELETE payloads may only contain PK if REPLICA IDENTITY is DEFAULT.
                // Use filter-by-absence as a fallback: re-fetch on next observe call.
                // For now, attempt decode and filter by ID.
                try {
                    val old = action.decodeOldRecord<Share>()
                    _receivedShares.value = _receivedShares.value.filter { it.id != old.id }
                } catch (_: Exception) {
                    // If decode fails (REPLICA IDENTITY DEFAULT), trigger a full re-fetch
                    scope.launch {
                        _receivedShares.value = remote.getReceivedByUserId(userId)
                    }
                }
            }
            is PostgresAction.Select -> { /* no-op */ }
        }
    }
}
