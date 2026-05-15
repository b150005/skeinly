package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.remote.FriendRemoteOperations
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.FriendConnection
import io.github.b150005.skeinly.domain.model.FriendConnectionState
import io.github.b150005.skeinly.domain.model.FriendInvite
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

// Phase 25.1 (ADR-024 §Phase 25.1) — covers the [FriendRepositoryImpl]
// contract: short-circuit guards, sorted-pair invariant, happy paths,
// error mapping, cancellation propagation. Pattern mirrors
// WipeDataRepositoryImplTest (Phase 27.1) + DeviceTokenRepositoryImplTest
// (Phase 24.2e).

/**
 * Trip-wire exception whose `simpleName` matches the
 * `networkExceptionPatterns` heuristic in
 * `io.github.b150005.skeinly.domain.usecase.toUseCaseError` — surfaces
 * as [UseCaseError.Network] without depending on a platform-specific
 * IOException type.
 */
private class FriendFakeIOException(
    message: String,
) : Exception(message)

private class FakeFriendRemote : FriendRemoteOperations {
    val edgesByState: MutableMap<FriendConnectionState, MutableList<FriendConnection>> = mutableMapOf()
    val invites: MutableList<FriendInvite> = mutableListOf()
    var isFriendResult: Boolean = false
    var inviterIdReturnedOnRedeem: String = "inviter-id"

    var nextError: Throwable? = null
    var callsListEdges: Int = 0
    var callsInsertPending: Int = 0
    var callsUpdateState: Int = 0
    var callsDeleteEdge: Int = 0
    var callsIsFriend: Int = 0
    var callsCreateInvite: Int = 0
    var callsListInvites: Int = 0
    var callsRedeemCode: Int = 0
    var callsRedeemToken: Int = 0

    var lastInsertPending: Triple<String, String, String>? = null
    var lastUpdateState: Triple<String, String, FriendConnectionState>? = null
    var lastDeleteEdge: Pair<String, String>? = null
    var lastIsFriendArgs: Pair<String, String>? = null
    var lastRedeemCode: String? = null
    var lastRedeemToken: String? = null

    private fun maybeThrow() {
        nextError?.let {
            nextError = null
            throw it
        }
    }

    override suspend fun listEdges(state: FriendConnectionState): List<FriendConnection> {
        callsListEdges++
        maybeThrow()
        return edgesByState[state]?.toList() ?: emptyList()
    }

    override suspend fun insertPending(
        userA: String,
        userB: String,
        requesterId: String,
    ) {
        callsInsertPending++
        lastInsertPending = Triple(userA, userB, requesterId)
        maybeThrow()
    }

    override suspend fun updateState(
        userA: String,
        userB: String,
        newState: FriendConnectionState,
    ) {
        callsUpdateState++
        lastUpdateState = Triple(userA, userB, newState)
        maybeThrow()
    }

    override suspend fun deleteEdge(
        userA: String,
        userB: String,
    ) {
        callsDeleteEdge++
        lastDeleteEdge = userA to userB
        maybeThrow()
    }

    override suspend fun isFriendOf(
        userA: String,
        userB: String,
    ): Boolean {
        callsIsFriend++
        lastIsFriendArgs = userA to userB
        maybeThrow()
        return isFriendResult
    }

    override suspend fun createInvite(): FriendInvite {
        callsCreateInvite++
        maybeThrow()
        return SAMPLE_INVITE
    }

    override suspend fun listOwnInvites(): List<FriendInvite> {
        callsListInvites++
        maybeThrow()
        return invites.toList()
    }

    override suspend fun redeemCode(code: String): String {
        callsRedeemCode++
        lastRedeemCode = code
        maybeThrow()
        return inviterIdReturnedOnRedeem
    }

    override suspend fun redeemToken(token: String): String {
        callsRedeemToken++
        lastRedeemToken = token
        maybeThrow()
        return inviterIdReturnedOnRedeem
    }
}

private val SAMPLE_INVITE =
    FriendInvite(
        id = "invite-id",
        token = "T".repeat(43),
        code = "ABCD2345",
        expiresAt = Instant.parse("2099-01-01T00:00:00Z"),
        consumedAt = null,
        consumedBy = null,
        createdAt = Instant.parse("2026-05-15T00:00:00Z"),
    )

