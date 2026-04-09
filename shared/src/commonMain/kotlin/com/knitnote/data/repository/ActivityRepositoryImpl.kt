package com.knitnote.data.repository

import com.knitnote.data.remote.RemoteActivityDataSource
import com.knitnote.domain.model.Activity
import com.knitnote.domain.repository.ActivityRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
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
 * Remote-only Activity repository with Realtime subscription.
 * Call [closeChannel] on user logout.
 */
class ActivityRepositoryImpl(
    private val remote: RemoteActivityDataSource,
    private val supabaseClient: SupabaseClient,
    private val scope: CoroutineScope,
) : ActivityRepository {

    private var activityChannel: RealtimeChannel? = null
    private var subscribedUserId: String? = null
    private val channelMutex = Mutex()
    private val _activities = MutableStateFlow<List<Activity>>(emptyList())

    override suspend fun getByUserId(userId: String): List<Activity> =
        remote.getByUserId(userId)

    override fun observeByUserId(userId: String): Flow<List<Activity>> {
        launchRealtimeSubscription(userId)
        return _activities.map { activities ->
            activities.filter { it.userId == userId }
        }
    }

    override suspend fun create(activity: Activity): Activity =
        remote.insert(activity)

    /**
     * Unsubscribe from the Realtime channel and clear cached state.
     */
    override suspend fun closeChannel() = channelMutex.withLock {
        closeChannelInternal()
    }

    private suspend fun closeChannelInternal() {
        activityChannel?.unsubscribe()
        activityChannel = null
        subscribedUserId = null
        _activities.value = emptyList()
    }

    private fun launchRealtimeSubscription(userId: String) {
        scope.launch {
            channelMutex.withLock {
                if (activityChannel != null && subscribedUserId != userId) {
                    closeChannelInternal()
                }
                if (activityChannel != null) return@withLock

                val channel = supabaseClient.channel("activities-$userId")
                activityChannel = channel
                subscribedUserId = userId

                // Set up change flow before subscribing
                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "activities"
                    filter("user_id", FilterOperator.EQ, userId)
                }.onEach { action ->
                    handleActivityAction(action, userId)
                }.catch { e ->
                    if (e is CancellationException) throw e
                }.launchIn(scope)

                // Subscribe first, then seed
                channel.subscribe()
                _activities.value = remote.getByUserId(userId)
            }
        }
    }

    private fun handleActivityAction(action: PostgresAction, userId: String) {
        when (action) {
            is PostgresAction.Insert -> {
                val activity = action.decodeRecord<Activity>()
                if (activity.userId == userId) {
                    // Prepend (newest first) — atomic update
                    _activities.update { current -> listOf(activity) + current }
                }
            }
            is PostgresAction.Update,
            is PostgresAction.Delete,
            is PostgresAction.Select -> { /* Activities are append-only */ }
        }
    }
}
