package io.github.b150005.knitnote.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.data.analytics.AnalyticsEvents
import io.github.b150005.knitnote.data.analytics.AnalyticsTracker
import io.github.b150005.knitnote.domain.model.ChartBranch
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.model.SegmentState
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.ChartBranchRepository
import io.github.b150005.knitnote.domain.repository.PatternRepository
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.MarkRowSegmentsDoneUseCase
import io.github.b150005.knitnote.domain.usecase.MarkSegmentDoneUseCase
import io.github.b150005.knitnote.domain.usecase.ObserveProjectSegmentsUseCase
import io.github.b150005.knitnote.domain.usecase.ObserveStructuredChartUseCase
import io.github.b150005.knitnote.domain.usecase.OpenPullRequestUseCase
import io.github.b150005.knitnote.domain.usecase.ToggleSegmentStateUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Deterministic key for a segment in the overlay map. Absence of a key means
 * the segment is in the implicit `todo` state (ADR-010 §2).
 */
data class SegmentKey(
    val layerId: String,
    val x: Int,
    val y: Int,
)

data class ChartViewerState(
    val chart: StructuredChart? = null,
    val isLoading: Boolean = true,
    val hiddenLayerIds: Set<String> = emptySet(),
    val errorMessage: ErrorMessage? = null,
    /**
     * Per-segment progress overlay. Map-based truth model — absence ⇒ `todo`.
     * Populated only when [projectId] is non-null (viewer opened from a project
     * context); viewers opened for bare pattern inspection stay at an empty map
     * and the overlay paints nothing.
     */
    val segments: Map<SegmentKey, SegmentState> = emptyMap(),
    /**
     * True for `POLAR_ROUND` charts. Phase 34 ships rect-grid only per PRD AC-1.4;
     * the viewer paints no overlay and shows an inline notice.
     */
    val isPolar: Boolean = false,
    // Phase 38.4.1 (ADR-014 §6) — Open pull request entry. Resolved best-effort
    // after the chart loads; absence of any field deactivates the gate without
    // disrupting the rest of the viewer surface.
    val pattern: Pattern? = null,
    /**
     * Source branch whose tip equals the loaded chart's revision. Resolution
     * prefers an exact `tipRevisionId` match against `chart.revisionId`; falls
     * back to the `"main"` branch if no match (covers the case where a user
     * has switched branches but the local cache hasn't echoed the tip move yet).
     */
    val currentBranch: ChartBranch? = null,
    /**
     * Target's `"main"` branch on the upstream pattern (`pattern.parentPatternId`).
     * v1 routes PRs only against upstream/main per ADR-014 §1; if the upstream
     * lacks a "main" row the gate stays closed.
     */
    val targetMainBranch: ChartBranch? = null,
    val currentUserId: String? = null,
    val openPrTitleDraft: String = "",
    val openPrDescriptionDraft: String = "",
    val pendingOpenPrSheet: Boolean = false,
    val isOpeningPullRequest: Boolean = false,
    /**
     * Inline error surfaced inside the open-PR sheet. Kept distinct from the
     * top-level [errorMessage] (which displaces the chart with a centered
     * Text) so a form-submit failure stays in the form for retry.
     */
    val openPrError: ErrorMessage? = null,
) {
    /**
     * Derived gate for the "Open pull request" overflow entry.
     * v1 (ADR-014 §1, §6) requires:
     * 1. The pattern is a fork (`parentPatternId != null` — only forks can PR
     *    against an upstream).
     * 2. The current user owns this pattern (only the owner can open PRs from it;
     *    a cross-fork browser viewing someone else's pattern cannot).
     * 3. Source branch resolves on the local cache AND its `tipRevisionId`
     *    matches the loaded chart's revisionId. The "main" fallback in
     *    [resolveCurrentBranch] keeps the displayed-branch rendering helpful
     *    during cache lag, but submitting a PR with a `sourceBranchId` whose
     *    tip does not match `sourceTipRevisionId` would land an immediately
     *    unmergeable PR (the `merge_pull_request` RPC raises "Source tip
     *    drifted" with no recovery path) — see code review MEDIUM-1.
     * 4. Target pattern's `"main"` branch resolves.
     * 5. Chart is loaded (we need the revisionId to be the source tip).
     *
     * Routed through a derived property (not a top-level extension) so the
     * Swift bridge reads it as a plain `Bool` getter — same pattern as
     * `PullRequestDetailState.canMerge` / `canClose`.
     */
    val canOpenPullRequest: Boolean
        get() {
            val p = pattern ?: return false
            val owner = currentUserId ?: return false
            val branch = currentBranch ?: return false
            val c = chart ?: return false
            return p.parentPatternId != null &&
                p.ownerId == owner &&
                branch.tipRevisionId == c.revisionId &&
                targetMainBranch != null
        }
}