private val SAMPLE_FRIEND =
    FriendConnection(
        userA = "aaaa1111-1111-1111-1111-111111111111",
        userB = "bbbb2222-2222-2222-2222-222222222222",
        state = FriendConnectionState.Accepted,
        requesterId = "aaaa1111-1111-1111-1111-111111111111",
        createdAt = Instant.parse("2026-05-15T00:00:00Z"),
        acceptedAt = Instant.parse("2026-05-15T00:01:00Z"),
    )

class FriendRepositoryImplTest {
    /** Returns user id sorted to be < the SAMPLE_FRIEND's userB. */
    private val callerId = "aaaa1111-1111-1111-1111-111111111111"
    private val otherId = "bbbb2222-2222-2222-2222-222222222222"

    private fun makeAuth(userId: String? = callerId): FakeAuthRepository =
        FakeAuthRepository().also { auth ->
            if (userId != null) {
                auth.setAuthState(AuthState.Authenticated(userId = userId, email = "test@example.com"))
            }
        }

    // ========================================================================
    // Short-circuit guards
    // ========================================================================

    @Test
    fun listFriends_returns_RequiresConnectivity_when_remote_is_null() =
        runTest {
            val repo = FriendRepositoryImpl(remote = null, authRepository = makeAuth())
            val result = repo.listFriends()
            val failure = assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.RequiresConnectivity, failure.error)
        }

    @Test
    fun listFriends_returns_SignInRequired_when_not_signed_in() =
        runTest {
            val remote = FakeFriendRemote()
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth(userId = null))
            val result = repo.listFriends()
            val failure = assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.SignInRequired, failure.error)
            assertEquals(0, remote.callsListEdges, "must not contact remote without auth")
        }

    @Test
    fun sendRequest_returns_RequiresConnectivity_when_remote_is_null() =
        runTest {
            val repo = FriendRepositoryImpl(remote = null, authRepository = makeAuth())
            val result = repo.sendRequest(otherId)
            val failure = assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.RequiresConnectivity, failure.error)
        }

    @Test
    fun redeemCode_short_circuits_unauthenticated() =
        runTest {
            val remote = FakeFriendRemote()
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth(userId = null))
            val result = repo.redeemCode("ABCD2345")
            val failure = assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.SignInRequired, failure.error)
            assertEquals(0, remote.callsRedeemCode)
        }

    // ========================================================================
    // listFriends / listPending happy paths
    // ========================================================================

    @Test
    fun listFriends_filters_by_accepted_state() =
        runTest {
            val remote =
                FakeFriendRemote().apply {
                    edgesByState[FriendConnectionState.Accepted] = mutableListOf(SAMPLE_FRIEND)
                    edgesByState[FriendConnectionState.Pending] =
                        mutableListOf(SAMPLE_FRIEND.copy(state = FriendConnectionState.Pending))
                }
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.listFriends()
            val success = assertIs<UseCaseResult.Success<List<FriendConnection>>>(result)
            assertEquals(1, success.value.size)
            assertEquals(FriendConnectionState.Accepted, success.value[0].state)
        }

    @Test
    fun listPending_filters_by_pending_state() =
        runTest {
            val pending = SAMPLE_FRIEND.copy(state = FriendConnectionState.Pending, acceptedAt = null)
            val remote =
                FakeFriendRemote().apply {
                    edgesByState[FriendConnectionState.Pending] = mutableListOf(pending)
                }
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.listPending()
            val success = assertIs<UseCaseResult.Success<List<FriendConnection>>>(result)
            assertEquals(1, success.value.size)
            assertEquals(FriendConnectionState.Pending, success.value[0].state)
        }

    @Test
    fun listFriends_returns_empty_list_when_no_edges() =
        runTest {
            val remote = FakeFriendRemote()
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.listFriends()
            val success = assertIs<UseCaseResult.Success<List<FriendConnection>>>(result)
            assertTrue(success.value.isEmpty())
        }

    // ========================================================================
    // Sorted-pair invariant — caller < other vs caller > other
    // ========================================================================

    @Test
    fun sendRequest_sorts_caller_less_than_other() =
        runTest {
            // callerId starts with "aaaa..." which is < otherId "bbbb..."
            // so sort order is (callerId, otherId).
            val remote = FakeFriendRemote()
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            repo.sendRequest(otherId)
            assertEquals(Triple(callerId, otherId, callerId), remote.lastInsertPending)
        }

    @Test
    fun sendRequest_sorts_caller_greater_than_other() =
        runTest {
            // Flip: caller is "bbbb..." and other is "aaaa..." — sort
            // order should swap so user_a < user_b holds.
            val remote = FakeFriendRemote()
            val swappedCaller = "bbbb2222-2222-2222-2222-222222222222"
            val swappedOther = "aaaa1111-1111-1111-1111-111111111111"
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth(userId = swappedCaller))
            repo.sendRequest(swappedOther)
            // Expected: (aaaa, bbbb, requester=bbbb) — userA<userB always.
            assertEquals(
                Triple(swappedOther, swappedCaller, swappedCaller),
                remote.lastInsertPending,
            )
        }

    @Test
    fun acceptRequest_sorts_pair_before_update() =
        runTest {
            val remote = FakeFriendRemote()
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            repo.acceptRequest(otherId)
            val update = remote.lastUpdateState
            assertEquals(callerId, update?.first)
            assertEquals(otherId, update?.second)
            assertEquals(FriendConnectionState.Accepted, update?.third)
        }

    @Test
    fun disconnect_transitions_to_blocked() =
        runTest {
            val remote = FakeFriendRemote()
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            repo.disconnect(otherId)
            assertEquals(FriendConnectionState.Blocked, remote.lastUpdateState?.third)
        }

    @Test
    fun rejectRequest_deletes_the_edge() =
        runTest {
            val remote = FakeFriendRemote()
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            repo.rejectRequest(otherId)
            assertEquals(callerId to otherId, remote.lastDeleteEdge)
            assertEquals(1, remote.callsDeleteEdge)
        }

    @Test
    fun sendRequest_rejects_self_target() =
        runTest {
            val remote = FakeFriendRemote()
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.sendRequest(callerId)
            // Self-target throws IllegalArgumentException inside the
            // runRemote wrapper, which maps it to Unknown via
            // toUseCaseError. The test pins the surface
            // ("don't contact remote") without overconstraining the
            // exact error variant.
            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(failure.error)
            assertEquals(0, remote.callsInsertPending, "self-target should not contact remote")
        }

    // ========================================================================
    // isFriend — passes both caller + other to remote
    // ========================================================================

    @Test
    fun isFriend_passes_caller_and_other_to_remote() =
        runTest {
            val remote =
                FakeFriendRemote().apply {
                    isFriendResult = true
                }
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.isFriend(otherId)
            val success = assertIs<UseCaseResult.Success<Boolean>>(result)
            assertTrue(success.value)
            assertEquals(callerId to otherId, remote.lastIsFriendArgs)
        }

    @Test
    fun isFriend_returns_false_when_not_friends() =
        runTest {
            val remote =
                FakeFriendRemote().apply {
                    isFriendResult = false
                }
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.isFriend(otherId)
            val success = assertIs<UseCaseResult.Success<Boolean>>(result)
            assertFalse(success.value)
        }

    // ========================================================================
    // Invite happy paths
    // ========================================================================

    @Test
    fun createInvite_returns_remote_invite() =
        runTest {
            val remote = FakeFriendRemote()
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.createInvite()
            val success = assertIs<UseCaseResult.Success<FriendInvite>>(result)
            assertEquals(SAMPLE_INVITE, success.value)
            assertEquals(1, remote.callsCreateInvite)
        }

    @Test
    fun listInvites_returns_remote_list() =
        runTest {
            val remote =
                FakeFriendRemote().apply {
                    invites.add(SAMPLE_INVITE)
                    invites.add(SAMPLE_INVITE.copy(id = "second-invite"))
                }
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.listInvites()
            val success = assertIs<UseCaseResult.Success<List<FriendInvite>>>(result)
            assertEquals(2, success.value.size)
        }

    @Test
    fun redeemCode_passes_code_verbatim() =
        runTest {
            val remote =
                FakeFriendRemote().apply {
                    inviterIdReturnedOnRedeem = "abcd1234-inviter"
                }
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.redeemCode("ABCD2345")
            val success = assertIs<UseCaseResult.Success<String>>(result)
            assertEquals("abcd1234-inviter", success.value)
            assertEquals("ABCD2345", remote.lastRedeemCode)
        }

    @Test
    fun redeemToken_passes_token_verbatim() =
        runTest {
            val remote = FakeFriendRemote()
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            val token = "T".repeat(43)
            repo.redeemToken(token)
            assertEquals(token, remote.lastRedeemToken)
        }

    // ========================================================================
    // Error mapping — IOException → Network, arbitrary → Unknown
    // ========================================================================

    @Test
    fun listFriends_maps_IOException_to_Network() =
        runTest {
            val remote =
                FakeFriendRemote().apply {
                    nextError = FriendFakeIOException("read timeout")
                }
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.listFriends()
            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Network>(failure.error)
        }

    @Test
    fun redeemCode_maps_arbitrary_exception_to_Unknown() =
        runTest {
            val remote =
                FakeFriendRemote().apply {
                    nextError = IllegalStateException("invite not found")
                }
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.redeemCode("BADBAD12")
            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(failure.error)
        }

    // ========================================================================
    // Cancellation — must NOT be swallowed
    // ========================================================================

    @Test
    fun sendRequest_propagates_CancellationException() =
        runTest {
            val remote =
                FakeFriendRemote().apply {
                    nextError = CancellationException("test cancellation")
                }
            val repo = FriendRepositoryImpl(remote = remote, authRepository = makeAuth())
            assertFailsWith<CancellationException> {
                repo.sendRequest(otherId)
            }
        }

    // ========================================================================
    // Sorted-pair util — direct unit coverage for the LEAST/GREATEST helper
    // ========================================================================

    @Test
    fun sortPair_returns_alphabetically_sorted_pair() {
        val (a, b) =
            FriendRepositoryImpl.sortPair(
                "bbbb-id",
                "aaaa-id",
            )
        assertEquals("aaaa-id", a)
        assertEquals("bbbb-id", b)
    }

    @Test
    fun sortPair_preserves_already_sorted_pair() {
        val (a, b) =
            FriendRepositoryImpl.sortPair(
                "aaaa-id",
                "bbbb-id",
            )
        assertEquals("aaaa-id", a)
        assertEquals("bbbb-id", b)
    }

    // ========================================================================
    // FriendConnection.otherUserId helper
    // ========================================================================

    @Test
    fun otherUserId_returns_userB_when_caller_is_userA() {
        val other = SAMPLE_FRIEND.otherUserId(SAMPLE_FRIEND.userA)
        assertEquals(SAMPLE_FRIEND.userB, other)
    }

    @Test
    fun otherUserId_returns_userA_when_caller_is_userB() {
        val other = SAMPLE_FRIEND.otherUserId(SAMPLE_FRIEND.userB)
        assertEquals(SAMPLE_FRIEND.userA, other)
    }

    @Test
    fun otherUserId_throws_when_caller_not_a_participant() {
        assertFailsWith<IllegalStateException> {
            SAMPLE_FRIEND.otherUserId("not-a-participant")
        }
    }

    @Test
    fun callerIsRequester_returns_true_when_caller_sent_request() {
        assertTrue(SAMPLE_FRIEND.callerIsRequester(SAMPLE_FRIEND.requesterId))
    }

    @Test
    fun callerIsRequester_returns_false_when_caller_received_request() {
        val other =
            if (SAMPLE_FRIEND.requesterId == SAMPLE_FRIEND.userA) SAMPLE_FRIEND.userB else SAMPLE_FRIEND.userA
        assertFalse(SAMPLE_FRIEND.callerIsRequester(other))
    }

    // ========================================================================
    // FriendInvite.isOutstanding
    // ========================================================================

    @Test
    fun isOutstanding_returns_true_when_unconsumed_and_unexpired() {
        val now = Instant.parse("2026-05-15T12:00:00Z")
        assertTrue(SAMPLE_INVITE.isOutstanding(now))
    }

    @Test
    fun isOutstanding_returns_false_when_expired() {
        val now = Instant.parse("2099-02-01T00:00:00Z") // after SAMPLE_INVITE.expiresAt
        assertFalse(SAMPLE_INVITE.isOutstanding(now))
    }

    @Test
    fun isOutstanding_returns_false_when_consumed() {
        val consumed =
            SAMPLE_INVITE.copy(
                consumedAt = Instant.parse("2026-05-16T00:00:00Z"),
                consumedBy = "redeemer-id",
            )
        val now = Instant.parse("2026-05-15T12:00:00Z")
        assertFalse(consumed.isOutstanding(now))
    }

    // ========================================================================
    // FriendConnectionState wire mapping
    // ========================================================================

    @Test
    fun friendConnectionState_fromWire_roundtrip() {
        FriendConnectionState.entries.forEach { state ->
            assertEquals(state, FriendConnectionState.fromWire(state.wireValue))
        }
    }

    @Test
    fun friendConnectionState_fromWire_throws_on_unknown() {
        assertFailsWith<IllegalStateException> {
            FriendConnectionState.fromWire("not-a-state")
        }
    }
}
