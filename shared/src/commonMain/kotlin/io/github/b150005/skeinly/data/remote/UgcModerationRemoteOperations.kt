package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.domain.model.BlockedUser
import io.github.b150005.skeinly.domain.model.UgcReportCategory
import io.github.b150005.skeinly.domain.model.UgcTargetType
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 39 (ADR-021 §D4) — write/read port for UGC moderation:
 *
 * - `submit-ugc-report` Edge Function (report submission)
 * - `public.user_blocks` table CRUD (block / unblock / list)
 * - `public.profiles` read (resolve blocked-user display names)
 *
 * Exists as an interface so [io.github.b150005.skeinly.data.repository.UgcModerationRepositoryImpl]
 * tests can inject an in-memory fake without standing up Supabase.
 * Same precedent as [FriendRemoteOperations] (Phase 25.1) and
 * [WipeDataRemoteOperations] (Phase 27.1).
 */
interface UgcModerationRemoteOperations {
    /**
     * Calls the `submit-ugc-report` Edge Function. The Functions
     * plugin auto-attaches the caller's session JWT (`verify_jwt =
     * true`). Throws [UgcReportSubmissionException] on any
     * application-level rejection (envelope `ok = false`) or
     * Supabase-platform failure (non-200) so the repository can map
     * the carried [UgcReportSubmissionException.code] to a typed
     * `UseCaseError`. Throws other exceptions on transport failure.
     */
    suspend fun submitReport(
        targetType: UgcTargetType,
        targetId: String,
        category: UgcReportCategory,
        reason: String,
    )

    /** Idempotent INSERT (upsert on the composite PK) of a block. */
    suspend fun blockUser(
        blockerId: String,
        blockedId: String,
    )

    /** Idempotent DELETE of the caller's block (no-op if absent). */
    suspend fun unblockUser(
        blockerId: String,
        blockedId: String,
    )

    /**
     * Lists the caller's blocks (RLS scopes to `blocker_id =
     * blockerId`) joined to `profiles` for display names. A blocked
     * user whose profile row is missing (deleted account) surfaces
     * with a blank [BlockedUser.displayName]; the screen substitutes
     * a localized "Unknown user" fallback.
     */
    suspend fun listBlockedUsers(blockerId: String): List<BlockedUser>
}

/**
 * Carries the `submit-ugc-report` Edge Function's closed error code
 * ([code]) so [io.github.b150005.skeinly.data.repository.UgcModerationRepositoryImpl]
 * maps `RATE_LIMITED` / `UNAUTHORIZED` / `VALIDATION_FAILED` to
 * specific `UseCaseError`s rather than a generic catch-all. [code] is
 * null for a non-200 Supabase-platform failure (function undeployed /
 * mid-rotation / 5xx).
 */
class UgcReportSubmissionException(
    val code: String?,
    message: String,
) : Exception(message)

/**
 * Phase 39 (ADR-021 §D4) — Supabase implementation of
 * [UgcModerationRemoteOperations].
 *
 * Report submission uses supabase-kt's `functions.invoke()` (NOT a
 * hand-rolled Ktor POST) so the session JWT is auto-attached to match
 * the Edge Function's `verify_jwt = true` — same rationale as
 * [RemoteSymbolPackDataSource]. The plugin returns Ktor's
 * `HttpResponse` raw and does NOT throw on non-2xx; we inspect the
 * status + envelope explicitly.
 *
 * Block / unblock use Postgrest. `blockUser` upserts on the composite
 * PK `(blocker_id, blocked_id)` so a duplicate block is idempotent
 * without a pre-SELECT. `unblockUser` DELETEs by both ids; a
 * non-existent row is a Postgrest no-op (idempotent). RLS
 * (`blocker_id = auth.uid()` on both INSERT WITH CHECK and DELETE
 * USING, migration 031) is the server-side authority — we still pass
 * [blockerId] explicitly so the WITH CHECK predicate is satisfiable.
 */
