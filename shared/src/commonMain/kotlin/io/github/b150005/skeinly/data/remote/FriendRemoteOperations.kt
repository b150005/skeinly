package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.domain.model.FriendConnection
import io.github.b150005.skeinly.domain.model.FriendConnectionState
import io.github.b150005.skeinly.domain.model.FriendInvite
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Instant

/**
 * Phase 25.1 (ADR-024) — write/read port for `friend_connections` +
 * `friend_invites` tables + the four supporting RPCs.
 *
 * Exists as an interface so [FriendRepositoryImpl] tests can inject
 * an in-memory fake. Same precedent as
 * [WipeDataRemoteOperations] (ADR-023 §3.1) and
 * [DeviceTokenRemoteOperations] (ADR-017 §3.5).
 *
 * The repository sorts user pairs before calling [insertPending],
 * [updateState], [deleteEdge] — this interface accepts sorted
 * (userA, userB) and trusts the caller. The repository's contract
 * absorbs the sorting responsibility from the UI / ViewModel layer.
 */
interface FriendRemoteOperations {
    suspend fun listEdges(state: FriendConnectionState): List<FriendConnection>

    suspend fun insertPending(
        userA: String,
        userB: String,
        requesterId: String,
    )

    suspend fun updateState(
        userA: String,
        userB: String,
        newState: FriendConnectionState,
    )

    suspend fun deleteEdge(
        userA: String,
        userB: String,
    )

    /** Symmetric friendship check — passes both UUIDs to is_friend(). */
    suspend fun isFriendOf(
        userA: String,
        userB: String,
    ): Boolean

    suspend fun createInvite(): FriendInvite

    suspend fun listOwnInvites(): List<FriendInvite>

    /** Returns inviter user id on success. */
    suspend fun redeemCode(code: String): String

    /** Returns inviter user id on success. */
    suspend fun redeemToken(token: String): String
}

/**
 * Phase 25.1 (ADR-024) — Supabase implementation of
 * [FriendRemoteOperations].
 *
 * Reads happen via `postgrest["friend_connections"].select()` filtered
 * by state. The RLS policy `Users can read own friend connections`
 * (migration 035) scopes results to rows where `auth.uid()` is a
 * participant.
 *
 * Mutations on `friend_connections` happen via direct CRUD (INSERT for
 * sendRequest, UPDATE for acceptRequest / disconnect, DELETE for
 * rejectRequest). Invite-related operations route via SECURITY
 * DEFINER RPCs because they need to bypass RLS (e.g. the redeemer
 * cannot SELECT the inviter's invite row directly).
 *
 * **Outstanding-invite filtering happens client-side** (`isOutstanding`)
 * rather than via a Postgrest `IS NULL` filter — the per-user invite
 * list is small (~1-5 invites at any time) so client-side filtering
 * is cheaper than building a more complex query DSL surface, and
 * keeps this port portable across supabase-kt minor releases that
 * shuffle the filter operator API.
 */
