package io.github.b150005.knitnote.ui.pullrequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.data.analytics.AnalyticsEvents
import io.github.b150005.knitnote.data.analytics.AnalyticsTracker
import io.github.b150005.knitnote.domain.chart.CellConflict
import io.github.b150005.knitnote.domain.chart.CellCoordinate
import io.github.b150005.knitnote.domain.chart.ConflictDetector
import io.github.b150005.knitnote.domain.chart.ConflictReport
import io.github.b150005.knitnote.domain.chart.LayerConflict
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.model.toStructuredChart
import io.github.b150005.knitnote.domain.repository.ChartRevisionRepository
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import io.github.b150005.knitnote.domain.usecase.ConflictResolution
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.GetPullRequestUseCase
import io.github.b150005.knitnote.domain.usecase.MergePullRequestUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.applyResolutions
import io.github.b150005.knitnote.domain.usecase.toErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 38.4 (ADR-014 §4 §5 §6 §8) — interactive conflict resolution surface.
 *
 * Loads ancestor / theirs / mine snapshots, runs [ConflictDetector] up-front,
 * and then exposes a per-conflict picker. When every conflict (cell + layer)
 * has a resolution, [ChartConflictResolutionState.canApplyAndMerge] flips
 * true and the user can confirm the merge.
 *
 * **No partial application** — the merge is one shot. Skipping (resolving to
 * the ancestor value) is allowed but the user must explicitly pick "Skip" per
 * conflict; an empty resolution map keeps the merge button disabled.
 */
data class ChartConflictResolutionState(
    val pullRequest: PullRequest? = null,
    val ancestor: StructuredChart? = null,
    val theirs: StructuredChart? = null,
    val mine: StructuredChart? = null,
    val report: ConflictReport? = null,
    val cellResolutions: Map<CellCoordinate, ConflictResolution> = emptyMap(),
    val layerResolutions: Map<String, ConflictResolution> = emptyMap(),
    val isLoading: Boolean = true,
    val isMerging: Boolean = false,
    val error: ErrorMessage? = null,
) {
    val canApplyAndMerge: Boolean
        get() {
            val r = report ?: return false
            if (isMerging) return false
            if (pullRequest == null || ancestor == null || theirs == null || mine == null) return false
            // Every cell conflict must have a pick.
            val cellsResolved = r.conflicts.all { cellResolutions.containsKey(CellCoordinate(it.layerId, it.x, it.y)) }
            // Every layer conflict must have a pick.
            val layersResolved = r.layerConflicts.all { layerResolutions.containsKey(it.layerId) }
            return cellsResolved && layersResolved
        }
}

sealed interface ChartConflictResolutionEvent {
    data class PickCell(
        val coordinate: CellCoordinate,
        val resolution: ConflictResolution,
    ) : ChartConflictResolutionEvent

    data class PickLayer(
        val layerId: String,
        val resolution: ConflictResolution,
    ) : ChartConflictResolutionEvent

    data object ApplyAndMerge : ChartConflictResolutionEvent

    data object ClearError : ChartConflictResolutionEvent
}

sealed interface ChartConflictResolutionNavEvent {
    data class MergeApplied(
        val mergedRevisionId: String,
    ) : ChartConflictResolutionNavEvent
}

/**
 * Loads and resolves a single PR's conflicts. Initialised with the PR's id;
 * dependencies are injected and the load is one-shot in `init` (revisions
 * are immutable per ADR-013 §1 so there is no Flow to observe).
 */
