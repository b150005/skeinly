package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.remote.UgcModerationRemoteOperations
import io.github.b150005.skeinly.data.remote.UgcReportSubmissionException
import io.github.b150005.skeinly.domain.model.BlockedUser
import io.github.b150005.skeinly.domain.model.MAX_UGC_REASON_LENGTH
import io.github.b150005.skeinly.domain.model.UgcReportCategory
import io.github.b150005.skeinly.domain.model.UgcTargetType
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.UgcModerationRepository
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toUseCaseError
import kotlinx.coroutines.CancellationException

/**
 * Phase 39 (ADR-021 §D4) — implementation of [UgcModerationRepository].
 *
 * Local-only mode (Supabase not configured ⇒ [remote] = null)
 * short-circuits with [UseCaseError.RequiresConnectivity]; signed-out
 * short-circuits with [UseCaseError.SignInRequired]. Same offline-first
 * shape as [FriendRepositoryImpl] (Phase 25.1) / [WipeDataRepositoryImpl]
 * (Phase 27.1) — both guards fire BEFORE the network round-trip.
 *
 * [submitReport] adds two client-side pre-flight validations (blank /
 * over-long reason) so the report modal never round-trips an input the
 * Edge Function would reject, and maps the Edge Function's closed
 * envelope codes to specific [UseCaseError]s. [blockUser] adds a
 * client-side self-block guard mirroring
 * [FriendRepositoryImpl.sendRequest]'s self-request guard.
 *
 * **No local mirror.** Reports + blocks are server-authoritative; the
 * migration-032 RLS amendments filter blocked content out of every
 * query server-side, so there is no client cache to invalidate.
 *
 * **Never throws** — failures route via [UseCaseResult.Failure].
 */
class UgcModerationRepositoryImpl(
    private val remote: UgcModerationRemoteOperations?,
    private val authRepository: AuthRepository,
) : UgcModerationRepository {
    override suspend fun submitReport(
        targetType: UgcTargetType,
        targetId: String,
        category: UgcReportCategory,
        reason: String,
    ): UseCaseResult<Unit> {
        val ops =
            remote
                ?: return UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
        authRepository.getCurrentUserId()
            ?: return UseCaseResult.Failure(UseCaseError.SignInRequired)

        // Client-side pre-flight. Trim ONCE and validate + send the
        // trimmed value so the client gate, the Edge Function's
        // `length(reason) BETWEEN 1 AND 2000`, and the stored
        // `ugc_reports.reason` all agree — a whitespace-padded or
        // whitespace-only reason can never reach the wire or the DB
        // (no leading/trailing-space drift between the FieldRequired
        // guard and the payload). The operator triages the trimmed
        // text exactly as the reporter intended it.
        val trimmed = reason.trim()
        if (trimmed.isEmpty()) {
            return UseCaseResult.Failure(UseCaseError.FieldRequired)
        }
        if (trimmed.length > MAX_UGC_REASON_LENGTH) {
            return UseCaseResult.Failure(UseCaseError.FieldTooLong)
        }

        return try {
            ops.submitReport(targetType, targetId, category, trimmed)
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UgcReportSubmissionException) {
            UseCaseResult.Failure(mapSubmissionCode(e))
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }

    override suspend fun blockUser(blockedUserId: String): UseCaseResult<Unit> =
        runRemote { ops, callerId ->
            if (blockedUserId == callerId) {
                // Client-side guard before the round-trip. The DB
                // `CHECK (blocker_id != blocked_id)` is the backstop;
                // surfacing it here keeps the test deterministic
                // without a real DB and avoids a wasted call.
                return@runRemote UseCaseResult.Failure(UseCaseError.OperationNotAllowed)
            }
            ops.blockUser(blockerId = callerId, blockedId = blockedUserId)
            UseCaseResult.Success(Unit)
        }

    override suspend fun unblockUser(blockedUserId: String): UseCaseResult<Unit> =
        runRemote { ops, callerId ->
            ops.unblockUser(blockerId = callerId, blockedId = blockedUserId)
            UseCaseResult.Success(Unit)
        }

    override suspend fun listBlockedUsers(): UseCaseResult<List<BlockedUser>> =
        runRemote { ops, callerId ->
            UseCaseResult.Success(ops.listBlockedUsers(blockerId = callerId))
        }

    /**
     * Maps the Edge Function's closed `code` envelope to a typed
     * [UseCaseError]:
     * - `RATE_LIMITED` → [UseCaseError.RateLimited] (10/hr per user)
     * - `UNAUTHORIZED` → [UseCaseError.SignInRequired] (token expired
     *   mid-flight; the pre-flight check already caught signed-out)
     * - `VALIDATION_FAILED` → [UseCaseError.Unknown] (client pre-flight
     *   already enforces the same rule; reaching here is contract
     *   drift, surfaced generically)
     * - `CONFIG_MISSING` / `DB_INSERT_FAILED` / null (platform) →
     *   [UseCaseError.Unknown] (operator-side / transient; the modal
     *   shows a generic retryable error)
     */
    private fun mapSubmissionCode(e: UgcReportSubmissionException): UseCaseError =
        when (e.code) {
            "RATE_LIMITED" -> UseCaseError.RateLimited
            "UNAUTHORIZED" -> UseCaseError.SignInRequired
            else -> UseCaseError.Unknown(e)
        }

    /**
     * Common pre-flight + error-mapping wrapper for the block /
     * unblock / list methods. The lambda receives the resolved
     * [UgcModerationRemoteOperations] + the caller's user id (both
     * non-null at the lambda site by construction) and returns its own
     * [UseCaseResult] so a guard (e.g. self-block) can short-circuit
     * with a specific [UseCaseError] without throwing.
     *
     * Rethrows [CancellationException]; maps all other exceptions via
     * [toUseCaseError]. Mirrors [FriendRepositoryImpl.runRemote].
     */
    private suspend inline fun <T> runRemote(
        crossinline block: suspend (ops: UgcModerationRemoteOperations, callerId: String) -> UseCaseResult<T>,
    ): UseCaseResult<T> {
        val ops =
            remote
                ?: return UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
        val callerId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(UseCaseError.SignInRequired)
        return try {
            block(ops, callerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
