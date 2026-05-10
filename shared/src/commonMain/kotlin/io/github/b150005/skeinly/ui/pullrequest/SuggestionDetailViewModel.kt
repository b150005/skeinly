package io.github.b150005.skeinly.ui.pullrequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.ClickActionId
import io.github.b150005.skeinly.data.analytics.Screen
import io.github.b150005.skeinly.domain.chart.ConflictDetector
import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionComment
import io.github.b150005.skeinly.domain.model.User
import io.github.b150005.skeinly.domain.model.toChart
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.ChartRepository
import io.github.b150005.skeinly.domain.repository.ChartVersionRepository
import io.github.b150005.skeinly.domain.repository.PatternRepository
import io.github.b150005.skeinly.domain.repository.SuggestionRepository
import io.github.b150005.skeinly.domain.repository.UserRepository
import io.github.b150005.skeinly.domain.usecase.ApplySuggestionUseCase
import io.github.b150005.skeinly.domain.usecase.CloseSuggestionUseCase
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.GetSuggestionCommentsUseCase
import io.github.b150005.skeinly.domain.usecase.GetSuggestionUseCase
import io.github.b150005.skeinly.domain.usecase.PostSuggestionCommentUseCase
import io.github.b150005.skeinly.domain.usecase.SuggestionObserveScope
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.applyResolutions
import io.github.b150005.skeinly.domain.usecase.toErrorMessage
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
 * wires `ApplySuggestionUseCase` against the SECURITY DEFINER RPC.
 *
 * **Realtime lifecycle.** Opens the per-PR comments channel
 * `pull-request-comments-<prId>` (ADR-014 §7) in `init` via
 * [SuggestionRepository.subscribeToCommentsChannel]; closes it in
 * [onCleared]. The channel keeps the local cache warm so
 * [GetSuggestionCommentsUseCase.observe] emits live updates without a
 * manual refresh.
 *
 * **Author resolution** mirrors `SuggestionListViewModel` —
 * [UserRepository.getByIds] resolves comment author + PR author display
 * names; UI falls back to `label_someone` on cache misses.
 */
data class SuggestionDetailState(
    val suggestion: Suggestion? = null,
    val comments: List<SuggestionComment> = emptyList(),
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
            val pr = suggestion ?: return false
            val current = currentUserId ?: return false
            val owner = targetOwnerId ?: return false
            return pr.canMerge(current, owner)
        }

    /** Derived gate for the close button. Either party may close per ADR-014 §1. */
    val canClose: Boolean
        get() {
            val pr = suggestion ?: return false
            if (pr.status != io.github.b150005.skeinly.domain.model.SuggestionStatus.OPEN) return false
            val current = currentUserId ?: return false
            return current == pr.authorId || current == targetOwnerId
        }
}

sealed interface SuggestionDetailEvent {
    data class CommentDraftChanged(
        val draft: String,
    ) : SuggestionDetailEvent

    data object PostComment : SuggestionDetailEvent

    data object RequestClose : SuggestionDetailEvent

    data object ConfirmClose : SuggestionDetailEvent

    data object DismissCloseConfirmation : SuggestionDetailEvent

    data object RequestMerge : SuggestionDetailEvent

    /**
     * Phase 38.4 — confirm the merge dialog. Routes through `ConflictDetector`
     * to decide between (a) auto-clean direct merge and (b) navigate to
     * `ChartConflictResolutionScreen` for interactive resolution.
     */
    data object ConfirmMerge : SuggestionDetailEvent

    data object DismissMergeConfirmation : SuggestionDetailEvent

    data object ClearError : SuggestionDetailEvent
}

/**
 * One-shot navigation events surfaced to the screen layer (e.g. Snackbar
 * confirmation + back-pop after close lands).
 */
sealed interface SuggestionDetailNavEvent {
    data object PrClosed : SuggestionDetailNavEvent

    /**
     * Phase 38.4 — auto-clean merge succeeded; surface the success Snackbar
     * + pop back to the list. The new revision id is included so analytics
     * or "see new commit in history" links can route to it (no current
     * caller; reserved for forward-compat).
     */
    data class PrMerged(
        val mergedRevisionId: String,
    ) : SuggestionDetailNavEvent

    /**
     * Phase 38.4 — conflicts detected; navigate to the resolution screen
     * carrying the PR id. The resolution screen reloads the snapshots itself
     * so we don't ferry envelopes through the navigation graph.
     */
    data class NavigateToConflictResolution(
        val prId: String,
    ) : SuggestionDetailNavEvent

