package io.github.b150005.skeinly.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.domain.model.FriendConnection
import io.github.b150005.skeinly.domain.model.FriendInvite
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
 * Phase 25.3 (ADR-024 §(e)) — Settings → Privacy → Connections screen
 * ViewModel.
 *
 * **Three tabs** per ADR §(e): Friends / Pending / Invite.
 * - Friends tab → accepted-state edges, with disconnect action.
 * - Pending tab → both inbound (Accept/Reject) and outbound (Cancel)
 *   pending-state edges. UI discriminates via
 *   [FriendConnection.callerIsRequester].
 * - Invite tab → outstanding invites + create-invite + share.
 *
 * **Profile resolution**: friend_connections rows carry only UUIDs.
 * Display names are batch-fetched from [UserRepository.getByIds] after
 * each list refresh and stashed in [ConnectionsState.displayNames]. The
 * UI looks them up by UUID with a localized "Unknown user" fallback for
 * profiles that fail to resolve (deleted account, RLS-hidden by a block,
 * cold start before fetch completes). Batch fetch over N+1 because
 * `getByIds` already exists ([RemoteUserDataSource.getByIds] uses
 * `isIn("id", ids)`) and a typical friend list is small (<50) — one
 * round-trip dominates over N round-trips at any scale.
 *
 * **Lambda-seam DI** (mirrors [WipeDataViewModel] / MfaEnrollmentViewModel):
 * each FriendRepository method is bound as a `suspend` lambda at the
 * Koin site so commonTest can inject recording stubs without standing
 * up the supabase-kt surface. Same for [resolveDisplayNames] (wraps
 * UserRepository.getByIds → Map<id, displayName>).
 *
 * **In-flight guards** are per-target rather than global: tapping
 * Accept on Alice's row should not gray out Bob's row. The
 * [ConnectionsState.pendingActionUserId] field is replaced by a set
 * [pendingActionTargets] so concurrent taps on different targets each
 * get their own spinner; same target re-tap is silently swallowed by
 * the `add` returning false.
 *
 * **Caller id** is resolved once at init via [getCallerId] and frozen
 * for the VM's lifetime. Sign-out flips AuthState upstream; the VM
 * itself doesn't observe — the screen above it pops on auth-flip.
 */
data class ConnectionsState(
    val activeTab: ConnectionsTab = ConnectionsTab.Friends,
    /** True during initial load AND any explicit refresh. */
    val isLoading: Boolean = false,
    val friends: List<FriendConnection> = emptyList(),
    /** Both inbound + outbound pending requests. */
    val pending: List<FriendConnection> = emptyList(),
    val invites: List<FriendInvite> = emptyList(),
    /** UUID → display name. Missing entry ⇒ render "Unknown user". */
    val displayNames: Map<String, String> = emptyMap(),
    /** Per-target in-flight set so concurrent taps on different rows
     *  each get an independent spinner. */
    val pendingActionTargets: Set<String> = emptySet(),
    val isCreatingInvite: Boolean = false,
    /** Caller's auth.uid(). Null until init resolves it. UI uses this
     *  to discriminate inbound vs outbound pending rows. */
    val callerId: String? = null,
    /** Inline error from the most-recent failed action; cleared via
     *  [ConnectionsEvent.ClearError] or by next action retry. */
    val error: ErrorMessage? = null,
) {
    /**
     * Helper for the Pending tab UI: returns true iff the caller is
     * the recipient (NOT the requester) of this pending edge — i.e.
     * the row should show Accept / Reject buttons.
     *
     * For unresolved [callerId] (init hasn't completed), returns false
     * so the UI defaults to outbound (Cancel) treatment, which is
     * harmless because the unresolved-caller window is sub-second.
     */
    fun isInbound(connection: FriendConnection): Boolean = callerId?.let { !connection.callerIsRequester(it) } ?: false

    /** True iff at least one action is in-flight against [otherUserId]. */
    fun isActionInFlight(otherUserId: String): Boolean = otherUserId in pendingActionTargets
}

/**
 * Closed enum for the 3-tab selector. UI's TabRow / Picker iterates
 * over [entries] for index-based mapping.
 */
enum class ConnectionsTab {
    Friends,
    Pending,
    Invite,
}

sealed interface ConnectionsEvent {
    data class SelectTab(
        val tab: ConnectionsTab,
    ) : ConnectionsEvent

    /** Refresh all three lists + display names. */
    data object Refresh : ConnectionsEvent

    /** From Pending tab → inbound row → Accept tap. */
    data class AcceptRequest(
        val otherUserId: String,
    ) : ConnectionsEvent

