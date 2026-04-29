package io.github.b150005.knitnote.ui.pullrequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.chart.ConflictDetector
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestComment
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.model.toStructuredChart
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.ChartRevisionRepository
import io.github.b150005.knitnote.domain.repository.PatternRepository
import io.github.b150005.knitnote.domain.repository.PullRequestRepository
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import io.github.b150005.knitnote.domain.repository.UserRepository
import io.github.b150005.knitnote.domain.usecase.ClosePullRequestUseCase
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.GetPullRequestCommentsUseCase
import io.github.b150005.knitnote.domain.usecase.GetPullRequestUseCase
import io.github.b150005.knitnote.domain.usecase.MergePullRequestUseCase
import io.github.b150005.knitnote.domain.usecase.PostPullRequestCommentUseCase
import io.github.b150005.knitnote.domain.usecase.PullRequestObserveScope
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.applyResolutions
import io.github.b150005.knitnote.domain.usecase.toErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Phase 38.3 (ADR-014 §6, §8) — single pull-request detail surface.
 *
 * Loads PR + target owner + comments and exposes write actions for posting
 * comments and closing the PR. Merge stays inert per the spec — Phase 38.4
 * wires `MergePullRequestUseCase` against the SECURITY DEFINER RPC.
 *
 * **Realtime lifecycle.** Opens the per-PR comments channel
 * `pull-request-comments-<prId>` (ADR-014 §7) in `init` via
 * [PullRequestRepository.subscribeToCommentsChannel]; closes it in
 * [onCleared]. The channel keeps the local cache warm so
 * [GetPullRequestCommentsUseCase.observe] emits live updates without a
 * manual refresh.
 *
 * **Author resolution** mirrors `PullRequestListViewModel` —
 * [UserRepository.getByIds] resolves comment author + PR author display
 * names; UI falls back to `label_someone` on cache misses.
 */
data class PullRequestDetailState(
    val pullRequest: PullRequest? = null,
    val comments: List<PullRequestComment> = emptyList(),
    val users: Map<String, User> = emptyMap(),
    /** Resolved from `PatternRepository.getById(targetPatternId)`; null until loaded. */
    val targetOwnerId: String? = null,
    val currentUserId: String? = null,
    val commentDraft: String = "",
    val isLoading: Boolean = true,
    val isSendingComment: Boolean = false,
    val isClosingPr: Boolean = false,
    val isMerging: Boolean = false,
    val pendingCloseConfirmation: Boolean = false,
    val pendingMergeConfirmation: Boolean = false,
    val error: ErrorMessage? = null,
) {
    /**
     * Derived gate for the merge button. Phase 38.3 keeps it inert (the
     * Compose / SwiftUI layers disable the button regardless), but the gate
     * is computed centrally so Phase 38.4 just enables the screen-side
     * `enabled` flag.
     *
     * On state, not as a top-level extension property, so the Swift bridge
     * can read it as a regular `Bool` getter rather than a Kt-class function.
     */
    val canMerge: Boolean
        get() {
            val pr = pullRequest ?: return false
            val current = currentUserId ?: return false
            val owner = targetOwnerId ?: return false
            return pr.canMerge(current, owner)
        }

    /** Derived gate for the close button. Either party may close per ADR-014 §1. */
    val canClose: Boolean
        get() {
            val pr = pullRequest ?: return false
            if (pr.status != io.github.b150005.knitnote.domain.model.PullRequestStatus.OPEN) return false
            val current = currentUserId ?: return false
            return current == pr.authorId || current == targetOwnerId
        }
}

sealed interface PullRequestDetailEvent {
    data class CommentDraftChanged(
        val draft: String,
    ) : PullRequestDetailEvent

    data object PostComment : PullRequestDetailEvent

    data object RequestClose : PullRequestDetailEvent

    data object ConfirmClose : PullRequestDetailEvent

    data object DismissCloseConfirmation : PullRequestDetailEvent

    data object RequestMerge : PullRequestDetailEvent

    /**
     * Phase 38.4 — confirm the merge dialog. Routes through `ConflictDetector`
     * to decide between (a) auto-clean direct merge and (b) navigate to
     * `ChartConflictResolutionScreen` for interactive resolution.
     */
    data object ConfirmMerge : PullRequestDetailEvent

    data object DismissMergeConfirmation : PullRequestDetailEvent

    data object ClearError : PullRequestDetailEvent
}

/**
 * One-shot navigation events surfaced to the screen layer (e.g. Snackbar
 * confirmation + back-pop after close lands).
 */
