package io.github.b150005.skeinly.ui.connections

import io.github.b150005.skeinly.domain.model.FriendConnection
import io.github.b150005.skeinly.domain.model.FriendConnectionState
import io.github.b150005.skeinly.domain.model.FriendInvite
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Phase 25.3 (ADR-024 §(e)) — locks the [ConnectionsViewModel] state
 * machine + dispatcher routing. Lambda-seam stubs for the FriendRepository
 * surface keep these tests free of the supabase-kt + Ktor + UserRepository
 * surfaces, mirroring the precedent set by
 * [io.github.b150005.skeinly.ui.settings.WipeDataViewModelTest] and
 * [io.github.b150005.skeinly.ui.auth.MfaEnrollmentViewModelTest].
 *
 * `Dispatchers.setMain(UnconfinedTestDispatcher())` so `viewModelScope`'s
 * launches resolve eagerly without an explicit `advanceUntilIdle()`
 * everywhere; per-test calls to `advanceUntilIdle()` are added where
 * the test needs to wait for a coroutine continuation across an awaited
 * deferred.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val callerId = "00000000-0000-0000-0000-000000000001"
    private val aliceId = "00000000-0000-0000-0000-000000000002"
    private val bobId = "00000000-0000-0000-0000-000000000003"
    private val carolId = "00000000-0000-0000-0000-000000000004"
    private val now: Instant = Clock.System.now()

    private fun acceptedEdge(other: String): FriendConnection {
        val (a, b) = if (callerId < other) callerId to other else other to callerId
        return FriendConnection(
            userA = a,
            userB = b,
            state = FriendConnectionState.Accepted,
            requesterId = callerId,
            createdAt = now,
            acceptedAt = now,
        )
    }

    private fun pendingEdge(
        other: String,
        requester: String,
    ): FriendConnection {
        val (a, b) = if (callerId < other) callerId to other else other to callerId
        return FriendConnection(
            userA = a,
            userB = b,
            state = FriendConnectionState.Pending,
            requesterId = requester,
            createdAt = now,
            acceptedAt = null,
        )
    }

    private fun anInvite(): FriendInvite =
        FriendInvite(
            id = "11111111-1111-1111-1111-111111111111",
            token = "tok-abc",
            code = "ABCD2345",
            expiresAt = now + 14.days,
            consumedAt = null,
            consumedBy = null,
            createdAt = now,
        )

    /**
     * Builder DSL so tests that only care about a subset of the
     * lambda-seam dispatchers don't repeat the full ctor every time.
     */
    private fun build(
        getCallerId: suspend () -> String? = { callerId },
        listFriends: suspend () -> UseCaseResult<List<FriendConnection>> = { UseCaseResult.Success(emptyList()) },
        listPending: suspend () -> UseCaseResult<List<FriendConnection>> = { UseCaseResult.Success(emptyList()) },
        listInvites: suspend () -> UseCaseResult<List<FriendInvite>> = { UseCaseResult.Success(emptyList()) },
        resolveDisplayNames: suspend (List<String>) -> Map<String, String> = { emptyMap() },
        acceptRequest: suspend (String) -> UseCaseResult<Unit> = { UseCaseResult.Success(Unit) },
        rejectRequest: suspend (String) -> UseCaseResult<Unit> = { UseCaseResult.Success(Unit) },
        disconnect: suspend (String) -> UseCaseResult<Unit> = { UseCaseResult.Success(Unit) },
        createInvite: suspend () -> UseCaseResult<FriendInvite> = { UseCaseResult.Success(anInvite()) },
    ) = ConnectionsViewModel(
        getCallerId = getCallerId,
        listFriends = listFriends,
        listPending = listPending,
        listInvites = listInvites,
        resolveDisplayNames = resolveDisplayNames,
        acceptRequest = acceptRequest,
        rejectRequest = rejectRequest,
        disconnect = disconnect,
        createInvite = createInvite,
    )

    @Test
    fun `init resolves callerId and triggers refresh`() =
        runTest {
            val vm =
                build(
                    listFriends = { UseCaseResult.Success(listOf(acceptedEdge(aliceId))) },
                    listPending = { UseCaseResult.Success(listOf(pendingEdge(bobId, requester = bobId))) },
                    listInvites = { UseCaseResult.Success(listOf(anInvite())) },
                    resolveDisplayNames = { ids ->
                        ids.associateWith {
                            when (it) {
                                aliceId -> "Alice"
                                bobId -> "Bob"
                                else -> "?"
                            }
                        }
                    },
                )
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(callerId, state.callerId)
            assertEquals(1, state.friends.size)
            assertEquals(1, state.pending.size)
            assertEquals(1, state.invites.size)
            assertEquals("Alice", state.displayNames[aliceId])
            assertEquals("Bob", state.displayNames[bobId])
            assertFalse(state.isLoading)
        }

    @Test
    fun `SelectTab updates active tab without re-fetching`() =
        runTest {
            var listCallCount = 0
            val vm =
                build(
                    listFriends = {
                        listCallCount++
                        UseCaseResult.Success(emptyList())
                    },
                )
            advanceUntilIdle()
            val initialCount = listCallCount
            vm.onEvent(ConnectionsEvent.SelectTab(ConnectionsTab.Pending))
            assertEquals(ConnectionsTab.Pending, vm.state.value.activeTab)
            assertEquals(initialCount, listCallCount, "SelectTab must not re-fetch")
        }

    @Test
    fun `isInbound is true when caller is NOT requester`() =
        runTest {
            val vm = build()
            advanceUntilIdle()
            // Bob sent a request to caller — caller is NOT requester
            // ⇒ inbound from caller's perspective.
            val inbound = pendingEdge(bobId, requester = bobId)
            assertTrue(vm.state.value.isInbound(inbound))
            // Caller sent a request to Bob — caller IS requester
            // ⇒ outbound from caller's perspective.
            val outbound = pendingEdge(bobId, requester = callerId)
            assertFalse(vm.state.value.isInbound(outbound))
        }

    @Test
    fun `AcceptRequest dispatches to acceptRequest lambda and refreshes lists`() =
        runTest {
            val accepted = mutableListOf<String>()
            var listFriendsCalls = 0
            val vm =
                build(
                    listFriends = {
                        listFriendsCalls++
                        UseCaseResult.Success(emptyList())
                    },
                    acceptRequest = { id ->
                        accepted += id
                        UseCaseResult.Success(Unit)
                    },
                )
            advanceUntilIdle()
            val initialFriendsCalls = listFriendsCalls
            vm.onEvent(ConnectionsEvent.AcceptRequest(aliceId))
            advanceUntilIdle()
            assertEquals(listOf(aliceId), accepted)
            // refreshAll fires after success → listFriends called again.
            assertTrue(listFriendsCalls > initialFriendsCalls)
        }

    @Test
    fun `RejectRequest routes through rejectRequest lambda`() =
        runTest {
            val rejected = mutableListOf<String>()
            val vm =
                build(
                    rejectRequest = { id ->
                        rejected += id
                        UseCaseResult.Success(Unit)
                    },
                )
            advanceUntilIdle()
            vm.onEvent(ConnectionsEvent.RejectRequest(aliceId))
            advanceUntilIdle()
            assertEquals(listOf(aliceId), rejected)
        }

    @Test
    fun `CancelOutboundRequest also routes through rejectRequest lambda`() =
        runTest {
            // The repository uses one DELETE for both reject inbound +
            // cancel outbound. The VM exposes two events for clarity but
            // both must end up at the same dispatcher.
            val rejected = mutableListOf<String>()
            val vm =
                build(
                    rejectRequest = { id ->
                        rejected += id
                        UseCaseResult.Success(Unit)
                    },
                )
            advanceUntilIdle()
            vm.onEvent(ConnectionsEvent.CancelOutboundRequest(bobId))
            advanceUntilIdle()
            assertEquals(listOf(bobId), rejected)
        }

    @Test
    fun `Disconnect dispatches to disconnect lambda`() =
        runTest {
            val disconnected = mutableListOf<String>()
            val vm =
                build(
                    disconnect = { id ->
                        disconnected += id
                        UseCaseResult.Success(Unit)
                    },
                )
            advanceUntilIdle()
            vm.onEvent(ConnectionsEvent.Disconnect(aliceId))
            advanceUntilIdle()
            assertEquals(listOf(aliceId), disconnected)
        }

    @Test
    fun `Action failure surfaces error and does not refresh`() =
        runTest {
            var listFriendsCalls = 0
            val vm =
                build(
                    listFriends = {
                        listFriendsCalls++
                        UseCaseResult.Success(emptyList())
                    },
                    acceptRequest = {
                        UseCaseResult.Failure(UseCaseError.Network(RuntimeException("offline")))
                    },
                )
            advanceUntilIdle()
            val before = listFriendsCalls
            vm.onEvent(ConnectionsEvent.AcceptRequest(aliceId))
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)
            // No refresh after failure (would mask the error).
            assertEquals(before, listFriendsCalls)
        }

    @Test
    fun `In-flight action against a target prevents duplicate dispatches`() =
        runTest {
            val callCount = mutableMapOf<String, Int>()
            val gate = CompletableDeferred<Unit>()
            val vm =
                build(
                    acceptRequest = { id ->
                        callCount[id] = (callCount[id] ?: 0) + 1
                        gate.await()
                        UseCaseResult.Success(Unit)
                    },
                )
            advanceUntilIdle()
            vm.onEvent(ConnectionsEvent.AcceptRequest(aliceId))
            // Spinner now shows for alice.
            assertTrue(vm.state.value.isActionInFlight(aliceId))
            // Re-tap on the SAME target → silently swallowed.
            vm.onEvent(ConnectionsEvent.AcceptRequest(aliceId))
            // Tap on a DIFFERENT target → both run concurrently.
            vm.onEvent(ConnectionsEvent.AcceptRequest(bobId))
            assertTrue(vm.state.value.isActionInFlight(bobId))
            assertEquals(1, callCount[aliceId])
            assertEquals(1, callCount[bobId])
            // Release both.
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(vm.state.value.isActionInFlight(aliceId))
            assertFalse(vm.state.value.isActionInFlight(bobId))
        }

    @Test
    fun `In-flight set clears even when action fails`() =
        runTest {
            val vm =
                build(
                    acceptRequest = {
                        UseCaseResult.Failure(UseCaseError.Network(RuntimeException("oops")))
                    },
                )
            advanceUntilIdle()
            vm.onEvent(ConnectionsEvent.AcceptRequest(aliceId))
            advanceUntilIdle()
            assertFalse(vm.state.value.isActionInFlight(aliceId))
        }

    @Test
    fun `CreateInvite optimistic-prepends the new invite`() =
        runTest {
            val invite = anInvite()
            val vm =
                build(
                    createInvite = { UseCaseResult.Success(invite) },
                )
            advanceUntilIdle()
            assertTrue(
                vm.state.value.invites
                    .isEmpty(),
            )
            vm.onEvent(ConnectionsEvent.CreateInvite)
            advanceUntilIdle()
            assertEquals(listOf(invite), vm.state.value.invites)
            assertFalse(vm.state.value.isCreatingInvite)
        }

    @Test
    fun `CreateInvite while already creating is silently swallowed`() =
        runTest {
            var calls = 0
            val gate = CompletableDeferred<Unit>()
            val vm =
                build(
                    createInvite = {
                        calls++
                        gate.await()
                        UseCaseResult.Success(anInvite())
                    },
                )
            advanceUntilIdle()
            vm.onEvent(ConnectionsEvent.CreateInvite)
            assertTrue(vm.state.value.isCreatingInvite)
            vm.onEvent(ConnectionsEvent.CreateInvite)
            vm.onEvent(ConnectionsEvent.CreateInvite)
            assertEquals(1, calls)
            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `CreateInvite failure surfaces error and resets isCreatingInvite`() =
        runTest {
            val vm =
                build(
                    createInvite = {
                        UseCaseResult.Failure(UseCaseError.Network(RuntimeException("net")))
                    },
                )
            advanceUntilIdle()
            vm.onEvent(ConnectionsEvent.CreateInvite)
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.isCreatingInvite)
        }

    @Test
    fun `Refresh re-runs all three list dispatchers`() =
        runTest {
            var friendsCalls = 0
            var pendingCalls = 0
            var invitesCalls = 0
            val vm =
                build(
                    listFriends = {
                        friendsCalls++
                        UseCaseResult.Success(emptyList())
                    },
                    listPending = {
                        pendingCalls++
                        UseCaseResult.Success(emptyList())
                    },
                    listInvites = {
                        invitesCalls++
                        UseCaseResult.Success(emptyList())
                    },
                )
            advanceUntilIdle()
            val baseF = friendsCalls
            val baseP = pendingCalls
            val baseI = invitesCalls
            vm.onEvent(ConnectionsEvent.Refresh)
            advanceUntilIdle()
            assertTrue(friendsCalls > baseF)
            assertTrue(pendingCalls > baseP)
            assertTrue(invitesCalls > baseI)
        }

    @Test
    fun `listFriends failure halts refresh and surfaces error`() =
        runTest {
            var pendingCalls = 0
            val vm =
                build(
                    listFriends = {
                        UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
                    },
                    listPending = {
                        pendingCalls++
                        UseCaseResult.Success(emptyList())
                    },
                )
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)
            // listPending never runs after listFriends failure.
            assertEquals(0, pendingCalls)
            assertFalse(vm.state.value.isLoading)
        }

    @Test
    fun `ClearError drops error`() =
        runTest {
            val vm =
                build(
                    listFriends = {
                        UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
                    },
                )
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)
            vm.onEvent(ConnectionsEvent.ClearError)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `displayNames batch-fetches every distinct other-user across friends and pending`() =
        runTest {
            val capturedIds = mutableListOf<String>()
            val vm =
                build(
                    listFriends = { UseCaseResult.Success(listOf(acceptedEdge(aliceId))) },
                    listPending =
                        {
                            UseCaseResult.Success(
                                listOf(
                                    pendingEdge(bobId, requester = bobId),
                                    pendingEdge(carolId, requester = callerId),
                                ),
                            )
                        },
                    resolveDisplayNames = { ids ->
                        capturedIds += ids
                        ids.associateWith { it.takeLast(1) }
                    },
                )
            advanceUntilIdle()
            // Three distinct other-users (Alice, Bob, Carol). Caller's
            // own id MUST NOT be in the batch (otherUserId excludes it).
            val captured = capturedIds.toSet()
            assertTrue(aliceId in captured)
            assertTrue(bobId in captured)
            assertTrue(carolId in captured)
            assertFalse(callerId in captured)
        }

    @Test
    fun `displayNames stays empty when no friends or pending`() =
        runTest {
            var resolveCalls = 0
            val vm =
                build(
                    resolveDisplayNames = { _ ->
                        resolveCalls++
                        emptyMap()
                    },
                )
            advanceUntilIdle()
            assertTrue(
                vm.state.value.displayNames
                    .isEmpty(),
            )
            assertEquals(0, resolveCalls, "resolveDisplayNames must not run for empty inputs")
        }

    @Test
    fun `null callerId skips display-name resolution gracefully`() =
        runTest {
            var resolveCalls = 0
            val vm =
                build(
                    getCallerId = { null },
                    listFriends = { UseCaseResult.Success(listOf(acceptedEdge(aliceId))) },
                    resolveDisplayNames = { _ ->
                        resolveCalls++
                        emptyMap()
                    },
                )
            advanceUntilIdle()
            // Friends still loaded into state — the resolution pass just
            // skipped because we have no caller perspective to derive
            // the "other side" from. Invariant: this screen is only
            // mounted while authenticated (NavGraph gates Settings →
            // Connections behind isSignedIn), so a null callerId is a
            // sub-second cold-start window, not a steady state. The VM
            // intentionally still accepts the list into state so the
            // next refresh (after callerId resolves) renders without a
            // re-fetch.
            assertEquals(1, vm.state.value.friends.size)
            assertEquals(0, resolveCalls)
            assertNull(vm.state.value.callerId)
        }

    @Test
    fun `resolveDisplayNames throwing does not hang the screen on loading`() =
        runTest {
            // HIGH regression guard (code review 2026-05-15): list
            // fetches return UseCaseResult and never throw, but
            // resolveDisplayNames wraps UserRepository.getByIds which
            // CAN throw (network timeout). An uncaught throw there used
            // to leave isLoading = true forever. The catch boundary in
            // refreshAll must absorb it and still clear isLoading.
            val vm =
                build(
                    listFriends = { UseCaseResult.Success(listOf(acceptedEdge(aliceId))) },
                    resolveDisplayNames = { _ ->
                        throw RuntimeException("getByIds network timeout")
                    },
                )
            advanceUntilIdle()
            assertFalse(vm.state.value.isLoading, "screen must not hang on the spinner")
            assertEquals(1, vm.state.value.friends.size, "list still loaded")
            // Names unresolved → empty map → UI falls back to
            // "Unknown user". No error surfaced (name resolution is
            // best-effort, not a hard failure).
            assertTrue(
                vm.state.value.displayNames
                    .isEmpty(),
            )
        }

    @Test
    fun `Refresh while already loading is silently swallowed`() =
        runTest {
            // LOW regression guard (code review 2026-05-15): a fast
            // double Refresh dispatch must not launch concurrent
            // refreshAll coroutines that race to overwrite the lists.
            var friendsCalls = 0
            val gate = CompletableDeferred<Unit>()
            val vm =
                build(
                    listFriends = {
                        friendsCalls++
                        gate.await()
                        UseCaseResult.Success(emptyList())
                    },
                )
            // init's refreshAll is now in-flight (isLoading == true,
            // suspended on the gate).
            assertTrue(vm.state.value.isLoading)
            val callsAfterInit = friendsCalls
            vm.onEvent(ConnectionsEvent.Refresh)
            vm.onEvent(ConnectionsEvent.Refresh)
            // Both Refresh dispatches no-op because isLoading is true.
            assertEquals(callsAfterInit, friendsCalls)
            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `isActionInFlight returns false for unrelated targets`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val vm =
                build(
                    acceptRequest = {
                        gate.await()
                        UseCaseResult.Success(Unit)
                    },
                )
            advanceUntilIdle()
            vm.onEvent(ConnectionsEvent.AcceptRequest(aliceId))
            assertTrue(vm.state.value.isActionInFlight(aliceId))
            assertFalse(vm.state.value.isActionInFlight(bobId))
            gate.complete(Unit)
            advanceUntilIdle()
        }
}
