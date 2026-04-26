package io.github.b150005.knitnote.data.sync

import io.github.b150005.knitnote.data.local.LocalChartRevisionDataSource
import io.github.b150005.knitnote.data.local.LocalPatternDataSource
import io.github.b150005.knitnote.data.local.LocalProgressDataSource
import io.github.b150005.knitnote.data.local.LocalProjectDataSource
import io.github.b150005.knitnote.data.local.LocalProjectSegmentDataSource
import io.github.b150005.knitnote.data.local.LocalPullRequestDataSource
import io.github.b150005.knitnote.data.realtime.ChangeFilter
import io.github.b150005.knitnote.data.realtime.ChannelHandle
import io.github.b150005.knitnote.data.realtime.RealtimeChannelProvider
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.ChartRevision
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.model.PullRequest
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
    private val localProjectSegment: LocalProjectSegmentDataSource,
    private val authRepository: AuthRepository,
    private val scope: CoroutineScope,
    private val logger: SyncLogger = DefaultSyncLogger,
    private val isOnline: StateFlow<Boolean>? = null,
    private val config: RealtimeConfig = RealtimeConfig(),
    private val random: Random = Random.Default,
    // Phase 37.1 (ADR-013 §8): chart-revisions-<ownerId> 5th channel.
    // Optional with `null` default so existing test call-sites that don't
    // exercise revisions continue to construct this manager unchanged.
    private val localChartRevision: LocalChartRevisionDataSource? = null,
    // Phase 38.1 (ADR-014 §7): pull-requests-incoming-<ownerId> + outgoing
    // 6th + 7th channels. Optional with `null` default for backward compat
    // with existing test call-sites — same pattern as chart_revisions above.
    // The dynamic per-PR comments channel (pull-request-comments-<prId>)
    // lands in Phase 38.3 alongside PullRequestDetailScreen and is NOT
    // managed by RealtimeSyncManager — it's opened/closed on view lifecycle.
    private val localPullRequest: LocalPullRequestDataSource? = null,
) {
    private var projectChannel: ChannelHandle? = null
    private var progressChannel: ChannelHandle? = null
    private var patternChannel: ChannelHandle? = null
    private var projectSegmentChannel: ChannelHandle? = null
    private var chartRevisionChannel: ChannelHandle? = null
    private var pullRequestIncomingChannel: ChannelHandle? = null
    private var pullRequestOutgoingChannel: ChannelHandle? = null
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
            subscribeToProjectSegments(ownerId)
            if (localChartRevision != null) subscribeToChartRevisions(ownerId)
            if (localPullRequest != null) {
                subscribeToPullRequestsOutgoing(ownerId)
                subscribeToPullRequestsIncoming(ownerId)
            }
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
        projectSegmentChannel?.unsubscribe()
        projectSegmentChannel = null
        chartRevisionChannel?.unsubscribe()
        chartRevisionChannel = null
        pullRequestIncomingChannel?.unsubscribe()
        pullRequestIncomingChannel = null
        pullRequestOutgoingChannel?.unsubscribe()
        pullRequestOutgoingChannel = null
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

    private suspend fun subscribeToProjectSegments(ownerId: String) {
        val handle = channelProvider.createChannel("project-segments-$ownerId")
        projectSegmentChannel = handle

        handle
            .postgresChangeFlow(
                table = "project_segments",
                filter = ChangeFilter("owner_id", ownerId),
            ).onEach { action ->
                handleProjectSegmentAction(action)
            }.catch { e ->
                if (e is CancellationException) throw e
                logger.log(TAG, "Channel flow error on project_segments", e)
                scheduleRetry(ownerId)
            }.launchIn(scope)

        handle.subscribe()
    }

    private suspend fun subscribeToChartRevisions(ownerId: String) {
        val handle = channelProvider.createChannel("chart-revisions-$ownerId")
        chartRevisionChannel = handle

        handle
            .postgresChangeFlow(
                table = "chart_revisions",
                filter = ChangeFilter("owner_id", ownerId),
            ).onEach { action ->
                handleChartRevisionAction(action)
            }.catch { e ->
                if (e is CancellationException) throw e
                logger.log(TAG, "Channel flow error on chart_revisions", e)
                scheduleRetry(ownerId)
            }.launchIn(scope)

        handle.subscribe()
    }

    /**
     * Outgoing PRs the user authored. Server-side filter `author_id eq ownerId`
     * narrows the broadcast cleanly — RLS would also restrict to participant
     * visibility, but with the eq filter the server doesn't even broadcast
     * incoming PRs to this channel (Realtime publishes through the union of
     * RLS visibility AND any client filter, so the eq filter is the tighter
     * constraint here).
     */
    private suspend fun subscribeToPullRequestsOutgoing(ownerId: String) {
        val handle = channelProvider.createChannel("pull-requests-outgoing-$ownerId")
        pullRequestOutgoingChannel = handle

        handle
            .postgresChangeFlow(
                table = "pull_requests",
                filter = ChangeFilter("author_id", ownerId),
            ).onEach { action ->
                handlePullRequestAction(action)
            }.catch { e ->
                if (e is CancellationException) throw e
                logger.log(TAG, "Channel flow error on pull_requests outgoing", e)
                scheduleRetry(ownerId)
            }.launchIn(scope)

        handle.subscribe()
    }

    /**
     * Incoming PRs targeting any of the user's patterns. Cannot use a single-
     * eq [ChangeFilter] for "target_pattern_id IN (patterns I own)" — the
     * filter API only supports equality. Subscribed without a client-side
     * filter; RLS scopes the broadcast to PRs where the user is participant
     * (author OR target owner) per migration 016 — the same union the
     * outgoing channel sees, just differently labeled. The local handler
     * upserts unconditionally; the [PullRequest.id] PRIMARY KEY makes
     * outgoing-PR events arriving on both channels idempotent. Cost: each
     * outgoing PR change fires once on each channel (2x bandwidth on
     * outgoing events only); each incoming PR change fires once. Acceptable
     * given the v1 channel budget — revisit consolidation in Phase 39 if
     * connection caps become tight.
     */
    private suspend fun subscribeToPullRequestsIncoming(ownerId: String) {
        val handle = channelProvider.createChannel("pull-requests-incoming-$ownerId")
        pullRequestIncomingChannel = handle

        handle
            .postgresChangeFlow(
                table = "pull_requests",
                filter = null,
            ).onEach { action ->
                handlePullRequestAction(action)
            }.catch { e ->
                if (e is CancellationException) throw e
                logger.log(TAG, "Channel flow error on pull_requests incoming", e)
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

    private suspend fun handleProjectSegmentAction(action: PostgresAction) {
        when (action) {
            is PostgresAction.Insert -> {
                val segment = action.decodeRecord<ProjectSegment>()
                if (isKnownProject(segment.projectId)) {
                    localProjectSegment.upsert(segment)
                }
            }
            is PostgresAction.Update -> {
                val segment = action.decodeRecord<ProjectSegment>()
                if (isKnownProject(segment.projectId)) {
                    localProjectSegment.upsert(segment)
                }
            }
            is PostgresAction.Delete -> {
                // Best-effort extract id from the old record; delete may race with
                // a local reset that already cleared the row, in which case the
                // local datasource silently no-ops.
                val old = action.decodeOldRecord<ProjectSegment>()
                localProjectSegment.delete(old.id)
            }
            is PostgresAction.Select -> { /* no-op */ }
        }
    }

    /**
     * Revisions are append-only at RLS — the policies in migration 015 permit
     * SELECT + INSERT only. UPDATE events are therefore impossible.
     *
     * DELETE events DO occur via `ON DELETE CASCADE` on `chart_revisions.pattern_id`:
     * when a pattern is deleted, Postgres cascades the delete to every revision
     * row and emits a Realtime DELETE event for each. We mirror that to local
     * by clearing the revision rows whose `pattern_id` matches the deleted
     * pattern. A single CASCADE delete on the pattern fires one DELETE per
     * revision row — calling `deleteByPatternId` on every event is wasteful
     * but idempotent (subsequent calls on the same `patternId` are no-ops),
     * and avoids tracking which row triggered the cascade. The pattern-side
     * `handlePatternAction` Delete already removes the parent row locally; this
     * handler is the explicit revision-side cleanup.
     */
    private suspend fun handleChartRevisionAction(action: PostgresAction) {
        val ds = localChartRevision ?: return
        when (action) {
            is PostgresAction.Insert -> {
                val revision = action.decodeRecord<ChartRevision>()
                ds.upsert(revision)
            }
            is PostgresAction.Delete -> {
                // CASCADE delete from pattern. Decode the old record to recover
                // pattern_id, then bulk-clear local revisions for that pattern.
                val old = action.decodeOldRecord<ChartRevision>()
                ds.deleteByPatternId(old.patternId)
            }
            is PostgresAction.Update,
            is PostgresAction.Select,
            -> { /* no-op: RLS forbids UPDATE; SELECT is not an event */ }
        }
    }

    /**
     * Pull request Realtime handler (Phase 38.1, ADR-014 §7).
     *
     * INSERT and UPDATE both upsert via the same path — the local
     * [LocalPullRequestDataSource.upsert] uses INSERT OR REPLACE on `id` so
     * a re-arrived event from the dual-channel subscription (outgoing PR
     * delivered through both incoming + outgoing channels) is a silent
     * overwrite with the same row.
     *
     * DELETE: there is no DELETE policy in migration 016, so application
     * code never produces DELETE events directly. The only deletion path is
     * CASCADE on pattern delete, which Postgres emits as one DELETE event
     * per cascaded PR row. Each event carries the deleted PR id in the
     * `old` record, and we clear that single row via [deleteById] — NOT a
     * bulk-clear by `pattern_id`, because a PR sits at the join of two
     * patterns (source + target) and each cascade fires its own per-row
     * event. The chart_revisions handler bulk-clears via `deleteByPatternId`
     * because a revision's parent is a single pattern; the asymmetry is
     * deliberate, not an oversight.
     */
    private suspend fun handlePullRequestAction(action: PostgresAction) {
        val ds = localPullRequest ?: return
        when (action) {
            is PostgresAction.Insert -> {
                val pr = action.decodeRecord<PullRequest>()
                ds.upsert(pr)
            }
            is PostgresAction.Update -> {
                val pr = action.decodeRecord<PullRequest>()
                ds.upsert(pr)
            }
            is PostgresAction.Delete -> {
                val old = action.decodeOldRecord<PullRequest>()
                ds.deleteById(old.id)
            }
            is PostgresAction.Select -> { /* no-op */ }
        }
    }

    companion object {
        private const val TAG = "RealtimeSyncManager"
    }
}