sealed interface PullRequestDetailNavEvent {
    data object PrClosed : PullRequestDetailNavEvent

    /**
     * Phase 38.4 — auto-clean merge succeeded; surface the success Snackbar
     * + pop back to the list. The new revision id is included so analytics
     * or "see new commit in history" links can route to it (no current
     * caller; reserved for forward-compat).
     */
    data class PrMerged(
        val mergedRevisionId: String,
    ) : PullRequestDetailNavEvent

    /**
     * Phase 38.4 — conflicts detected; navigate to the resolution screen
     * carrying the PR id. The resolution screen reloads the snapshots itself
     * so we don't ferry envelopes through the navigation graph.
     */
    data class NavigateToConflictResolution(
        val prId: String,
    ) : PullRequestDetailNavEvent
}

class PullRequestDetailViewModel(
    private val prId: String,
    private val getPullRequest: GetPullRequestUseCase,
    private val getComments: GetPullRequestCommentsUseCase,
    private val postComment: PostPullRequestCommentUseCase,
    private val closePullRequest: ClosePullRequestUseCase,
    private val pullRequestRepository: PullRequestRepository,
    private val patternRepository: PatternRepository,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    // Phase 38.4 deps. Nullable defaults so existing tests that don't exercise
    // the merge path can construct the ViewModel without supplying them; the
    // RequestMerge path no-ops if any are absent (defense-in-depth — Koin
    // production wiring always provides non-null).
    private val mergePullRequest: MergePullRequestUseCase? = null,
    private val chartRevisionRepository: ChartRevisionRepository? = null,
    private val structuredChartRepository: StructuredChartRepository? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(PullRequestDetailState())
    val state: StateFlow<PullRequestDetailState> = _state.asStateFlow()

    private val _navEvents = Channel<PullRequestDetailNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<PullRequestDetailNavEvent> = _navEvents.receiveAsFlow()

    init {
        // 1. Open the per-PR comments Realtime channel BEFORE seeding the
        //    comment list so any INSERT landing during the seed window
        //    propagates through the channel rather than being missed.
        viewModelScope.launch {
            try {
                pullRequestRepository.subscribeToCommentsChannel(prId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Best-effort — local-only mode is a no-op, network failures
                // here just mean the cache stays whatever it was. Errors are
                // not surfaced to the user; observe() still emits from cache.
            }
        }

        // 2. Resolve current user id (cached locally; cheap synchronous-ish call).
        _state.update { it.copy(currentUserId = authRepository.getCurrentUserId()) }

        // 3. Seed the PR + target owner + comments + author display names.
        viewModelScope.launch { loadInitial() }

        // 4. Subscribe to live PR + comment Flows so post-init mutations
        //    (close landing through Realtime, peer comment append) propagate
        //    to the UI without a manual refresh.
        observeComments()
    }

    override fun onCleared() {
        // viewModelScope is already cancelled by the time `onCleared` runs, so
        // a plain `viewModelScope.launch` would be dropped. Spawn a detached
        // coroutine with `NonCancellable` so an external cancel signal cannot
        // race the cleanup, and bound the lifetime with `withTimeout` so a
        // hung Realtime `unsubscribe` (e.g. server timeout, network partition)
        // cannot leak the scope indefinitely. 5s is generous for a websocket
        // unsubscribe round-trip; the cleanup is idempotent on the server side
        // so a forced timeout is safe — the server eventually GCs the channel.
        CoroutineScope(Dispatchers.Default + NonCancellable).launch {
            try {
                withTimeout(timeMillis = 5_000) {
                    pullRequestRepository.closeCommentsChannel()
                }
            } catch (_: Throwable) {
                // Best-effort cleanup. Timeout / network error / cancellation
                // — the channel will eventually close server-side regardless.
            }
        }
        super.onCleared()
    }

    fun onEvent(event: PullRequestDetailEvent) {
        when (event) {
            is PullRequestDetailEvent.CommentDraftChanged ->
                _state.update { it.copy(commentDraft = event.draft) }

            PullRequestDetailEvent.PostComment -> postCommentInternal()

            PullRequestDetailEvent.RequestClose ->
                _state.update { it.copy(pendingCloseConfirmation = true) }

            PullRequestDetailEvent.ConfirmClose -> {
                _state.update { it.copy(pendingCloseConfirmation = false) }
                closeInternal()
            }

            PullRequestDetailEvent.DismissCloseConfirmation ->
                _state.update { it.copy(pendingCloseConfirmation = false) }

            PullRequestDetailEvent.RequestMerge ->
                _state.update { it.copy(pendingMergeConfirmation = true) }

            PullRequestDetailEvent.ConfirmMerge -> {
                _state.update { it.copy(pendingMergeConfirmation = false) }
                attemptMerge()
            }

            PullRequestDetailEvent.DismissMergeConfirmation ->
                _state.update { it.copy(pendingMergeConfirmation = false) }

            PullRequestDetailEvent.ClearError ->
                _state.update { it.copy(error = null) }
        }
    }

    private suspend fun loadInitial() {
        when (val result = getPullRequest(prId)) {
            is UseCaseResult.Failure -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = result.error.toErrorMessage(),
                    )
                }
                return
            }
            is UseCaseResult.Success -> {
                val pr = result.value
                // Explicit try/catch (not runCatching) because runCatching
                // swallows CancellationException — losing targetOwnerId for
                // the session means canMerge / canClose stay permanently
                // false even after a successful re-fetch, and the merge gate
                // is the load-bearing part of the detail surface. If the
                // call is cancelled, propagate so the structured-concurrency
                // unwind happens correctly. Other exceptions degrade to
                // null targetOwnerId — UI then falls back to "merge button
                // disabled" which is the same state as a not-yet-resolved
                // owner, and the user can re-enter the screen to retry.
                val targetPattern =
                    try {
                        patternRepository.getById(pr.targetPatternId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                _state.update {
                    it.copy(
                        pullRequest = pr,
                        targetOwnerId = targetPattern?.ownerId,
                        isLoading = false,
                    )
                }
                resolveUsersForPr(pr)
                // Subscribe to live PR row updates so a status flip landing
                // through Realtime (close echo, merge echo) lands in state.
                observePrRow(pr)
            }
        }
    }

    private fun observePrRow(pr: PullRequest) {
        // Pick the observe scope based on the user's role: target owner → INCOMING,
        // source author → OUTGOING. A user who is both (rare — owns both source
        // and target patterns) defaults to INCOMING since that's the merge surface.
        val currentId = _state.value.currentUserId ?: return
        val targetOwnerId = _state.value.targetOwnerId
        val ownerId =
            when {
                targetOwnerId == currentId -> currentId
                pr.authorId == currentId -> currentId
                else -> return // Caller has no role on this PR; observe Flow would be empty.
            }
        val scope =
            if (targetOwnerId == currentId) {
                PullRequestObserveScope.INCOMING
            } else {
                PullRequestObserveScope.OUTGOING
            }

        getPullRequest
            .observe(prId, ownerId, scope)
            .onEach { fresh ->
                _state.update { it.copy(pullRequest = fresh) }
            }.catch { e ->
                if (e is CancellationException) throw e
                _state.update {
                    it.copy(error = ErrorMessage.Raw(e.message ?: "Failed to observe pull request"))
                }
            }.launchIn(viewModelScope)
    }

    private fun observeComments() {
        getComments
            .observe(prId)
            .onEach { comments ->
                _state.update { it.copy(comments = comments) }
                resolveUsersForComments(comments)
            }.catch { e ->
                if (e is CancellationException) throw e
                _state.update {
                    it.copy(error = ErrorMessage.Raw(e.message ?: "Failed to load comments"))
                }
            }.launchIn(viewModelScope)
    }

    private suspend fun resolveUsersForPr(pr: PullRequest) {
        val authorId = pr.authorId ?: return
        if (_state.value.users.containsKey(authorId)) return
        // Explicit try/catch (not runCatching) so CancellationException
        // propagates per project convention (Phase 36.3 / 37.x precedent).
        // User-display-name resolution failure is non-fatal — UI falls back
        // to label_someone — but the cancellation case must still unwind.
        val user =
            try {
                userRepository.getById(authorId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return
        _state.update { current -> current.copy(users = current.users + (authorId to user)) }
    }

    private suspend fun resolveUsersForComments(comments: List<PullRequestComment>) {
        val newAuthorIds = comments.mapNotNull { it.authorId }.distinct() - _state.value.users.keys
        if (newAuthorIds.isEmpty()) return
        val resolved =
            try {
                userRepository.getByIds(newAuthorIds).associateBy { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
        _state.update { current -> current.copy(users = current.users + resolved) }
    }

    private fun postCommentInternal() {
        val draft = _state.value.commentDraft
        if (draft.isBlank() || _state.value.isSendingComment) return
        viewModelScope.launch {
            _state.update { it.copy(isSendingComment = true) }
            when (val result = postComment(prId, draft)) {
                is UseCaseResult.Success -> {
                    _state.update {
                        it.copy(commentDraft = "", isSendingComment = false)
                    }
                    // Optimistic local cache write — the comment Flow may not
                    // emit until the Realtime echo arrives in local-only mode
                    // it's never going to, so seed the comments list now.
                    _state.update { current ->
                        if (current.comments.any { it.id == result.value.id }) {
                            current
                        } else {
                            current.copy(comments = current.comments + result.value)
                        }
                    }
                    resolveUsersForComments(listOf(result.value))
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(isSendingComment = false, error = result.error.toErrorMessage())
                    }
            }
        }
    }

    /**
     * Phase 38.4 (ADR-014 §4 §5 §6) — confirm-merge path. Loads the three
     * revision snapshots, runs [ConflictDetector], and either:
     *  - Auto-clean: invokes [MergePullRequestUseCase] with the source-tip
     *    document directly. The conflict-detector returned `isClean = true`
     *    so no user resolution is required.
     *  - Conflicts: emits [PullRequestDetailNavEvent.NavigateToConflictResolution]
     *    so the screen layer pushes [ChartConflictResolutionScreen] for
     *    interactive resolution.
     *
     * Defense-in-depth: if any of the merge dependencies are null (test
     * construction without merge wiring, or an unconfigured offline build),
     * surfaces a Validation error rather than silently no-op'ing.
     */
    private fun attemptMerge() {
        val pr = _state.value.pullRequest ?: return
        val merge = mergePullRequest
        val chartRevisionRepo = chartRevisionRepository
        val chartRepo = structuredChartRepository
        if (merge == null || chartRevisionRepo == null || chartRepo == null) {
            _state.update {
                it.copy(error = ErrorMessage.Raw("Merge is unavailable in offline-only mode"))
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isMerging = true) }
            val ancestor =
                try {
                    chartRevisionRepo.getRevision(pr.commonAncestorRevisionId)?.toStructuredChart()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            val theirs =
                try {
                    chartRevisionRepo.getRevision(pr.sourceTipRevisionId)?.toStructuredChart()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            val mine =
                try {
                    chartRepo.getByPatternId(pr.targetPatternId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            if (ancestor == null || theirs == null || mine == null) {
                _state.update {
                    it.copy(
                        isMerging = false,
                        error = ErrorMessage.Raw("Could not load chart revisions for this pull request"),
                    )
                }
                return@launch
            }
            val report = ConflictDetector.detect(ancestor = ancestor, theirs = theirs, mine = mine)
            if (report.isClean) {
                // Auto-clean merge: apply autoFromTheirs + autoLayerFromTheirs
                // on top of `mine` (target tip). Passing `theirs` raw would
                // silently drop any target-side edits that landed after the
                // fork point — code review HIGH-1 fix. autoFromMine is the
                // identity over `mine` (those cells already match), so we
                // don't need to apply it; the empty conflict-resolution maps
                // tell `applyResolutions` there are no contested picks.
                val resolved =
                    applyResolutions(
                        mine = mine,
                        autoFromTheirs = report.autoFromTheirs,
                        conflictPicks = emptyMap(),
                        autoLayerFromTheirs = report.autoLayerFromTheirs,
                        layerConflictPicks = emptyMap(),
                        theirs = theirs,
                        ancestor = ancestor,
                    )
                when (val result = merge(pullRequest = pr, resolvedChart = resolved)) {
                    is UseCaseResult.Success -> {
                        _state.update { it.copy(isMerging = false) }
                        _navEvents.trySend(
                            PullRequestDetailNavEvent.PrMerged(
                                mergedRevisionId = result.value.mergedRevisionId,
                            ),
                        )
                    }
                    is UseCaseResult.Failure ->
                        _state.update {
                            it.copy(isMerging = false, error = result.error.toErrorMessage())
                        }
                }
            } else {
                _state.update { it.copy(isMerging = false) }
                _navEvents.trySend(
                    PullRequestDetailNavEvent.NavigateToConflictResolution(prId = prId),
                )
            }
        }
    }

    private fun closeInternal() {
        val pr = _state.value.pullRequest ?: return
        if (_state.value.isClosingPr) return
        viewModelScope.launch {
            _state.update { it.copy(isClosingPr = true) }
            when (val result = closePullRequest(pr)) {
                is UseCaseResult.Success -> {
                    _state.update {
                        it.copy(
                            isClosingPr = false,
                            pullRequest = result.value,
                        )
                    }
                    _navEvents.trySend(PullRequestDetailNavEvent.PrClosed)
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(isClosingPr = false, error = result.error.toErrorMessage())
                    }
            }
        }
    }
}