    /** From Pending tab → inbound row → Reject tap. Same RPC as Cancel
     *  outbound (DELETE) — repository [FriendRepository.rejectRequest]
     *  is the destination. UI distinguishes for clarity only. */
    data class RejectRequest(
        val otherUserId: String,
    ) : ConnectionsEvent

    /** From Pending tab → outbound row → Cancel tap. Routes through
     *  [FriendRepository.rejectRequest] under the hood. */
    data class CancelOutboundRequest(
        val otherUserId: String,
    ) : ConnectionsEvent

    /** From Friends tab → row → Disconnect (after confirmation). */
    data class Disconnect(
        val otherUserId: String,
    ) : ConnectionsEvent

    /** From Invite tab → "Create invite" button. */
    data object CreateInvite : ConnectionsEvent

    data object ClearError : ConnectionsEvent
}

class ConnectionsViewModel(
    private val getCallerId: suspend () -> String?,
    private val listFriends: suspend () -> UseCaseResult<List<FriendConnection>>,
    private val listPending: suspend () -> UseCaseResult<List<FriendConnection>>,
    private val listInvites: suspend () -> UseCaseResult<List<FriendInvite>>,
    /** Resolves a list of user UUIDs to a UUID → displayName map.
     *  Missing entries (failed lookup, profile deleted) are simply
     *  absent from the map; the screen falls back to "Unknown user". */
    private val resolveDisplayNames: suspend (List<String>) -> Map<String, String>,
    private val acceptRequest: suspend (String) -> UseCaseResult<Unit>,
    private val rejectRequest: suspend (String) -> UseCaseResult<Unit>,
    private val disconnect: suspend (String) -> UseCaseResult<Unit>,
    private val createInvite: suspend () -> UseCaseResult<FriendInvite>,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectionsState())
    val state: StateFlow<ConnectionsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val callerId = getCallerId()
            _state.update { it.copy(callerId = callerId) }
            refreshAll()
        }
    }

    fun onEvent(event: ConnectionsEvent) {
        when (event) {
            is ConnectionsEvent.SelectTab ->
                _state.update { it.copy(activeTab = event.tab) }
            ConnectionsEvent.Refresh ->
                // In-flight guard: a fast double-dispatch (e.g. a
                // future pull-to-refresh widget swiped twice) must not
                // launch concurrent refreshAll coroutines that race to
                // overwrite the three lists out-of-order. isLoading is
                // set synchronously at refreshAll's entry on the Main
                // dispatcher, so checking it here is a reliable guard.
                if (!_state.value.isLoading) {
                    viewModelScope.launch { refreshAll() }
                }
            is ConnectionsEvent.AcceptRequest ->
                runAction(event.otherUserId) { acceptRequest(event.otherUserId) }
            is ConnectionsEvent.RejectRequest ->
                runAction(event.otherUserId) { rejectRequest(event.otherUserId) }
            is ConnectionsEvent.CancelOutboundRequest ->
                // Same RPC as RejectRequest — both DELETE the pending row.
                // Distinct event preserved for analytics / clarity if a
                // future divergence (e.g. cancellation reason capture)
                // surfaces.
                runAction(event.otherUserId) { rejectRequest(event.otherUserId) }
            is ConnectionsEvent.Disconnect ->
                runAction(event.otherUserId) { disconnect(event.otherUserId) }
            ConnectionsEvent.CreateInvite -> performCreateInvite()
            ConnectionsEvent.ClearError ->
                _state.update { it.copy(error = null) }
        }
    }

    /**
     * Refresh-all path: the three list fetches run sequentially inside
     * a single coroutine so a failure of any one list surfaces a
     * single error and doesn't fan out to multiple snackbars (fail-fast
     * on the first non-Success). Display-name resolution is a fourth
     * round-trip deferred until all three list fetches succeed (avoids
     * resolving names for empty lists).
     *
     * **Exception boundary on [resolveDisplayNames]**: the three list
     * fetches return [UseCaseResult] and never throw (FriendRepository
     * contract). [resolveDisplayNames] wraps `UserRepository.getByIds`
     * which is a plain `suspend fun` that CAN throw (network timeout,
     * Ktor `HttpRequestTimeoutException`). An uncaught throw there
     * would propagate past `runAction`'s `finally` (which only clears
     * the in-flight set) and leave `isLoading = true` forever — a
     * permanent spinner. So the name-resolution block is wrapped:
     * `CancellationException` rethrows per coroutine convention; any
     * other exception leaves [ConnectionsState.displayNames] as-is
     * (stale names are better than a hung screen — the UI falls back
     * to "Unknown user" for unresolved ids anyway).
     */
    private suspend fun refreshAll() {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val friendsResult = listFriends()) {
            is UseCaseResult.Failure -> {
                _state.update {
                    it.copy(isLoading = false, error = friendsResult.error.toErrorMessage())
                }
                return
            }
            is UseCaseResult.Success -> {
                _state.update { it.copy(friends = friendsResult.value) }
            }
        }
        when (val pendingResult = listPending()) {
            is UseCaseResult.Failure -> {
                _state.update {
                    it.copy(isLoading = false, error = pendingResult.error.toErrorMessage())
                }
                return
            }
            is UseCaseResult.Success -> {
                _state.update { it.copy(pending = pendingResult.value) }
            }
        }
        when (val invitesResult = listInvites()) {
            is UseCaseResult.Failure -> {
                _state.update {
                    it.copy(isLoading = false, error = invitesResult.error.toErrorMessage())
                }
                return
            }
            is UseCaseResult.Success -> {
                _state.update { it.copy(invites = invitesResult.value) }
            }
        }
        // Resolve display names for every distinct other-user id across
        // friends + pending. Caller id is excluded by construction
        // because `otherUserId` returns the non-caller side. If
        // callerId hasn't resolved yet (sub-second window at init),
        // skip name resolution this pass — the next refresh after
        // tab tap or pull-to-refresh picks it up.
        val callerId = _state.value.callerId
        if (callerId != null) {
            val ids =
                buildSet {
                    _state.value.friends.forEach { add(it.otherUserId(callerId)) }
                    _state.value.pending.forEach { add(it.otherUserId(callerId)) }
                }.toList()
            if (ids.isNotEmpty()) {
                try {
                    val names = resolveDisplayNames(ids)
                    _state.update { it.copy(displayNames = names) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Leave displayNames untouched — the UI falls back
                    // to "Unknown user" for any unresolved id. A failed
                    // name lookup must NOT hang the screen on the
                    // spinner (isLoading is cleared below regardless).
                }
            } else {
                _state.update { it.copy(displayNames = emptyMap()) }
            }
        }
        _state.update { it.copy(isLoading = false) }
    }

    /**
     * Common per-target action wrapper: marks the target in-flight,
     * dispatches the lambda, on success refreshes the lists (so the
     * row drops off / moves between tabs), on failure surfaces the
     * error inline. The in-flight set is cleared regardless of
     * outcome so the row's spinner stops.
     *
     * Re-tap on the same target while in-flight is silently swallowed
     * by the early-return check on [ConnectionsState.pendingActionTargets].
     * `viewModelScope` defaults to Main dispatcher on both Android and
     * iOS so the read-then-write of the in-flight set is single-threaded
     * by construction; no explicit lock needed.
     */
    private fun runAction(
        otherUserId: String,
        block: suspend () -> UseCaseResult<Unit>,
    ) {
        viewModelScope.launch {
            if (otherUserId in _state.value.pendingActionTargets) return@launch
            _state.update {
                it.copy(pendingActionTargets = it.pendingActionTargets + otherUserId)
            }
            try {
                when (val result = block()) {
                    is UseCaseResult.Success -> refreshAll()
                    is UseCaseResult.Failure ->
                        _state.update {
                            it.copy(error = result.error.toErrorMessage())
                        }
                }
            } finally {
                _state.update {
                    it.copy(pendingActionTargets = it.pendingActionTargets - otherUserId)
                }
            }
        }
    }

    private fun performCreateInvite() {
        // Re-entry guard, same single-threaded reasoning as runAction:
        // performCreateInvite is invoked from onEvent on the Main
        // thread and viewModelScope launches on Main, so this
        // read-then-launch is atomic by construction (no lock needed;
        // `synchronized` is JVM-only and would break the iOS target).
        if (_state.value.isCreatingInvite) return
        viewModelScope.launch {
            _state.update { it.copy(isCreatingInvite = true, error = null) }
            try {
                when (val result = createInvite()) {
                    is UseCaseResult.Success -> {
                        // Prepend the freshly-minted invite so the UI
                        // shows it immediately; refreshAll's listInvites
                        // will reconcile the canonical server-side state
                        // on next refresh.
                        _state.update {
                            it.copy(invites = listOf(result.value) + it.invites)
                        }
                    }
                    is UseCaseResult.Failure ->
                        _state.update {
                            it.copy(error = result.error.toErrorMessage())
                        }
                }
            } finally {
                _state.update { it.copy(isCreatingInvite = false) }
            }
        }
    }
}
