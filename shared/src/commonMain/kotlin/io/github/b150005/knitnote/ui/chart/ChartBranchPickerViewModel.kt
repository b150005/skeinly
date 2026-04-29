package io.github.b150005.knitnote.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.ChartBranch
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import io.github.b150005.knitnote.domain.usecase.CreateBranchUseCase
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.GetChartBranchesUseCase
import io.github.b150005.knitnote.domain.usecase.SwitchBranchUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toErrorMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Branch picker on top of `ChartViewerScreen` (Phase 37.4, ADR-013 §7).
 *
 * Owns the live branch list + the create / switch mutations. The picker UI
 * is a sheet, so the ViewModel survives the sheet's open/close lifecycle and
 * keeps the branch list warm across reopens.
 *
 * The "current branch" is derived from the materialized chart's `revisionId`:
 * a branch whose `tipRevisionId` matches is "current". When two branches share
 * a tip (immediately after `createBranch`), the picker highlights both — same
 * model as `StructuredChartRepositoryImpl.advanceCurrentBranchTip`.
 */
data class ChartBranchPickerState(
    val branches: List<ChartBranch> = emptyList(),
    val currentRevisionId: String? = null,
    val isLoading: Boolean = true,
    val error: ErrorMessage? = null,
)

sealed interface ChartBranchPickerEvent {
    data class CreateBranch(
        val branchName: String,
    ) : ChartBranchPickerEvent

    data class SwitchBranch(
        val branchName: String,
    ) : ChartBranchPickerEvent

    data object ClearError : ChartBranchPickerEvent
}

/**
 * One-shot signal that a branch switch completed. The consumer (Compose
 * `LaunchedEffect`, SwiftUI `.task`) reacts by closing the picker sheet and
 * surfacing a transient confirmation. [branchName] is the switched-to branch
 * — carried in the envelope rather than snapshotted from the ViewModel state
 * so the consumer is independent of the timing race between
 * `_branchSwitched.trySend` and the `combine` block writing the new
 * `currentRevisionId` into `_state` (the two paths are concurrent on the
 * same scope, with no happens-before guarantee).
 */
data class BranchSwitchedEvent(
    val branchName: String,
)

class ChartBranchPickerViewModel(
    private val patternId: String,
    private val getBranches: GetChartBranchesUseCase,
    private val createBranch: CreateBranchUseCase,
    private val switchBranch: SwitchBranchUseCase,
    private val chartRepository: StructuredChartRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ChartBranchPickerState())
    val state: StateFlow<ChartBranchPickerState> = _state.asStateFlow()

    private val _branchSwitched = Channel<BranchSwitchedEvent>(Channel.BUFFERED)
    val branchSwitched: Flow<BranchSwitchedEvent> = _branchSwitched.receiveAsFlow()

    init {
        // Combine the live branch list with the live chart so the "current
        // branch" highlight tracks both branch tip moves AND chart updates
        // landing through Realtime / sync. Either side firing recomputes the
        // displayed state — saving the user from having to refresh manually
        // after a peer device pushes a tip advance.
        combine(
            getBranches.observe(patternId),
            chartRepository.observeByPatternId(patternId),
        ) { branches, chart ->
            ChartBranchPickerState(
                branches = branches,
                currentRevisionId = chart?.revisionId,
                isLoading = false,
                error = _state.value.error,
            )
        }.onEach { newState ->
            _state.update { newState }
        }.catch { throwable ->
            _state.update {
                it.copy(isLoading = false, error = ErrorMessage.LoadFailed)
            }
        }.launchIn(viewModelScope)
    }

    fun onEvent(event: ChartBranchPickerEvent) {
        when (event) {
            is ChartBranchPickerEvent.CreateBranch -> handleCreateBranch(event.branchName)
            is ChartBranchPickerEvent.SwitchBranch -> handleSwitchBranch(event.branchName)
            ChartBranchPickerEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun handleCreateBranch(branchName: String) {
        viewModelScope.launch {
            val ownerId = authRepository.getCurrentUserId()
            if (ownerId == null) {
                _state.update { it.copy(error = ErrorMessage.SignInRequired) }
                return@launch
            }
            when (val result = createBranch(patternId, branchName, ownerId)) {
                is UseCaseResult.Success -> Unit
                is UseCaseResult.Failure ->
                    _state.update { it.copy(error = result.error.toErrorMessage()) }
            }
        }
    }

    private fun handleSwitchBranch(branchName: String) {
        viewModelScope.launch {
            when (val result = switchBranch(patternId, branchName)) {
                is UseCaseResult.Success -> {
                    _branchSwitched.trySend(BranchSwitchedEvent(branchName = branchName))
                }

                is UseCaseResult.Failure -> {
                    _state.update { it.copy(error = result.error.toErrorMessage()) }
                }
            }
        }
    }
}