class RemoteFriendDataSource(
    private val supabaseClient: SupabaseClient,
) : FriendRemoteOperations {
    override suspend fun listEdges(state: FriendConnectionState): List<FriendConnection> {
        val rows =
            supabaseClient.postgrest["friend_connections"]
                .select(Columns.list(SELECT_COLS)) {
                    filter {
                        eq("state", state.wireValue)
                    }
                }.decodeList<FriendConnectionRow>()
        return rows.map { it.toDomain() }
    }

    override suspend fun insertPending(
        userA: String,
        userB: String,
        requesterId: String,
    ) {
        supabaseClient.postgrest["friend_connections"]
            .insert(
                FriendConnectionInsertPayload(
                    userA = userA,
                    userB = userB,
                    state = FriendConnectionState.Pending.wireValue,
                    requesterId = requesterId,
                ),
            )
    }

    override suspend fun updateState(
        userA: String,
        userB: String,
        newState: FriendConnectionState,
    ) {
        // The CHECK `friend_connections_accepted_at_matches_state`
        // enforces accepted_at IS NOT NULL iff state='accepted'. When
        // transitioning TO accepted we stamp a client-side timestamp;
        // when transitioning AWAY we null out the column. Both
        // columns ride in the same UPDATE so the CHECK is satisfied
        // atomically. Client-side clock skew is cosmetic only — the
        // DB doesn't trust this timestamp for any logic.
        val acceptedAt: String? =
            if (newState == FriendConnectionState.Accepted) {
                kotlin.time.Clock.System
                    .now()
                    .toString()
            } else {
                null
            }
        supabaseClient.postgrest["friend_connections"]
            .update(
                StateAndAcceptedAtPayload(
                    state = newState.wireValue,
                    acceptedAt = acceptedAt,
                ),
            ) {
                filter {
                    eq("user_a", userA)
                    eq("user_b", userB)
                }
            }
    }

    override suspend fun deleteEdge(
        userA: String,
        userB: String,
    ) {
        supabaseClient.postgrest["friend_connections"]
            .delete {
                filter {
                    eq("user_a", userA)
                    eq("user_b", userB)
                }
            }
    }

    override suspend fun isFriendOf(
        userA: String,
        userB: String,
    ): Boolean {
        val result =
            supabaseClient.postgrest.rpc(
                "is_friend",
                buildJsonObject {
                    put("p_user_a", JsonPrimitive(userA))
                    put("p_user_b", JsonPrimitive(userB))
                },
            )
        return result.decodeAs<Boolean>()
    }

    override suspend fun createInvite(): FriendInvite {
        val result = supabaseClient.postgrest.rpc("create_friend_invite")
        // RPC returns TABLE(id, token, code, expires_at) — decode as the
        // minimal row then fetch the full row via SELECT (consumed_at +
        // consumed_by + created_at default to null / now()).
        val rows = result.decodeList<FriendInviteRowMinimal>()
        val row = rows.firstOrNull() ?: error("create_friend_invite returned no rows")
        val full =
            supabaseClient.postgrest["friend_invites"]
                .select(Columns.list(INVITE_SELECT_COLS)) {
                    filter { eq("id", row.id) }
                }.decodeList<FriendInviteRow>()
                .firstOrNull()
        return full?.toDomain()
            ?: FriendInvite(
                // Fallback when RLS denied the SELECT (shouldn't happen
                // since the caller is the inviter, but defensive).
                id = row.id,
                token = row.token,
                code = row.code,
                expiresAt = Instant.parse(row.expiresAt),
                consumedAt = null,
                consumedBy = null,
                createdAt = Instant.parse(row.expiresAt), // best-effort
            )
    }

    override suspend fun listOwnInvites(): List<FriendInvite> {
        // RLS scopes to inviter_id = auth.uid(); we further filter
        // client-side for outstanding (non-consumed) invites.
        val rows =
            supabaseClient.postgrest["friend_invites"]
                .select(Columns.list(INVITE_SELECT_COLS))
                .decodeList<FriendInviteRow>()
        return rows.map { it.toDomain() }
    }

    override suspend fun redeemCode(code: String): String {
        val result =
            supabaseClient.postgrest.rpc(
                "redeem_friend_invite_code",
                buildJsonObject { put("p_code", JsonPrimitive(code)) },
            )
        return result.decodeAs<String>()
    }

    override suspend fun redeemToken(token: String): String {
        val result =
            supabaseClient.postgrest.rpc(
                "redeem_friend_invite_token",
                buildJsonObject { put("p_token", JsonPrimitive(token)) },
            )
        return result.decodeAs<String>()
    }

    @Serializable
    private data class FriendConnectionRow(
        @SerialName("user_a") val userA: String,
        @SerialName("user_b") val userB: String,
        val state: String,
        @SerialName("requester_id") val requesterId: String,
        @SerialName("created_at") val createdAt: String,
        @SerialName("accepted_at") val acceptedAt: String? = null,
    ) {
        fun toDomain(): FriendConnection =
            FriendConnection(
                userA = userA,
                userB = userB,
                state = FriendConnectionState.fromWire(state),
                requesterId = requesterId,
                createdAt = Instant.parse(createdAt),
                acceptedAt = acceptedAt?.let { Instant.parse(it) },
            )
    }

    @Serializable
    private data class FriendConnectionInsertPayload(
        @SerialName("user_a") val userA: String,
        @SerialName("user_b") val userB: String,
        val state: String,
        @SerialName("requester_id") val requesterId: String,
    )

    @Serializable
    private data class StateAndAcceptedAtPayload(
        val state: String,
        @SerialName("accepted_at") val acceptedAt: String? = null,
    )

    @Serializable
    private data class FriendInviteRowMinimal(
        val id: String,
        val token: String,
        val code: String,
        @SerialName("expires_at") val expiresAt: String,
    )

    @Serializable
    private data class FriendInviteRow(
        val id: String,
        val token: String,
        val code: String,
        @SerialName("expires_at") val expiresAt: String,
        @SerialName("consumed_at") val consumedAt: String? = null,
        @SerialName("consumed_by") val consumedBy: String? = null,
        @SerialName("created_at") val createdAt: String,
    ) {
        fun toDomain(): FriendInvite =
            FriendInvite(
                id = id,
                token = token,
                code = code,
                expiresAt = Instant.parse(expiresAt),
                consumedAt = consumedAt?.let { Instant.parse(it) },
                consumedBy = consumedBy,
                createdAt = Instant.parse(createdAt),
            )
    }

    internal companion object {
        internal const val SELECT_COLS =
            "user_a,user_b,state,requester_id,created_at,accepted_at"
        internal const val INVITE_SELECT_COLS =
            "id,token,code,expires_at,consumed_at,consumed_by,created_at"
    }
}
