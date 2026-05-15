package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.remote.FriendRemoteOperations
import io.github.b150005.skeinly.domain.model.FriendConnection
import io.github.b150005.skeinly.domain.model.FriendConnectionState
import io.github.b150005.skeinly.domain.model.FriendInvite
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.FriendRepository
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toUseCaseError
import kotlinx.coroutines.CancellationException

/**
 * Phase 25.1 (ADR-024) — implementation of [FriendRepository].
 *
 * Local-only mode (Supabase not configured ⇒ [remote] = null)
 * short-circuits with [UseCaseError.RequiresConnectivity]; signed-out
 * short-circuits with [UseCaseError.SignInRequired]. Same shape as
 * [WipeDataRepositoryImpl] (Phase 27.1) and
 * [DeviceTokenRepositoryImpl] (Phase 24.2e).
 *
 * **Sorted-pair invariant**: every method that mutates
 * `friend_connections` rows sorts `(callerId, otherUserId)` via the
 * [sortPair] helper before delegating to [remote]. The remote port
 * trusts the caller-side ordering; the DB's `friend_connections_sorted_pair`
 * CHECK enforces correctness as defense-in-depth.
 *
 * **No local mirror.** The friendship graph is server-authoritative.
 * Later phases may add a SQLDelight projection for offline read; the
 * Phase 25.1 surface is online-only.
 */
class FriendRepositoryImpl(
    private val remote: FriendRemoteOperations?,
    private val authRepository: AuthRepository,
) : FriendRepository {
    override suspend fun listFriends(): UseCaseResult<List<FriendConnection>> =
        runRemote { ops, _ ->
            ops.listEdges(FriendConnectionState.Accepted)
        }

    override suspend fun listPending(): UseCaseResult<List<FriendConnection>> =
        runRemote { ops, _ ->
            ops.listEdges(FriendConnectionState.Pending)
        }

    override suspend fun sendRequest(otherUserId: String): UseCaseResult<Unit> =
        runRemote { ops, callerId ->
            if (otherUserId == callerId) {
                // Client-side guard before the network round-trip.
                // The DB's `friend_connections_sorted_pair` CHECK would
                // also reject this (user_a < user_b can't hold when
                // both UUIDs are equal) but surfacing it here saves
                // the round-trip + makes the test fixture deterministic
                // without a real DB.
                throw IllegalArgumentException("cannot send friend request to self")
            }
            val (a, b) = sortPair(callerId, otherUserId)
            ops.insertPending(userA = a, userB = b, requesterId = callerId)
        }

    override suspend fun acceptRequest(otherUserId: String): UseCaseResult<Unit> =
        runRemote { ops, callerId ->
            val (a, b) = sortPair(callerId, otherUserId)
            ops.updateState(userA = a, userB = b, newState = FriendConnectionState.Accepted)
        }

    override suspend fun rejectRequest(otherUserId: String): UseCaseResult<Unit> =
        runRemote { ops, callerId ->
            val (a, b) = sortPair(callerId, otherUserId)
            ops.deleteEdge(userA = a, userB = b)
        }

    override suspend fun disconnect(otherUserId: String): UseCaseResult<Unit> =
        runRemote { ops, callerId ->
            val (a, b) = sortPair(callerId, otherUserId)
            ops.updateState(userA = a, userB = b, newState = FriendConnectionState.Blocked)
        }

    override suspend fun isFriend(otherUserId: String): UseCaseResult<Boolean> =
        runRemote { ops, callerId ->
            ops.isFriendOf(userA = callerId, userB = otherUserId)
        }

    override suspend fun createInvite(): UseCaseResult<FriendInvite> =
        runRemote { ops, _ ->
            ops.createInvite()
        }

    override suspend fun listInvites(): UseCaseResult<List<FriendInvite>> =
        runRemote { ops, _ ->
            ops.listOwnInvites()
        }

    override suspend fun redeemCode(code: String): UseCaseResult<String> =
        runRemote { ops, _ ->
            ops.redeemCode(code)
        }

    override suspend fun redeemToken(token: String): UseCaseResult<String> =
        runRemote { ops, _ ->
            ops.redeemToken(token)
        }

    /**
     * Common pre-flight + error-mapping wrapper used by every public
     * method. The lambda receives the resolved [FriendRemoteOperations]
     * and the caller's user id; both are non-null at the lambda site
     * by construction (the offline / unauth short-circuits fire
     * before invocation).
     *
     * Rethrows [CancellationException] per coroutine-cooperative
     * cancellation conventions; maps all other exceptions via
     * [toUseCaseError].
     */
    private suspend inline fun <T> runRemote(
        crossinline block: suspend (ops: FriendRemoteOperations, callerId: String) -> T,
    ): UseCaseResult<T> {
        val ops =
            remote
                ?: return UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
        val callerId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(UseCaseError.SignInRequired)
        return try {
            UseCaseResult.Success(block(ops, callerId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }

    internal companion object {
        /**
         * Returns the two UUIDs sorted lexicographically so the
         * application layer's composite-PK invariant
         * `friend_connections_sorted_pair (user_a < user_b)` holds.
         * String comparison is used because UUIDs in Postgres compare
         * by byte order, and Kotlin string comparison on canonical
         * UUID strings produces the same ordering as the underlying
         * byte representation for hex-formatted UUIDs.
         */
        internal fun sortPair(
            a: String,
            b: String,
        ): Pair<String, String> = if (a < b) a to b else b to a
    }
}