class RemoteUgcModerationDataSource(
    private val supabaseClient: SupabaseClient,
    private val json: Json,
) : UgcModerationRemoteOperations {
    override suspend fun submitReport(
        targetType: UgcTargetType,
        targetId: String,
        category: UgcReportCategory,
        reason: String,
    ) {
        val response: HttpResponse =
            supabaseClient.functions.invoke(EDGE_FUNCTION) {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        SubmitReportRequest.serializer(),
                        SubmitReportRequest(
                            targetType = targetType.wireValue,
                            targetId = targetId,
                            reason = reason,
                            reasonCategory = category.wireValue,
                        ),
                    ),
                )
            }

        val bodyText =
            try {
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Non-200 with an unreadable body still means the
                // submission did not land — surface as a code-less
                // platform failure.
                throw UgcReportSubmissionException(
                    code = null,
                    message = "submit-ugc-report response body unreadable: ${e.message ?: e::class.simpleName.orEmpty()}",
                )
            }

        // Non-200 ⇒ Supabase-platform breakage (function undeployed,
        // mid-rotation, 5xx, or a verify_jwt 401 from an expired token
        // racing the repository's pre-flight check). Code-less so the
        // repository maps it to a generic Unknown (or SignInRequired
        // for a 401).
        if (response.status.value != HTTP_OK) {
            throw UgcReportSubmissionException(
                code = if (response.status.value == HTTP_UNAUTHORIZED) CODE_UNAUTHORIZED else null,
                message = "submit-ugc-report returned HTTP ${response.status.value}: ${bodyText.take(200)}",
            )
        }

        val envelope = json.decodeFromString(SubmitReportResponse.serializer(), bodyText)
        if (!envelope.ok) {
            throw UgcReportSubmissionException(
                code = envelope.code,
                message = envelope.message ?: "submit-ugc-report rejected the report",
            )
        }
    }

    override suspend fun blockUser(
        blockerId: String,
        blockedId: String,
    ) {
        // Upsert (not insert) so a duplicate block is idempotent
        // without a pre-SELECT round-trip — the composite PK
        // (blocker_id, blocked_id) is the conflict target.
        supabaseClient.postgrest["user_blocks"]
            .upsert(
                UserBlockInsert(blockerId = blockerId, blockedId = blockedId),
            )
    }

    override suspend fun unblockUser(
        blockerId: String,
        blockedId: String,
    ) {
        supabaseClient.postgrest["user_blocks"]
            .delete {
                filter {
                    eq("blocker_id", blockerId)
                    eq("blocked_id", blockedId)
                }
            }
    }

    override suspend fun listBlockedUsers(blockerId: String): List<BlockedUser> {
        // Deliberate two-step (NOT a Postgrest embedded join
        // `user_blocks?select=blocked_id,profiles!blocked_id(...)`):
        // the early-return below makes the common no-blocks case a
        // single round-trip, and the explicit second SELECT keeps this
        // portable across supabase-kt minor releases that shuffle the
        // embedded-resource DSL (same rationale as
        // FriendRemoteOperations' client-side invite filtering). Do
        // not "optimize" into a join without re-verifying the
        // `profiles!blocked_id` FK relationship name is stable.
        val blocks =
            supabaseClient.postgrest["user_blocks"]
                .select(Columns.list("blocked_id")) {
                    filter { eq("blocker_id", blockerId) }
                }.decodeList<BlockedIdRow>()
        if (blocks.isEmpty()) return emptyList()

        val blockedIds = blocks.map { it.blockedId }
        val profiles =
            supabaseClient.postgrest["profiles"]
                .select(Columns.list("id,display_name")) {
                    filter { isIn("id", blockedIds) }
                }.decodeList<ProfileNameRow>()
        val nameById = profiles.associate { it.id to it.displayName }

        // Preserve user_blocks order; a deleted-account block surfaces
        // with a blank name (screen substitutes localized fallback).
        return blockedIds.map { id ->
            BlockedUser(userId = id, displayName = nameById[id] ?: "")
        }
    }

    @Serializable
    private data class SubmitReportRequest(
        @SerialName("target_type") val targetType: String,
        @SerialName("target_id") val targetId: String,
        val reason: String,
        @SerialName("reason_category") val reasonCategory: String,
    )

    @Serializable
    private data class SubmitReportResponse(
        val ok: Boolean,
        @SerialName("report_id") val reportId: String? = null,
        @SerialName("github_issue_url") val githubIssueUrl: String? = null,
        val code: String? = null,
        val message: String? = null,
    )

    /** INSERT/upsert payload — both ids always supplied (no default
     *  so a missing id is a compile-time error, not a silent ""). */
    @Serializable
    private data class UserBlockInsert(
        @SerialName("blocker_id") val blockerId: String,
        @SerialName("blocked_id") val blockedId: String,
    )

    /** SELECT decode for the blocked-list query (only `blocked_id` is
     *  projected; a dedicated DTO avoids the upsert payload's shape
     *  leaking a default-"" blocker_id into the read path). */
    @Serializable
    private data class BlockedIdRow(
        @SerialName("blocked_id") val blockedId: String,
    )

    /** `profiles.display_name` is nullable in the schema (a profile
     *  row can exist before display-name completion, and a deleted
     *  account leaves no profile row at all). Nullable here so a NULL
     *  does not throw during decode — the `?: ""` fallback at the call
     *  site maps it to the screen's localized "Unknown user". */
    @Serializable
    private data class ProfileNameRow(
        val id: String,
        @SerialName("display_name") val displayName: String? = null,
    )

    internal companion object {
        internal const val EDGE_FUNCTION = "submit-ugc-report"
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        internal const val CODE_UNAUTHORIZED = "UNAUTHORIZED"
    }
}