sealed interface ChartViewerEvent {
    data class ToggleLayer(
        val layerId: String,
    ) : ChartViewerEvent

    /** Cell tap — cycles the segment state todo → wip → done → todo (ADR-010 §2). */
    data class TapCell(
        val layerId: String,
        val x: Int,
        val y: Int,
    ) : ChartViewerEvent

    /** Long-press — forces the segment to `done` regardless of prior state. */
    data class LongPressCell(
        val layerId: String,
        val x: Int,
        val y: Int,
    ) : ChartViewerEvent

    /**
     * Long-press on a row-number (rect) or ring-number (polar) label — marks
     * every cell on that row/ring as `done` across visible layers. Per
     * ADR-011 §4; [row] maps to chart y-coordinate on rect or ring index on
     * polar without reinterpretation.
     */
    data class MarkRowDone(
        val row: Int,
    ) : ChartViewerEvent

    // Phase 38.4.1 (ADR-014 §6) — Open pull request flow.
    data object RequestOpenPullRequest : ChartViewerEvent

    data class OpenPrTitleChanged(
        val value: String,
    ) : ChartViewerEvent

    data class OpenPrDescriptionChanged(
        val value: String,
    ) : ChartViewerEvent

    data object ConfirmOpenPullRequest : ChartViewerEvent

    data object DismissOpenPullRequestSheet : ChartViewerEvent

    data object ClearOpenPrError : ChartViewerEvent
}

/**
 * One-shot navigation events surfaced to the screen layer. Phase 38.4.1
 * adds [PullRequestCreated] so the screen can navigate to the new PR's
 * detail surface after a successful open.
 */
sealed interface ChartViewerNavEvent {
    data class PullRequestCreated(
        val prId: String,
    ) : ChartViewerNavEvent
}