class ChartConflictResolutionViewModel(
    private val prId: String,
    private val getPullRequest: GetPullRequestUseCase,
    private val chartRevisionRepository: ChartRevisionRepository,
    private val structuredChartRepository: StructuredChartRepository,
    private val mergePullRequest: MergePullRequestUseCase,
    private val analyticsTracker: AnalyticsTracker? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ChartConflictResolutionState())
    val state: StateFlow<ChartConflictResolutionState> = _state.asStateFlow()

    private val _navEvents = Channel<ChartConflictResolutionNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<ChartConflictResolutionNavEvent> = _navEvents.receiveAsFlow()

    init {
        viewModelScope.launch { loadInitial() }
    }

    fun onEvent(event: ChartConflictResolutionEvent) {
        when (event) {
            is ChartConflictResolutionEvent.PickCell ->
                _state.update {
                    it.copy(cellResolutions = it.cellResolutions + (event.coordinate to event.resolution))
                }
            is ChartConflictResolutionEvent.PickLayer ->
                _state.update {
                    it.copy(layerResolutions = it.layerResolutions + (event.layerId to event.resolution))
                }
            ChartConflictResolutionEvent.ApplyAndMerge -> applyAndMerge()
            ChartConflictResolutionEvent.ClearError ->
                _state.update { it.copy(error = null) }
        }
    }

    private suspend fun loadInitial() {
        when (val prResult = getPullRequest(prId)) {
            is UseCaseResult.Failure -> {
                _state.update { it.copy(isLoading = false, error = prResult.error.toErrorMessage()) }
                return
            }
            is UseCaseResult.Success -> {
                val pr = prResult.value
                // Pull all three revisions in parallel via launch; ancestor +
                // theirs are resolved against the captured snapshots on the PR
                // row, mine is the *current* tip pointer for the target
                // pattern. The merge RPC re-validates source tip server-side
                // — the ViewModel's job is to surface the conflict report
                // against the snapshot that was captured at PR-open time.
                val ancestor =
                    safeFetchRevision(pr.commonAncestorRevisionId)
                val theirs =
                    safeFetchRevision(pr.sourceTipRevisionId)
                val mine =
                    try {
                        structuredChartRepository.getByPatternId(pr.targetPatternId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }

                if (ancestor == null || theirs == null || mine == null) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            pullRequest = pr,
                            error = ErrorMessage.LoadFailed,
                        )
                    }
                    return
                }

                val report =
                    ConflictDetector.detect(
                        ancestor = ancestor,
                        theirs = theirs,
                        mine = mine,
                    )

                _state.update {
                    it.copy(
                        pullRequest = pr,
                        ancestor = ancestor,
                        theirs = theirs,
                        mine = mine,
                        report = report,
                        isLoading = false,
                    )
                }
            }
        }
    }

    private suspend fun safeFetchRevision(revisionId: String): StructuredChart? =
        try {
            chartRevisionRepository.getRevision(revisionId)?.toStructuredChart()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun applyAndMerge() {
        val snapshot = _state.value
        val pr = snapshot.pullRequest ?: return
        val ancestor = snapshot.ancestor ?: return
        val theirs = snapshot.theirs ?: return
        val mine = snapshot.mine ?: return
        val report = snapshot.report ?: return
        if (!snapshot.canApplyAndMerge) return

        viewModelScope.launch {
            _state.update { it.copy(isMerging = true) }
            val resolved =
                applyResolutions(
                    mine = mine,
                    autoFromTheirs = report.autoFromTheirs,
                    conflictPicks = snapshot.cellResolutions,
                    autoLayerFromTheirs = report.autoLayerFromTheirs,
                    layerConflictPicks = snapshot.layerResolutions,
                    theirs = theirs,
                    ancestor = ancestor,
                )
            when (val result = mergePullRequest(pr, resolved)) {
                is UseCaseResult.Success -> {
                    _state.update { it.copy(isMerging = false) }
                    // Phase F.5 — closes the F.4 deferral. Conflict-resolution
                    // path always reports had_conflicts=true; the auto-clean
                    // counterpart in PullRequestDetailViewModel reports
                    // had_conflicts=false. Together the two ViewModels cover
                    // every successful merge transition.
                    analyticsTracker?.capture(
                        eventName = AnalyticsEvents.PULL_REQUEST_MERGED,
                        properties = mapOf(AnalyticsEvents.Props.HAD_CONFLICTS to true),
                    )
                    _navEvents.trySend(
                        ChartConflictResolutionNavEvent.MergeApplied(
                            mergedRevisionId = result.value.mergedRevisionId,
                        ),
                    )
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(isMerging = false, error = result.error.toErrorMessage())
                    }
            }
        }
    }
}

/**
 * Helper for resolving a [CellConflict] to its picked-side chart cell value.
 * Used by the resolution screen's preview pane (when an "before/after"
 * preview wants to render the user's pending pick).
 */
fun CellConflict.cellFor(resolution: ConflictResolution) =
    when (resolution) {
        ConflictResolution.TAKE_THEIRS -> theirs
        ConflictResolution.KEEP_MINE -> mine
        ConflictResolution.SKIP -> ancestor
    }

/** Same as [CellConflict.cellFor] for layer-level picks. */
fun LayerConflict.layerFor(resolution: ConflictResolution) =
    when (resolution) {
        ConflictResolution.TAKE_THEIRS -> theirs
        ConflictResolution.KEEP_MINE -> mine
        ConflictResolution.SKIP -> ancestor
    }
