package io.github.b150005.skeinly.ui.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toErrorMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 39 (ADR-021 §D4) — the "Block this user?" confirmation action.
 *
 * Reachable from the Suggestion author chip and (future) user-profile
 * surface. After a destructive confirmation dialog the user taps
 * Block; the repository INSERTs a `user_blocks` row and the
 * migration-032 RLS amendments filter all of the blocked user's
 * patterns / comments / suggestions / suggestion-comments out of the
 * blocker's queries server-side from that point on.
 *
 * **Single-shot action VM** (no list state — that's
 * [BlockedUsersViewModel]). The dialog owns the confirm/cancel
 * affordance; this VM only carries the in-flight + error state and
 * emits a [BlockUserNavEvent.Blocked] one-shot on success so the
 * screen can dismiss + flash a "User blocked" confirmation.
 *
 * **Re-entry guard**: [BlockUserState.isBlocking] short-circuits a
 * double-tap during the network round-trip — same precedent as
 * [io.github.b150005.skeinly.ui.settings.WipeDataViewModel].
 *
 * **Lambda-seam DI**: [blockedUserId] is a screen-time param;
 * [blockUser] wraps `UgcModerationRepository::blockUser`. Self-block
 * is rejected by the repository ([io.github.b150005.skeinly.domain.usecase.UseCaseError.OperationNotAllowed]
 * → [ErrorMessage.OperationNotAllowed]); the UI also hides the Block
 * affordance on the caller's own content, so reaching that arm is a
 * defensive backstop.
 */
data class BlockUserState(
    val isBlocking: Boolean = false,
    val error: ErrorMessage? = null,
)

sealed interface BlockUserEvent {
    /** Fire the block. No-op while a block is already in flight. */
    data object Confirm : BlockUserEvent

    data object ClearError : BlockUserEvent
}

sealed interface BlockUserNavEvent {
    data object Blocked : BlockUserNavEvent
}

class BlockUserViewModel(
    /** Who to block — resolved at screen-mount time. */
    private val blockedUserId: String,
    /** Lambda over `UgcModerationRepository::blockUser`. */
    private val blockUser: suspend (blockedUserId: String) -> UseCaseResult<Unit>,
) : ViewModel() {
    private val _state = MutableStateFlow(BlockUserState())
    val state: StateFlow<BlockUserState> = _state.asStateFlow()

    private val _navChannel = Channel<BlockUserNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<BlockUserNavEvent> = _navChannel.receiveAsFlow()

    fun onEvent(event: BlockUserEvent) {
        when (event) {
            BlockUserEvent.Confirm -> performBlock()
            BlockUserEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun performBlock() {
        if (_state.value.isBlocking) return
        // Flip `isBlocking` SYNCHRONOUSLY before launching so the
        // re-entry guard is atomic on the single-threaded Main
        // dispatcher (see UgcReportViewModel.performSubmit for the
        // dispatch-window rationale). A queued double-tap re-reads
        // `isBlocking = true` and returns early.
        _state.update { it.copy(isBlocking = true, error = null) }
        viewModelScope.launch {
            when (val result = blockUser(blockedUserId)) {
                is UseCaseResult.Success -> {
                    _state.update { it.copy(isBlocking = false) }
                    _navChannel.send(BlockUserNavEvent.Blocked)
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(
                            isBlocking = false,
                            error = result.error.toErrorMessage(),
                        )
                    }
            }
        }
    }
}
