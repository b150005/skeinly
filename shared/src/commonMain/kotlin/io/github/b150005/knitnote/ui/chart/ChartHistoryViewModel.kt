package io.github.b150005.knitnote.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.ChartRevision
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.GetChartHistoryUseCase
import io.github.b150005.knitnote.domain.usecase.RestoreRevisionUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toErrorMessage
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

/**
 * Live revision list rendered by [ChartHistoryScreen] (Phase 37.2, ADR-013 §6).
 *
 * `revisions` is newest-first per the repository's `created_at DESC` query.
 * The `error` slot surfaces transient flow failures (e.g. local-DB read crash);
 * Phase 37 has no UseCase-error-localization layer yet so the raw message is
 * shown verbatim per the project-wide "ViewModel error-message localization"
 * Tech Debt deferral.
 */
data class ChartHistoryState(
    val revisions: List<ChartRevision> = emptyList(),
    val isLoading: Boolean = true,
    val error: ErrorMessage? = null,
    /**
     * Phase 37.4 (ADR-013 §6): the revision the user long-pressed and is being
     * asked to confirm restoration of. Non-null while the confirmation dialog
     * is visible; cleared on confirm or dismiss.
     */
    val pendingRestoreRevision: ChartRevision? = null,
)

sealed interface ChartHistoryEvent {
    /**
     * Tap a revision row → ViewModel emits a [RevisionTapTarget] on the
     * navigation channel carrying both the tapped (target) revision id and
     * its parent revision id (base). Phase 37.3 routes that payload to
     * `ChartDiff(baseRevisionId = parent, targetRevisionId = tapped)`.
     */
    data class TapRevision(
        val revisionId: String,
    ) : ChartHistoryEvent

    /**
     * Long-press a revision row → opens the restore confirmation dialog
     * (Phase 37.4, ADR-013 §6). Non-load-bearing if [revisionId] is unknown
     * — the ViewModel silently no-ops rather than surfacing an error since
     * the row was rendered from the same in-memory list.
     */
    data class LongPressRevision(
        val revisionId: String,
    ) : ChartHistoryEvent

    /** Confirm the pending restore — appends a new revision on top of tip. */
    data object ConfirmRestore : ChartHistoryEvent

    /** Dismiss the pending restore dialog without restoring. */
    data object DismissRestore : ChartHistoryEvent

    data object ClearError : ChartHistoryEvent
}

/**
 * Channel payload for a tapped revision: the resolved (target, base) pair the
 * `ChartDiffScreen` needs. `baseRevisionId` is null when the tap target has no
 * parent (initial commit) — `ChartDiffScreen` then renders the initial-commit
 * view per ADR-013 §6 instead of a side-by-side diff.
 *
 * The lookup happens in the ViewModel because it owns the revision graph; the
 * Compose / SwiftUI screen layers stay diff-routing-agnostic and just forward
 * the payload through `onRevisionClick`.
 */
data class RevisionTapTarget(
    val targetRevisionId: String,
    val baseRevisionId: String?,
)

class ChartHistoryViewModel(
    private val patternId: String,
    private val getChartHistory: GetChartHistoryUseCase,
    // Phase 37.4: long-press → restore. Optional with `null` default so 37.2
    // test call-sites that never reach the restore branch stay green.
    private val restoreRevision: RestoreRevisionUseCase? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ChartHistoryState())
    val state: StateFlow<ChartHistoryState> = _state.asStateFlow()

    /**
     * One-shot tap channel — drained as a Flow by the Compose / SwiftUI layer.
     * `BUFFERED` so taps queued before the consumer attaches still deliver
     * (e.g. SwiftUI's `.task { }` may attach a frame after the view binds).
     */
    private val _revisionTaps = Channel<RevisionTapTarget>(Channel.BUFFERED)
    val revisionTaps: Flow<RevisionTapTarget> = _revisionTaps.receiveAsFlow()

    init {
        getChartHistory
            .observe(patternId)
            .onEach { revisions ->
                _state.update {
                    it.copy(revisions = revisions, isLoading = false)
                }
            }.catch { throwable ->
                _state.update {
                    it.copy(isLoading = false, error = ErrorMessage.LoadFailed)
                }
            }.launchIn(viewModelScope)
    }

    fun onEvent(event: ChartHistoryEvent) {
        when (event) {
            is ChartHistoryEvent.TapRevision -> {
                // Look up the tapped revision to derive its parent for the diff
                // base. Unknown revisionId (race against an in-flight Realtime
                // delete, or a malformed call) emits null base — `ChartDiffScreen`
                // then surfaces an "Initial commit" view rather than crashing the
                // navigation channel.
                val target = _state.value.revisions.firstOrNull { it.revisionId == event.revisionId }
                _revisionTaps.trySend(
                    RevisionTapTarget(
                        targetRevisionId = event.revisionId,
                        baseRevisionId = target?.parentRevisionId,
                    ),
                )
            }

            is ChartHistoryEvent.LongPressRevision -> {
                val target = _state.value.revisions.firstOrNull { it.revisionId == event.revisionId }
                if (target != null) {
                    _state.update { it.copy(pendingRestoreRevision = target) }
                }
            }

            ChartHistoryEvent.ConfirmRestore -> {
                val target = _state.value.pendingRestoreRevision ?: return
                _state.update { it.copy(pendingRestoreRevision = null) }
                val useCase = restoreRevision ?: return
                viewModelScope.launch {
                    when (val result = useCase(patternId, target.revisionId)) {
                        is UseCaseResult.Success -> Unit
                        is UseCaseResult.Failure ->
                            _state.update { it.copy(error = result.error.toErrorMessage()) }
                    }
                }
            }

            ChartHistoryEvent.DismissRestore ->
                _state.update { it.copy(pendingRestoreRevision = null) }

            ChartHistoryEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }
}
