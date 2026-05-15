package io.github.b150005.skeinly.domain.repository

import io.github.b150005.skeinly.domain.model.FriendConnection
import io.github.b150005.skeinly.domain.model.FriendInvite
import io.github.b150005.skeinly.domain.usecase.UseCaseResult

/**
 * Phase 25.1 (ADR-024) — friend graph contract.
 *
 * All methods return [UseCaseResult.Failure] with
 * [io.github.b150005.skeinly.domain.usecase.UseCaseError.RequiresConnectivity]
 * when Supabase is not configured (local-only build), and
 * [io.github.b150005.skeinly.domain.usecase.UseCaseError.SignInRequired]
 * when no session is active. Both short-circuit BEFORE the network
 * round-trip so a CI build with empty `SUPABASE_URL` /
 * `SUPABASE_PUBLISHABLE_KEY` never hits a 401, and a stray invocation
 * from a signed-out screen never reaches the network either.
 *
 * **Sorted-pair invariant**: the application layer is responsible for
 * sorting (callerId, otherUserId) before passing them through. The
 * Repository helpers do this internally; callers pass the OTHER
 * user's id (i.e. not their own auth.uid()) by convention.
 *
 * **Never throws** — failures route via [UseCaseResult.Failure].
 */
interface FriendRepository {
    /** Lists accepted-state edges where the caller is a participant. */
    suspend fun listFriends(): UseCaseResult<List<FriendConnection>>

    /**
     * Lists pending-state edges where the caller is a participant.
     * Includes both inbound (someone-sent-to-me) and outbound
     * (I-sent) requests; the UI discriminates via
     * [FriendConnection.callerIsRequester].
     */
    suspend fun listPending(): UseCaseResult<List<FriendConnection>>

    /**
     * Sends a friend request to [otherUserId]. Atomically writes a
     * `state=pending` row. Returns failure when there's already a
     * pending or accepted edge (UNIQUE PK), when the caller is blocked
     * by / has blocked the other side (RLS rejection or check), or
     * when [otherUserId] equals the caller's own id.
     */
    suspend fun sendRequest(otherUserId: String): UseCaseResult<Unit>

    /**
     * Accepts an inbound pending request from [otherUserId].
     * Transitions the row from `pending` → `accepted` and stamps
     * `accepted_at`. Returns failure if no pending row exists, if
     * the caller IS the requester (cannot accept own request), or
     * if the edge is already accepted/blocked.
     */
    suspend fun acceptRequest(otherUserId: String): UseCaseResult<Unit>

    /**
     * Rejects (= DELETEs) an inbound pending request from [otherUserId].
     * Distinct from [disconnect] semantically: reject is for pending
     * requests; disconnect is for already-accepted friendships.
     */
    suspend fun rejectRequest(otherUserId: String): UseCaseResult<Unit>

    /**
     * Disconnects an accepted friendship — transitions state to
     * `blocked`. Per ADR-024 §(a), the row stays so the other side
     * gets a "this connection was severed" signal on their next
     * query. Use [rejectRequest] for pending state, not this.
     */
    suspend fun disconnect(otherUserId: String): UseCaseResult<Unit>

    /**
     * Symmetric friendship check via the `is_friend()` SQL function
     * (migration 035). Returns true iff there's an accepted edge.
     */
    suspend fun isFriend(otherUserId: String): UseCaseResult<Boolean>

    /**
     * Generates a fresh invite via `create_friend_invite()` RPC
     * (migration 036). The RPC mints token (32-byte URL-safe random)
     * + code (8-char base32-excluding-O/0/I/1) server-side to keep
     * randomness off the KMP `expect/actual` surface.
     */
    suspend fun createInvite(): UseCaseResult<FriendInvite>

    /** Lists the caller's outstanding (non-consumed, non-expired) invites. */
    suspend fun listInvites(): UseCaseResult<List<FriendInvite>>

    /**
     * Redeems a paste-text code via `redeem_friend_invite_code()` RPC
     * (migration 035). Normalizes to upper-case server-side; the
     * caller does not need to pre-normalize. Returns the inviter's
     * user id on success.
     */
    suspend fun redeemCode(code: String): UseCaseResult<String>

    /**
     * Redeems a Universal Link token via `redeem_friend_invite_token()`
     * RPC (migration 035). Returns the inviter's user id on success.
     */
    suspend fun redeemToken(token: String): UseCaseResult<String>
}
