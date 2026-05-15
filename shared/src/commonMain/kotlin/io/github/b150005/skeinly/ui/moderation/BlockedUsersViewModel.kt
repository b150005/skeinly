package io.github.b150005.skeinly.ui.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.domain.model.BlockedUser
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 39 (ADR-021 §D4) — Settings → Privacy → Blocked Users screen.
 *
 * Lists the caller's blocked users (display name resolved server-side
 * from `profiles`) with a per-row Unblock affordance. Unblocking
 * removes the `user_blocks` row; the migration-032 RLS amendments then
 * stop filtering that user's content out of the caller's queries.
 *
 * **Auto-load on init** — the screen is reached by tapping a Settings
 * row, so the list should already be populating by the time it paints
 * (mirrors the implicit-load precedent of list screens that take no
 * explicit Load tap from the user).
 *
 * **Per-row in-flight set** ([BlockedUsersState.unblockingIds]) so
 * concurrent Unblock taps on different rows each get their own spinner
 * and a same-row re-tap is swallowed — same pattern as
 * [io.github.b150005.skeinly.ui.connections.ConnectionsViewModel]'s
 * `pendingActionTargets`.
 *
 * **Optimistic-free**: on successful unblock the row is removed from
 * the in-memory list directly (no full refetch) — the server state is
 * already authoritative and a refetch would cost a round-trip for a
 * single-row delta. A failed unblock keeps the row + surfaces
 * [BlockedUsersState.error].
 *
 * **Lambda-seam DI** mirrors [io.github.b150005.skeinly.ui.connections.ConnectionsViewModel]:
 * [loadBlocked] / [unblock] wrap `UgcModerationRepository` methods so
 * commonTest injects recording stubs without supabase-kt.
 */
data class BlockedUsersState(
    // Defaults to `true` so the very first composed frame — before
    // the `init { load() }` coroutine flips it on the Main dispatcher
    // — renders the loading branch, NOT a one-frame "You haven't
    // blocked anyone" flash (isEmpty would otherwise be true on the
    // pristine state).
    val isLoading: Boolean = true,
    val users: List<BlockedUser> = emptyList(),
    /** Per-row in-flight set keyed by [BlockedUser.userId]. */
    val unblockingIds: Set<String> = emptySet(),
    val error: ErrorMessage? = null,
) {
    /** True iff the list loaded successfully and is empty (drives the
     *  "You haven't blocked anyone" empty state, distinct from the
     *  loading spinner). */
    val isEmpty: Boolean
        get() = !isLoading && users.isEmpty() && error == null

    fun isUnblocking(userId: String): Boolean = userId in unblockingIds
}

sealed interface BlockedUsersEvent {
    /** Initial load + explicit refresh (pull-to-refresh / retry). */
    data object Load : BlockedUsersEvent

    data class Unblock(
        val userId: String,
    ) : BlockedUsersEvent

    data object ClearError : BlockedUsersEvent
}

class BlockedUsersViewModel(
    private val loadBlocked: suspend () -> UseCaseResult<List<BlockedUser>>,
    private val unblock: suspend (userId: String) -> UseCaseResult<Unit>,
) : ViewModel() {
    private val _state = MutableStateFlow(BlockedUsersState())
    val state: StateFlow<BlockedUsersState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: BlockedUsersEvent) {
        when (event) {
            BlockedUsersEvent.Load -> load()
            is BlockedUsersEvent.Unblock -> performUnblock(event.userId)
            BlockedUsersEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val result = loadBlocked()) {
                is UseCaseResult.Success ->
                    _state.update {
                        it.copy(isLoading = false, users = result.value)
                    }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = result.error.toErrorMessage(),
                        )
                    }
            }
        }
    }

    private fun performUnblock(userId: String) {
        // Same-row re-tap guard: `add` returning false means it's
        // already in flight, so swallow the second tap.
        if (userId in _state.value.unblockingIds) return
        viewModelScope.launch {
            _state.update {
                it.copy(unblockingIds = it.unblockingIds + userId, error = null)
            }
            val result =
                try {
                    unblock(userId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The repository never throws (UseCaseResult), but
                    // the lambda seam could in a future binding —
                    // defensive so the row's spinner always clears.
                    _state.update {
                        it.copy(
                            unblockingIds = it.unblockingIds - userId,
                            error = ErrorMessage.Generic,
                        )
                    }
                    return@launch
                }
            when (result) {
                is UseCaseResult.Success ->
                    _state.update {
                        it.copy(
                            users = it.users.filterNot { u -> u.userId == userId },
                            unblockingIds = it.unblockingIds - userId,
                        )
                    }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(
                            unblockingIds = it.unblockingIds - userId,
                            error = result.error.toErrorMessage(),
                        )
                    }
            }
        }
    }
}