    /**
     * Phase 24.2c-3 (ADR-017 §3.6) — fired on the success path of
     * [SuggestionDetailEvent.PostComment]. The screen layer listens for
     * this and dispatches
     * [io.github.b150005.skeinly.ui.notifications.NotificationPermissionEvent.TriggerEncountered]
     * with [io.github.b150005.skeinly.notifications.NotificationPromptTrigger.PR_COMMENT_POSTED]
     * so the in-app pre-permission explainer surfaces the first time a user
     * actively engages in collaboration. Keeping the dispatch at the screen
     * layer (rather than wiring [NotificationPermissionPrompter] into this
     * ViewModel) preserves the cross-cutting-concern boundary — the PR
     * detail surface emits a domain signal, and the screen layer composes
     * notification-permission UX on top of it.
     */
    data object CommentPosted : SuggestionDetailNavEvent
}

class SuggestionDetailViewModel(
    private val prId: String,
    private val getSuggestion: GetSuggestionUseCase,
    private val getComments: GetSuggestionCommentsUseCase,
    private val postComment: PostSuggestionCommentUseCase,
    private val closeSuggestion: CloseSuggestionUseCase,
    private val suggestionRepository: SuggestionRepository,
    private val patternRepository: PatternRepository,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    // Phase 38.4 deps. Nullable defaults so existing tests that don't exercise
    // the merge path can construct the ViewModel without supplying them; the
    // RequestMerge path no-ops if any are absent (defense-in-depth — Koin
    // production wiring always provides non-null).
    private val applySuggestion: ApplySuggestionUseCase? = null,
    private val chartVersionRepository: ChartVersionRepository? = null,
    private val chartRepository: ChartRepository? = null,
    // Phase F.4 — nullable + default null preserves existing test compat.
    private val analyticsTracker: AnalyticsTracker? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SuggestionDetailState())
    val state: StateFlow<SuggestionDetailState> = _state.asStateFlow()

    private val _navEvents = Channel<SuggestionDetailNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<SuggestionDetailNavEvent> = _navEvents.receiveAsFlow()

    init {
        // 1. Open the per-PR comments Realtime channel BEFORE seeding the
        //    comment list so any INSERT landing during the seed window
        //    propagates through the channel rather than being missed.
        viewModelScope.launch {
            try {
                suggestionRepository.subscribeToCommentsChannel(prId)
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
                    suggestionRepository.closeCommentsChannel()
                }
            } catch (_: Throwable) {
                // Best-effort cleanup. Timeout / network error / cancellation
                // — the channel will eventually close server-side regardless.
            }
        }
        super.onCleared()
    }

    fun onEvent(event: SuggestionDetailEvent) {
        when (event) {
            is SuggestionDetailEvent.CommentDraftChanged ->
                _state.update { it.copy(commentDraft = event.draft) }

            SuggestionDetailEvent.PostComment -> postCommentInternal()

            SuggestionDetailEvent.RequestClose -> {
                analyticsTracker?.track(
                    AnalyticsEvent.ClickAction(ClickActionId.CloseSuggestion, Screen.SuggestionDetail),
                )
                _state.update { it.copy(pendingCloseConfirmation = true) }
            }

            SuggestionDetailEvent.ConfirmClose -> {
                _state.update { it.copy(pendingCloseConfirmation = false) }
                closeInternal()
            }

            SuggestionDetailEvent.DismissCloseConfirmation ->
                _state.update { it.copy(pendingCloseConfirmation = false) }

            SuggestionDetailEvent.RequestMerge -> {
                analyticsTracker?.track(
                    AnalyticsEvent.ClickAction(ClickActionId.ApplySuggestion, Screen.SuggestionDetail),
                )
                _state.update { it.copy(pendingMergeConfirmation = true) }
            }

            SuggestionDetailEvent.ConfirmMerge -> {
                _state.update { it.copy(pendingMergeConfirmation = false) }
                attemptMerge()
            }

            SuggestionDetailEvent.DismissMergeConfirmation ->
                _state.update { it.copy(pendingMergeConfirmation = false) }

            SuggestionDetailEvent.ClearError ->
                _state.update { it.copy(error = null) }
        }
    }

    private suspend fun loadInitial() {
        when (val result = getSuggestion(prId)) {
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
                        suggestion = pr,
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

    private fun observePrRow(pr: Suggestion) {
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
                SuggestionObserveScope.INCOMING
            } else {
                SuggestionObserveScope.OUTGOING
            }

        getSuggestion
            .observe(prId, ownerId, scope)
            .onEach { fresh ->
                _state.update { it.copy(suggestion = fresh) }
            }.catch { e ->
                if (e is CancellationException) throw e
                _state.update {
                    it.copy(error = ErrorMessage.LoadFailed)
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
                    it.copy(error = ErrorMessage.LoadFailed)
                }
            }.launchIn(viewModelScope)
    }

    private suspend fun resolveUsersForPr(pr: Suggestion) {
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

    private suspend fun resolveUsersForComments(comments: List<SuggestionComment>) {
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
                    // Phase F.4 — engagement signal; no properties (cardinality safe).
                    analyticsTracker?.track(AnalyticsEvent.SuggestionCommented)
                    // Phase 24.2c-3 (ADR-017 §3.6) — engagement-moment signal
                    // for the in-app pre-permission explainer. The screen
                    // layer routes this to NotificationPermissionViewModel.
                    _navEvents.trySend(SuggestionDetailNavEvent.CommentPosted)
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
     *  - Auto-clean: invokes [ApplySuggestionUseCase] with the source-tip
     *    document directly. The conflict-detector returned `isClean = true`
     *    so no user resolution is required.
     *  - Conflicts: emits [SuggestionDetailNavEvent.NavigateToConflictResolution]
     *    so the screen layer pushes [ChartConflictResolutionScreen] for
     *    interactive resolution.
     *
     * Defense-in-depth: if any of the merge dependencies are null (test
     * construction without merge wiring, or an unconfigured offline build),
     * surfaces a Validation error rather than silently no-op'ing.
     */
    private fun attemptMerge() {
        val pr = _state.value.suggestion ?: return
        val merge = applySuggestion
        val chartVersionRepo = chartVersionRepository
        val chartRepo = chartRepository
        if (merge == null || chartVersionRepo == null || chartRepo == null) {
            _state.update {
                it.copy(error = ErrorMessage.RequiresConnectivity)
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isMerging = true) }
            val ancestor =
                try {
                    chartVersionRepo.getRevision(pr.commonAncestorRevisionId)?.toChart()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            val theirs =
                try {
                    chartVersionRepo.getRevision(pr.sourceTipRevisionId)?.toChart()
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
                        error = ErrorMessage.LoadFailed,
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
                when (val result = merge(suggestion = pr, resolvedChart = resolved)) {
                    is UseCaseResult.Success -> {
                        _state.update { it.copy(isMerging = false) }
                        // Phase F.4 / F.5 — this branch is only reachable when
                        // ConflictDetector found ZERO conflicts; the merge
                        // landed without a user resolution step (auto-clean /
                        // fast-forward path). The mutually-exclusive
                        // counterpart with had_conflicts=true lives in
                        // ChartConflictResolutionViewModel.applyAndMerge,
                        // which is the navigation target of `attemptMerge`'s
                        // conflicting branch above. Together the two
                        // ViewModels cover every successful merge transition
                        // exactly once.
                        analyticsTracker?.track(
                            AnalyticsEvent.SuggestionMerged(hadConflicts = false),
                        )
                        _navEvents.trySend(
                            SuggestionDetailNavEvent.PrMerged(
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
                    SuggestionDetailNavEvent.NavigateToConflictResolution(prId = prId),
                )
            }
        }
    }

    private fun closeInternal() {
        val pr = _state.value.suggestion ?: return
        if (_state.value.isClosingPr) return
        viewModelScope.launch {
            _state.update { it.copy(isClosingPr = true) }
            when (val result = closeSuggestion(pr)) {
                is UseCaseResult.Success -> {
                    _state.update {
                        it.copy(
                            isClosingPr = false,
                            suggestion = result.value,
                        )
                    }
                    // Phase F.4 — alpha1 cares about close-vs-merge ratio
                    // (collab loop completion vs abandonment). No properties.
                    analyticsTracker?.track(AnalyticsEvent.SuggestionClosed)
                    _navEvents.trySend(SuggestionDetailNavEvent.PrClosed)
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(isClosingPr = false, error = result.error.toErrorMessage())
                    }
            }
        }
    }
}
