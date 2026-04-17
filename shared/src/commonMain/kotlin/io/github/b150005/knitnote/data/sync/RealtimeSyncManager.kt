package io.github.b150005.knitnote.data.sync

import io.github.b150005.knitnote.data.local.LocalPatternDataSource
import io.github.b150005.knitnote.data.local.LocalProgressDataSource
import io.github.b150005.knitnote.data.local.LocalProjectDataSource
import io.github.b150005.knitnote.data.realtime.ChangeFilter
import io.github.b150005.knitnote.data.realtime.ChannelHandle
import io.github.b150005.knitnote.data.realtime.RealtimeChannelProvider
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.decodeOldRecord
import io.github.jan.supabase.realtime.decodeRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.random.Random

class RealtimeSyncManager(
    private val channelProvider: RealtimeChannelProvider,
    private val localProject: LocalProjectDataSource,
    private val localProgress: LocalProgressDataSource,
    private val localPattern: LocalPatternDataSource,
    private val authRepository: AuthRepository,
    private val scope: CoroutineScope,
    private val logger: SyncLogger = DefaultSyncLogger,
    private val isOnline: StateFlow<Boolean>? = null,
    private val config: RealtimeConfig = RealtimeConfig(),
    private val random: Random = Random.Default,
) {
    private var projectChannel: ChannelHandle? = null
    private var progressChannel: ChannelHandle? = null
    private var patternChannel: ChannelHandle? = null
    private var authObserverJob: Job? = null
    private var connectivityJob: Job? = null
    private var retryJob: Job? = null
    private val channelMutex = Mutex()

    internal var lastOwnerId: String? = null
        private set
    internal var retryCount: Int = 0
        private set

    fun start() {
        authObserverJob?.cancel()
        authObserverJob =
            authRepository
                .observeAuthState()
                .onEach { state ->
                    when (state) {
                        is AuthState.Authenticated -> {
                            retryCount = 0
                            subscribe(state.userId)
                        }
                        is AuthState.Unauthenticated,
                        is AuthState.Error,
                        -> unsubscribe()
                        is AuthState.Loading -> { /* wait */ }
                    }
                }.launchIn(scope)

        connectivityJob?.cancel()
        connectivityJob =
            isOnline
                ?.onEach { online ->
                    if (online) {
                        lastOwnerId?.let { ownerId ->
                            retryCount = 0
                            subscribe(ownerId)
                        }
                    }
                }?.launchIn(scope)
    }

    suspend fun stop() {
        authObserverJob?.cancel()
        authObserverJob = null
        connectivityJob?.cancel()
        connectivityJob = null
        retryJob?.cancel()
        retryJob = null
        unsubscribe()
    }

    internal suspend fun subscribe(ownerId: String) =
        channelMutex.withLock {
            retryJob?.cancel()
            retryJob = null
            unsubscribeInternal()
            lastOwnerId = ownerId
            subscribeToProjects(ownerId)
            subscribeToProgress(ownerId)
            subscribeToPatterns(ownerId)
        }

    internal suspend fun unsubscribe() =
        channelMutex.withLock {
            unsubscribeInternal()
        }

    private suspend fun unsubscribeInternal() {
        projectChannel?.unsubscribe()
        projectChannel = null
        progressChannel?.unsubscribe()
        progressChannel = null
        patternChannel?.unsubscribe()
        patternChannel = null
    }

    /**
     * Schedule a retry with exponential backoff. Synchronized via [channelMutex]
     * to prevent concurrent [.catch] blocks from racing on [retryCount]/[retryJob].
     * The [subscribe] call inside the launched coroutine acquires [channelMutex]
     * separately — no deadlock since this function releases the lock first.
     */
    private suspend fun scheduleRetry(ownerId: String) =
        channelMutex.withLock {
            // If a retry is already in flight, skip — avoids triple-increment
            // when all 3 channels fail simultaneously from the same network drop.
            if (retryJob?.isActive == true) return
            if (retryCount >= config.maxRetries) {
                logger.log(TAG, "Max retries ($retryCount) reached, waiting for connectivity/auth event")
                return
            }
            val currentRetry = retryCount++
            retryJob =
                scope.launch {
                    val backoff = calculateBackoff(currentRetry)
                    delay(backoff)
                    // Guard: if the user changed (e.g., sign-out/sign-in during backoff),
                    // skip this retry. RLS enforces data isolation server-side regardless.
                    if (lastOwnerId == ownerId) {
                        subscribe(ownerId)
                    }
                }
        }

    private fun calculateBackoff(retryRound: Int): Long {
        val baseDelay = config.baseDelayMs * (1L shl retryRound.coerceAtMost(20))
        val capped = min(baseDelay, config.maxDelayMs)
        val jitter = (capped * config.jitterFactor * random.nextDouble()).toLong()
        return capped + jitter
    }

    private suspend fun subscribeToProjects(ownerId: String) {
        val handle = channelProvider.createChannel("projects-$ownerId")
        projectChannel = handle

        handle
            .postgresChangeFlow(
                table = "projects",
                filter = ChangeFilter("owner_id", ownerId),
            ).onEach { action ->
                handleProjectAction(action)
            }.catch { e ->
                if (e is CancellationException) throw e
                logger.log(TAG, "Channel flow error on projects", e)
                scheduleRetry(ownerId)
            }.launchIn(scope)

        handle.subscribe()
    }

    private suspend fun subscribeToProgress(ownerId: String) {
        val handle = channelProvider.createChannel("progress-$ownerId")
        progressChannel = handle

        handle
            .postgresChangeFlow(
                table = "progress",
                filter = ChangeFilter("owner_id", ownerId),
            ).onEach { action ->
                handleProgressAction(action)
            }.catch { e ->
                if (e is CancellationException) throw e
                logger.log(TAG, "Channel flow error on progress", e)
                scheduleRetry(ownerId)
            }.launchIn(scope)

        handle.subscribe()
    }

    private suspend fun subscribeToPatterns(ownerId: String) {
        val handle = channelProvider.createChannel("patterns-$ownerId")
        patternChannel = handle

        handle
            .postgresChangeFlow(
                table = "patterns",
                filter = ChangeFilter("owner_id", ownerId),
            ).onEach { action ->
                handlePatternAction(action)
            }.catch { e ->
                if (e is CancellationException) throw e
                logger.log(TAG, "Channel flow error on patterns", e)
                scheduleRetry(ownerId)
            }.launchIn(scope)

        handle.subscribe()
    }

    private suspend fun handleProjectAction(action: PostgresAction) {
        when (action) {
            is PostgresAction.Insert -> {
                val project = action.decodeRecord<Project>()
                localProject.upsert(project)
            }
            is PostgresAction.Update -> {
                val remote = action.decodeRecord<Project>()
                val local = localProject.getById(remote.id)
                // ADR-003: higher currentRow wins (knitters don't un-knit rows)
                if (local != null && local.currentRow > remote.currentRow) return
                localProject.upsert(remote)
            }
            is PostgresAction.Delete -> {
                val old = action.decodeOldRecord<Project>()
                localProject.delete(old.id)
            }
            is PostgresAction.Select -> { /* no-op */ }
        }
    }

    private suspend fun handlePatternAction(action: PostgresAction) {
        when (action) {
            is PostgresAction.Insert -> {
                val pattern = action.decodeRecord<Pattern>()
                localPattern.upsert(pattern)
            }
            is PostgresAction.Update -> {
                val pattern = action.decodeRecord<Pattern>()
                localPattern.upsert(pattern)
            }
            is PostgresAction.Delete -> {
                val old = action.decodeOldRecord<Pattern>()
                localPattern.delete(old.id)
            }
            is PostgresAction.Select -> { /* no-op */ }
        }
    }

    private suspend fun handleProgressAction(action: PostgresAction) {
        when (action) {
            is PostgresAction.Insert -> {
                val progress = action.decodeRecord<Progress>()
                if (isKnownProject(progress.projectId)) {
                    localProgress.upsert(progress)
                }
            }
            is PostgresAction.Update -> {
                val progress = action.decodeRecord<Progress>()
                if (isKnownProject(progress.projectId)) {
                    localProgress.upsert(progress)
                }
            }
            is PostgresAction.Delete -> {
                val old = action.decodeOldRecord<Progress>()
                localProgress.delete(old.id)
            }
            is PostgresAction.Select -> { /* no-op */ }
        }
    }

    private suspend fun isKnownProject(projectId: String): Boolean = localProject.getById(projectId) != null

    companion object {
        private const val TAG = "RealtimeSyncManager"
    }
}
