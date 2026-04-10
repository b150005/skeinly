package com.knitnote.data.sync

import com.knitnote.data.local.LocalPatternDataSource
import com.knitnote.data.local.LocalProgressDataSource
import com.knitnote.data.local.LocalProjectDataSource
import com.knitnote.domain.model.AuthState
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Progress
import com.knitnote.domain.model.Project
import com.knitnote.domain.repository.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeOldRecord
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RealtimeSyncManager(
    private val supabaseClient: SupabaseClient,
    private val localProject: LocalProjectDataSource,
    private val localProgress: LocalProgressDataSource,
    private val localPattern: LocalPatternDataSource,
    private val authRepository: AuthRepository,
    private val scope: CoroutineScope,
) {

    private var projectChannel: RealtimeChannel? = null
    private var progressChannel: RealtimeChannel? = null
    private var patternChannel: RealtimeChannel? = null
    private var authObserverJob: Job? = null
    private val channelMutex = Mutex()

    fun start() {
        authObserverJob?.cancel()
        authObserverJob = authRepository.observeAuthState()
            .onEach { state ->
                when (state) {
                    is AuthState.Authenticated -> subscribe(state.userId)
                    is AuthState.Unauthenticated,
                    is AuthState.Error -> unsubscribe()
                    is AuthState.Loading -> { /* wait */ }
                }
            }
            .launchIn(scope)
    }

    suspend fun stop() {
        authObserverJob?.cancel()
        authObserverJob = null
        unsubscribe()
    }

    internal suspend fun subscribe(ownerId: String) = channelMutex.withLock {
        unsubscribeInternal()
        subscribeToProjects(ownerId)
        subscribeToProgress(ownerId)
        subscribeToPatterns(ownerId)
    }

    internal suspend fun unsubscribe() = channelMutex.withLock {
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

    private suspend fun subscribeToProjects(ownerId: String) {
        val channel = supabaseClient.channel("projects-$ownerId")
        projectChannel = channel

        channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "projects"
            filter("owner_id", FilterOperator.EQ, ownerId)
        }.onEach { action ->
            handleProjectAction(action)
        }.catch { e ->
            if (e is CancellationException) throw e
            // Realtime decode/handling error — log and continue
            // The flow terminates here; a re-subscribe on next auth event will recover
        }.launchIn(scope)

        channel.subscribe()
    }

    private suspend fun subscribeToProgress(ownerId: String) {
        val channel = supabaseClient.channel("progress-$ownerId")
        progressChannel = channel

        channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "progress"
        }.onEach { action ->
            handleProgressAction(action)
        }.catch { e ->
            if (e is CancellationException) throw e
            // Realtime decode/handling error — log and continue
        }.launchIn(scope)

        channel.subscribe()
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

    private suspend fun subscribeToPatterns(ownerId: String) {
        val channel = supabaseClient.channel("patterns-$ownerId")
        patternChannel = channel

        channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "patterns"
            filter("owner_id", FilterOperator.EQ, ownerId)
        }.onEach { action ->
            handlePatternAction(action)
        }.catch { e ->
            if (e is CancellationException) throw e
        }.launchIn(scope)

        channel.subscribe()
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

    private suspend fun isKnownProject(projectId: String): Boolean {
        return localProject.getById(projectId) != null
    }
}
