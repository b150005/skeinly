package io.github.b150005.knitnote.ui.pullrequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.UserRepository
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.GetIncomingPullRequestsUseCase
import io.github.b150005.knitnote.domain.usecase.GetOutgoingPullRequestsUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toErrorMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * Read-side filter for [PullRequestListScreen] (ADR-014 §6).
 *
 * Same data model on both branches; the screen renders a single list scoped
 * to the chosen direction. Tracked client-side via [PullRequestListEvent.SelectFilter]
 * so a user can toggle between the two without re-navigating.
 *
 * `@Serializable` so the [io.github.b150005.knitnote.ui.navigation.PullRequestList]
 * route can carry it as a default filter.
 */
@Serializable
enum class PullRequestFilter {
    INCOMING,
    OUTGOING,
}

data class PullRequestListState(
    val filter: PullRequestFilter = PullRequestFilter.INCOMING,
    val pullRequests: List<PullRequest> = emptyList(),
    val users: Map<String, User> = emptyMap(),
    val isLoading: Boolean = true,
    val error: ErrorMessage? = null,
)

sealed interface PullRequestListEvent {
    data class SelectFilter(
        val filter: PullRequestFilter,
    ) : PullRequestListEvent

    data object ClearError : PullRequestListEvent
}

/**
 * Phase 38.2 (ADR-014 §6, §8) — read-only list of pull requests for the
 * authenticated user. Parameterized on [defaultFilter]; the chip row in the UI
 * lets the user toggle between Incoming and Outgoing without re-navigating.
 *
 * **Cold-launch seeding contract.** [io.github.b150005.knitnote.domain.repository.PullRequestRepository.observeIncomingForOwner]
 * is local-only with no remote seed — a fresh install observing an empty cache
 * would render the empty state forever. The ViewModel kicks the suspend
 * `invoke()` overload first to backfill the local cache from the remote, then
 * relies on the live observe Flow + Realtime channel for further updates.
 *
 * **Filter switching.** The filter chip is a UI-state event that triggers a
 * fresh seed-then-observe cycle for the newly selected filter. Implemented via
 * `flatMapLatest` so a rapid toggle cancels any in-flight observe collection
 * before re-subscribing — protects against the older filter's emissions racing
 * past the new filter's into [_state].
 *
 * **Author resolution.** Each PR's `authorId` is a UUID, not a display name.
 * The ViewModel resolves `users: Map<String, User>` after each list update
 * via [UserRepository.getByIds] (same pattern as
 * [io.github.b150005.knitnote.ui.activityfeed.ActivityFeedViewModel]). UI
 * falls back to `label_someone` when the lookup misses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PullRequestListViewModel(
    defaultFilter: PullRequestFilter,
    private val getIncoming: GetIncomingPullRequestsUseCase,
    private val getOutgoing: GetOutgoingPullRequestsUseCase,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PullRequestListState(filter = defaultFilter))
    val state: StateFlow<PullRequestListState> = _state.asStateFlow()

    private val _filter = MutableStateFlow(defaultFilter)

    init {
        observeWithFilterSwitching()
    }

    fun onEvent(event: PullRequestListEvent) {
        when (event) {
            is PullRequestListEvent.SelectFilter -> {
                if (_filter.value != event.filter) {
                    // Reset users — stale entries from the old filter must not
                    // be reused on the new filter (an authorId collision could
                    // silently render a wrong display name; resolveUsers'
                    // dedup-by-key would also skip a re-fetch under the new
                    // filter that legitimately needs it).
                    _state.update {
                        it.copy(
                            filter = event.filter,
                            isLoading = true,
                            error = null,
                            users = emptyMap(),
                        )
                    }
                    _filter.value = event.filter
                }
            }

            PullRequestListEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun observeWithFilterSwitching() {
        val ownerId = authRepository.getCurrentUserId()
        if (ownerId == null) {
            _state.update {
                it.copy(isLoading = false, error = ErrorMessage.SignInRequired)
            }
            return
        }

        _filter
            .flatMapLatest { filter ->
                // Seed-then-observe inside a single `flow { }` builder so both
                // the seed and the live subscription share `flatMapLatest`'s
                // cancellable scope. A pre-fix version launched the seed via
                // `viewModelScope.launch` and emitted only the live observe
                // downstream — the seed's `_state.update` survived a filter
                // switch and could surface the prior filter's error after the
                // user had already moved on. emitAll keeps the live subscription
                // active for as long as `flatMapLatest` keeps this branch alive.
                flow {
                    val seed =
                        when (filter) {
                            PullRequestFilter.INCOMING -> getIncoming(ownerId)
                            PullRequestFilter.OUTGOING -> getOutgoing(ownerId)
                        }
                    if (seed is UseCaseResult.Failure) {
                        _state.update { it.copy(error = seed.error.toErrorMessage()) }
                    }
                    emitAll(
                        when (filter) {
                            PullRequestFilter.INCOMING -> getIncoming.observe(ownerId)
                            PullRequestFilter.OUTGOING -> getOutgoing.observe(ownerId)
                        },
                    )
                }
            }.onEach { prs ->
                _state.update { it.copy(pullRequests = prs, isLoading = false) }
                resolveUsers(prs)
            }.catch { e ->
                _state.update {
                    it.copy(isLoading = false, error = ErrorMessage.LoadFailed)
                }
            }.launchIn(viewModelScope)
    }

    private suspend fun resolveUsers(prs: List<PullRequest>) {
        // Snapshot read on the keys-to-fetch set is fine — we only need to
        // know which authorIds are *probably* missing. The actual merge below
        // re-reads `current.users` inside `_state.update` so a concurrent
        // resolveUsers landing during the suspend cannot have its newly-added
        // authors clobbered by this call's stale view (the
        // `ActivityFeedViewModel.resolveUsers` reference pattern carries this
        // race; 38.2 closes it on its own surface).
        val newAuthorIds = prs.mapNotNull { it.authorId }.distinct() - _state.value.users.keys
        if (newAuthorIds.isEmpty()) return

        val resolved = userRepository.getByIds(newAuthorIds).associateBy { it.id }
        _state.update { current -> current.copy(users = current.users + resolved) }
    }
}