class ChartViewerViewModel(
    private val patternId: String,
    private val projectId: String?,
    private val observeStructuredChart: ObserveStructuredChartUseCase,
    private val observeProjectSegments: ObserveProjectSegmentsUseCase,
    private val toggleSegmentState: ToggleSegmentStateUseCase,
    private val markSegmentDone: MarkSegmentDoneUseCase,
    private val markRowSegmentsDone: MarkRowSegmentsDoneUseCase,
    // Phase 38.4.1 deps. Nullable defaults so existing test call-sites that
    // don't exercise the open-PR path can construct the ViewModel without
    // supplying them; the gate stays false when any required dep is absent
    // (also covers local-only / offline mode where Supabase isn't configured).
    private val patternRepository: PatternRepository? = null,
    private val chartBranchRepository: ChartBranchRepository? = null,
    private val authRepository: AuthRepository? = null,
    private val openPullRequest: OpenPullRequestUseCase? = null,
    // Phase F.4 — nullable + default null preserves existing test compat.
    private val analyticsTracker: AnalyticsTracker? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ChartViewerState())
    val state: StateFlow<ChartViewerState> = _state.asStateFlow()

    private val _navEvents = Channel<ChartViewerNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<ChartViewerNavEvent> = _navEvents.receiveAsFlow()

    init {
        // Resolve currentUserId synchronously (cached locally per AuthRepository
        // contract). Wrapped with a no-op fallback so a misconfigured DI doesn't
        // null the entire user surface.
        _state.update { it.copy(currentUserId = authRepository?.getCurrentUserId()) }

        // Track the last revisionId we resolved Open-PR context for, so
        // Realtime self-echoes / peer updates that don't actually change the
        // tip don't re-fetch pattern + branches on every emission. Same
        // shape as `ProjectDetailViewModel.observeParentAttribution`'s
        // `distinctUntilChanged` (Phase 36.5).
        var lastResolvedRevisionId: String? = null
        viewModelScope.launch {
            observeStructuredChart(patternId)
                .catch { throwable ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = ErrorMessage.LoadFailed,
                        )
                    }
                }.collect { chart ->
                    _state.update {
                        it.copy(
                            chart = chart,
                            isLoading = false,
                            errorMessage = null,
                            isPolar = chart?.coordinateSystem == CoordinateSystem.POLAR_ROUND,
                        )
                    }
                    if (chart != null && chart.revisionId != lastResolvedRevisionId) {
                        lastResolvedRevisionId = chart.revisionId
                        resolveOpenPrContext(chart)
                    }
                }
        }

        if (projectId != null) {
            viewModelScope.launch {
                observeProjectSegments(projectId)
                    .catch { /* overlay simply stays empty on transient errors */ }
                    .collect { rows ->
                        _state.update { it.copy(segments = rows.toMap()) }
                    }
            }
        }
    }

    fun onEvent(event: ChartViewerEvent) {
        when (event) {
            is ChartViewerEvent.ToggleLayer -> toggleLayer(event.layerId)
            is ChartViewerEvent.TapCell -> tapCell(event.layerId, event.x, event.y)
            is ChartViewerEvent.LongPressCell -> longPressCell(event.layerId, event.x, event.y)
            is ChartViewerEvent.MarkRowDone -> markRowDone(event.row)
            is ChartViewerEvent.RequestOpenPullRequest ->
                _state.update {
                    it.copy(pendingOpenPrSheet = true, openPrError = null)
                }
            is ChartViewerEvent.OpenPrTitleChanged ->
                _state.update { it.copy(openPrTitleDraft = event.value) }
            is ChartViewerEvent.OpenPrDescriptionChanged ->
                _state.update { it.copy(openPrDescriptionDraft = event.value) }
            is ChartViewerEvent.ConfirmOpenPullRequest -> openPullRequestInternal()
            is ChartViewerEvent.DismissOpenPullRequestSheet ->
                _state.update {
                    it.copy(
                        pendingOpenPrSheet = false,
                        openPrTitleDraft = "",
                        openPrDescriptionDraft = "",
                        openPrError = null,
                    )
                }
            is ChartViewerEvent.ClearOpenPrError ->
                _state.update { it.copy(openPrError = null) }
        }
    }

    /**
     * iOS bridge: reads a segment's current state from the in-flight overlay map.
     * Swift cannot as-cast the exported Kotlin `Map<SegmentKey, SegmentState>`
     * to a Swift dictionary because `SegmentKey` does not conform to Swift's
     * `Hashable` protocol out of the Kotlin/Native bridge — this helper avoids
     * the bridging footgun while keeping Compose's `state.segments` lookup fast.
     */
    fun segmentStateAt(
        layerId: String,
        x: Int,
        y: Int,
    ): SegmentState? = _state.value.segments[SegmentKey(layerId, x, y)]

    private fun toggleLayer(layerId: String) {
        _state.update { current ->
            val hidden = current.hiddenLayerIds
            val next = if (layerId in hidden) hidden - layerId else hidden + layerId
            current.copy(hiddenLayerIds = next)
        }
    }

    private fun tapCell(
        layerId: String,
        x: Int,
        y: Int,
    ) {
        val pid = projectId ?: return
        val current = _state.value
        if (layerId in current.hiddenLayerIds) return
        // Phase 35.2f: viewer taps on a cell that belongs to a locked layer
        // are silently dropped per ADR-011 §5 addendum decision 1(c). The
        // viewer Screen pre-filters locked layers from the tap-routing set
        // so this check is defense-in-depth; it also makes the contract
        // observable from the ViewModel test layer.
        if (current.chart
                ?.layers
                ?.firstOrNull { it.id == layerId }
                ?.locked == true
        ) {
            return
        }
        // Phase F.4 — capture only the WIP→DONE transition (tap cycles
        // todo→wip→done→todo, so a "tap" reaches DONE iff the prior
        // segment state was WIP). The segments map is the same Flow the
        // overlay reads from, so this read is consistent with what the
        // user just saw before tapping.
        val priorState = current.segments[SegmentKey(layerId, x, y)]
        viewModelScope.launch {
            when (val result = toggleSegmentState(pid, layerId, x, y)) {
                is UseCaseResult.Success -> {
                    if (priorState == SegmentState.WIP) {
                        analyticsTracker?.capture(
                            eventName = AnalyticsEvents.SEGMENT_MARKED_DONE,
                            properties = mapOf(AnalyticsEvents.Props.SEGMENT_VIA to AnalyticsEvents.Props.SEGMENT_VIA_TAP),
                        )
                    }
                }
                is UseCaseResult.Failure ->
                    _state.update { it.copy(errorMessage = result.error.toErrorMessage()) }
            }
        }
    }

    private fun longPressCell(
        layerId: String,
        x: Int,
        y: Int,
    ) {
        val pid = projectId ?: return
        val current = _state.value
        if (layerId in current.hiddenLayerIds) return
        // Phase 35.2f lock guard — see [tapCell] for rationale.
        if (current.chart
                ?.layers
                ?.firstOrNull { it.id == layerId }
                ?.locked == true
        ) {
            return
        }
        // Phase F.4 — long-press is idempotent on already-DONE segments,
        // so capture only when the prior state was not DONE (avoids
        // duplicate analytics rows when a user long-presses the same
        // cell multiple times).
        val priorState = current.segments[SegmentKey(layerId, x, y)]
        viewModelScope.launch {
            when (val result = markSegmentDone(pid, layerId, x, y)) {
                is UseCaseResult.Success -> {
                    if (priorState != SegmentState.DONE) {
                        analyticsTracker?.capture(
                            eventName = AnalyticsEvents.SEGMENT_MARKED_DONE,
                            properties = mapOf(AnalyticsEvents.Props.SEGMENT_VIA to AnalyticsEvents.Props.SEGMENT_VIA_LONG_PRESS),
                        )
                    }
                }
                is UseCaseResult.Failure ->
                    _state.update { it.copy(errorMessage = result.error.toErrorMessage()) }
            }
        }
    }

    private fun markRowDone(row: Int) {
        val pid = projectId ?: return
        viewModelScope.launch {
            val hiddenLayerIds = _state.value.hiddenLayerIds
            when (val result = markRowSegmentsDone(patternId, pid, row, hiddenLayerIds)) {
                is UseCaseResult.Success -> {
                    // Phase F.4 — one event per row-batch action regardless
                    // of how many segments transitioned. The user did one
                    // intentional gesture; the analytics row should reflect
                    // that, not the cardinality of segments touched.
                    analyticsTracker?.capture(
                        eventName = AnalyticsEvents.SEGMENT_MARKED_DONE,
                        properties = mapOf(AnalyticsEvents.Props.SEGMENT_VIA to AnalyticsEvents.Props.SEGMENT_VIA_ROW_BATCH),
                    )
                }
                is UseCaseResult.Failure ->
                    _state.update { it.copy(errorMessage = result.error.toErrorMessage()) }
            }
        }
    }

    /**
     * Best-effort resolution of the data needed for the "Open pull request"
     * gate. Each lookup degrades to null on failure — the gate stays closed,
     * the rest of the viewer surface is unaffected.
     *
     * Re-runs only when `chart.revisionId` actually changes (the caller
     * de-dups via a `lastResolvedRevisionId` local on the chart-collect
     * path, mirroring `ProjectDetailViewModel.observeParentAttribution`'s
     * Phase 36.5 distinctUntilChanged pattern). A fresh save advances the
     * tip and re-anchors `currentBranch` on the next emission; pattern
     * metadata-only edits do not touch `chart_documents` so they do not
     * trigger this path (acceptable — `parentPatternId` is write-once per
     * ADR-012 §1 and `ownerId` cannot change on an existing pattern, so
     * stale `pattern.title` in state is irrelevant to gate correctness).
     *
     * Explicit try/catch with `CancellationException` rethrow per project
     * convention — `runCatching` would swallow it and silently leave the gate
     * deactivated for the session.
     */
    private suspend fun resolveOpenPrContext(chart: StructuredChart) {
        val patternRepo = patternRepository ?: return
        val branchRepo = chartBranchRepository ?: return
        val pattern =
            try {
                patternRepo.getById(patternId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        val sourceBranches =
            try {
                branchRepo.getByPatternId(patternId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        val currentBranch = resolveCurrentBranch(sourceBranches, chart.revisionId)
        val targetMain =
            pattern?.parentPatternId?.let { parentId ->
                try {
                    branchRepo.getByPatternIdAndName(parentId, ChartBranch.DEFAULT_BRANCH_NAME)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        _state.update {
            it.copy(
                pattern = pattern,
                currentBranch = currentBranch,
                targetMainBranch = targetMain,
            )
        }
    }

    /**
     * Resolve the source branch whose tip equals the loaded chart's revision.
     * Falls back to the `"main"` branch when no exact match exists; returns
     * null if "main" itself is missing, which deactivates the gate.
     */
    private fun resolveCurrentBranch(
        branches: List<ChartBranch>,
        tipRevisionId: String,
    ): ChartBranch? =
        branches.firstOrNull { it.tipRevisionId == tipRevisionId }
            ?: branches.firstOrNull { it.branchName == ChartBranch.DEFAULT_BRANCH_NAME }

    private fun openPullRequestInternal() {
        val current = _state.value
        if (!current.canOpenPullRequest || current.isOpeningPullRequest) return
        val openPr =
            openPullRequest ?: run {
                _state.update {
                    it.copy(openPrError = ErrorMessage.RequiresConnectivity)
                }
                return
            }
        // The `canOpenPullRequest` gate snapshot above guarantees these are
        // non-null. Use `requireNotNull` per project Kotlin coding-style rule
        // (no `!!`) — surfaces a meaningful exception if a future refactor
        // invalidates the gate.
        val pattern = requireNotNull(current.pattern) { "canOpenPullRequest gate: pattern is null" }
        val sourceBranch = requireNotNull(current.currentBranch) { "canOpenPullRequest gate: currentBranch is null" }
        val targetBranch = requireNotNull(current.targetMainBranch) { "canOpenPullRequest gate: targetMainBranch is null" }
        val chart = requireNotNull(current.chart) { "canOpenPullRequest gate: chart is null" }
        val parentPatternId =
            requireNotNull(pattern.parentPatternId) { "canOpenPullRequest gate: parentPatternId is null" }
        val title = current.openPrTitleDraft.trim()
        val description = current.openPrDescriptionDraft.trim().takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            _state.update { it.copy(isOpeningPullRequest = true, openPrError = null) }
            val result =
                openPr(
                    sourcePatternId = pattern.id,
                    sourceBranchId = sourceBranch.id,
                    sourceTipRevisionId = chart.revisionId,
                    targetPatternId = parentPatternId,
                    targetBranchId = targetBranch.id,
                    title = title,
                    description = description,
                )
            when (result) {
                is UseCaseResult.Success -> {
                    _state.update {
                        it.copy(
                            isOpeningPullRequest = false,
                            pendingOpenPrSheet = false,
                            openPrTitleDraft = "",
                            openPrDescriptionDraft = "",
                            openPrError = null,
                        )
                    }
                    // Phase F.4 — chart format reflects the source pattern's
                    // grid (rect / polar) so PostHog can segment PR-open
                    // adoption by chart type.
                    analyticsTracker?.capture(
                        eventName = AnalyticsEvents.PULL_REQUEST_OPENED,
                        properties =
                            mapOf(
                                AnalyticsEvents.Props.CHART_FORMAT to
                                    when (chart.coordinateSystem) {
                                        CoordinateSystem.RECT_GRID -> AnalyticsEvents.Props.CHART_FORMAT_RECT
                                        CoordinateSystem.POLAR_ROUND -> AnalyticsEvents.Props.CHART_FORMAT_POLAR
                                    },
                            ),
                    )
                    _navEvents.trySend(
                        ChartViewerNavEvent.PullRequestCreated(prId = result.value.id),
                    )
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(
                            isOpeningPullRequest = false,
                            openPrError = result.error.toErrorMessage(),
                        )
                    }
            }
        }
    }
}

private fun List<ProjectSegment>.toMap(): Map<SegmentKey, SegmentState> =
    associate { SegmentKey(it.layerId, it.cellX, it.cellY) to it.state }
