package io.github.b150005.knitnote.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.ChartRevision
import io.github.b150005.knitnote.domain.usecase.GetChartHistoryUseCase
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
    val error: String? = null,
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
                    it.copy(isLoading = false, error = throwable.message ?: "Failed to load chart history")
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
            ChartHistoryEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }
}
