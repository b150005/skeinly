package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.remote.UgcModerationRemoteOperations
import io.github.b150005.skeinly.data.remote.UgcReportSubmissionException
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.BlockedUser
import io.github.b150005.skeinly.domain.model.MAX_UGC_REASON_LENGTH
import io.github.b150005.skeinly.domain.model.UgcReportCategory
import io.github.b150005.skeinly.domain.model.UgcTargetType
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Phase 39 (ADR-021 §D4) — covers the [UgcModerationRepositoryImpl]
// contract: offline / unauth short-circuits, client-side reason
// validation, Edge Function envelope-code mapping, self-block guard,
// happy paths, error mapping, cancellation propagation. Pattern
// mirrors FriendRepositoryImplTest (Phase 25.1) + WipeDataRepositoryImplTest
// (Phase 27.1).

/**
 * Trip-wire whose `simpleName` matches the `networkExceptionPatterns`
 * heuristic in `toUseCaseError` — surfaces as [UseCaseError.Network]
 * without depending on a platform IOException type.
 */
private class UgcFakeIOException(
    message: String,
) : Exception(message)

private class FakeUgcRemote : UgcModerationRemoteOperations {
    var nextSubmitError: Throwable? = null
    var nextBlockError: Throwable? = null
    var nextUnblockError: Throwable? = null
    var nextListError: Throwable? = null
    var blockedUsers: List<BlockedUser> = emptyList()

    var callsSubmit = 0
    var callsBlock = 0
    var callsUnblock = 0
    var callsList = 0

    var lastSubmit: SubmitArgs? = null
    var lastBlock: Pair<String, String>? = null
    var lastUnblock: Pair<String, String>? = null
    var lastListBlocker: String? = null

    data class SubmitArgs(
        val targetType: UgcTargetType,
        val targetId: String,
        val category: UgcReportCategory,
        val reason: String,
    )

    override suspend fun submitReport(
        targetType: UgcTargetType,
        targetId: String,
        category: UgcReportCategory,
        reason: String,
    ) {
        callsSubmit++
        lastSubmit = SubmitArgs(targetType, targetId, category, reason)
        nextSubmitError?.let {
            nextSubmitError = null
            throw it
        }
    }

    override suspend fun blockUser(
        blockerId: String,
        blockedId: String,
    ) {
        callsBlock++
        lastBlock = blockerId to blockedId
        nextBlockError?.let {
            nextBlockError = null
            throw it
        }
    }

    override suspend fun unblockUser(
        blockerId: String,
        blockedId: String,
    ) {
        callsUnblock++
        lastUnblock = blockerId to blockedId
        nextUnblockError?.let {
            nextUnblockError = null
            throw it
        }
    }

    override suspend fun listBlockedUsers(blockerId: String): List<BlockedUser> {
        callsList++
        lastListBlocker = blockerId
        nextListError?.let {
            nextListError = null
            throw it
        }
        return blockedUsers
    }
}

class UgcModerationRepositoryImplTest {
    private val callerId = "aaaa1111-1111-1111-1111-111111111111"
    private val targetId = "cccc3333-3333-3333-3333-333333333333"
    private val otherId = "bbbb2222-2222-2222-2222-222222222222"

    private fun makeAuth(userId: String? = callerId): FakeAuthRepository =
        FakeAuthRepository().also { auth ->
            if (userId != null) {
                auth.setAuthState(AuthState.Authenticated(userId = userId, email = "test@example.com"))
            }
        }

    // ===== Short-circuit guards =====

    @Test
    fun submitReport_RequiresConnectivity_when_remote_null() =
        runTest {
            val repo = UgcModerationRepositoryImpl(remote = null, authRepository = makeAuth())
            val result = repo.submitReport(UgcTargetType.Pattern, targetId, UgcReportCategory.Spam, "bad")
            assertEquals(UseCaseError.RequiresConnectivity, assertIs<UseCaseResult.Failure>(result).error)
        }

    @Test
    fun submitReport_SignInRequired_when_not_signed_in() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth(userId = null))
            val result = repo.submitReport(UgcTargetType.Pattern, targetId, UgcReportCategory.Spam, "bad")
            assertEquals(UseCaseError.SignInRequired, assertIs<UseCaseResult.Failure>(result).error)
            assertEquals(0, remote.callsSubmit, "must not contact remote without auth")
        }

    @Test
    fun blockUser_RequiresConnectivity_when_remote_null() =
        runTest {
            val repo = UgcModerationRepositoryImpl(remote = null, authRepository = makeAuth())
            assertEquals(
                UseCaseError.RequiresConnectivity,
                assertIs<UseCaseResult.Failure>(repo.blockUser(otherId)).error,
            )
        }

    @Test
    fun listBlockedUsers_SignInRequired_when_not_signed_in() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth(userId = null))
            assertEquals(
                UseCaseError.SignInRequired,
                assertIs<UseCaseResult.Failure>(repo.listBlockedUsers()).error,
            )
            assertEquals(0, remote.callsList)
        }

    // ===== Client-side reason validation =====

    @Test
    fun submitReport_FieldRequired_when_reason_blank() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.submitReport(UgcTargetType.Comment, targetId, UgcReportCategory.Spam, "   ")
            assertEquals(UseCaseError.FieldRequired, assertIs<UseCaseResult.Failure>(result).error)
            assertEquals(0, remote.callsSubmit, "blank reason must not round-trip")
        }

    @Test
    fun submitReport_FieldTooLong_when_reason_exceeds_cap() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val tooLong = "x".repeat(MAX_UGC_REASON_LENGTH + 1)
            val result = repo.submitReport(UgcTargetType.Suggestion, targetId, UgcReportCategory.Hate, tooLong)
            assertEquals(UseCaseError.FieldTooLong, assertIs<UseCaseResult.Failure>(result).error)
            assertEquals(0, remote.callsSubmit, "over-long reason must not round-trip")
        }

    @Test
    fun submitReport_trims_reason_before_sending_to_remote() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result =
                repo.submitReport(UgcTargetType.Pattern, targetId, UgcReportCategory.Spam, "  padded reason  ")
            assertIs<UseCaseResult.Success<Unit>>(result)
            // HIGH-1: the trimmed value reaches the wire + the DB, not
            // the whitespace-padded original — client / Edge Function /
            // stored row all agree.
            assertEquals("padded reason", remote.lastSubmit?.reason)
        }

    @Test
    fun submitReport_whitespace_only_reason_is_FieldRequired_not_sent() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result =
                repo.submitReport(UgcTargetType.Pattern, targetId, UgcReportCategory.Spam, "\n\t  \n")
            assertEquals(UseCaseError.FieldRequired, assertIs<UseCaseResult.Failure>(result).error)
            assertEquals(0, remote.callsSubmit)
        }

    @Test
    fun unblockUser_SignInRequired_when_not_signed_in() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth(userId = null))
            assertEquals(
                UseCaseError.SignInRequired,
                assertIs<UseCaseResult.Failure>(repo.unblockUser(otherId)).error,
            )
            assertEquals(0, remote.callsUnblock, "must not contact remote without auth")
        }

    @Test
    fun submitReport_accepts_reason_at_exactly_the_cap() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val atCap = "y".repeat(MAX_UGC_REASON_LENGTH)
            val result = repo.submitReport(UgcTargetType.Pattern, targetId, UgcReportCategory.Other, atCap)
            assertIs<UseCaseResult.Success<Unit>>(result)
            assertEquals(1, remote.callsSubmit)
        }

    // ===== submitReport happy + error mapping =====

    @Test
    fun submitReport_happy_path_passes_args_through() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result =
                repo.submitReport(UgcTargetType.SuggestionComment, targetId, UgcReportCategory.Harassment, "abusive language")
            assertIs<UseCaseResult.Success<Unit>>(result)
            assertEquals(
                FakeUgcRemote.SubmitArgs(
                    UgcTargetType.SuggestionComment,
                    targetId,
                    UgcReportCategory.Harassment,
                    "abusive language",
                ),
                remote.lastSubmit,
            )
        }

    @Test
    fun submitReport_RATE_LIMITED_maps_to_RateLimited() =
        runTest {
            val remote = FakeUgcRemote().apply { nextSubmitError = UgcReportSubmissionException("RATE_LIMITED", "slow down") }
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.submitReport(UgcTargetType.Pattern, targetId, UgcReportCategory.Spam, "spam")
            assertEquals(UseCaseError.RateLimited, assertIs<UseCaseResult.Failure>(result).error)
        }

    @Test
    fun submitReport_UNAUTHORIZED_maps_to_SignInRequired() =
        runTest {
            val remote = FakeUgcRemote().apply { nextSubmitError = UgcReportSubmissionException("UNAUTHORIZED", "expired") }
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.submitReport(UgcTargetType.Pattern, targetId, UgcReportCategory.Spam, "spam")
            assertEquals(UseCaseError.SignInRequired, assertIs<UseCaseResult.Failure>(result).error)
        }

    @Test
    fun submitReport_VALIDATION_FAILED_maps_to_Unknown() =
        runTest {
            val remote =
                FakeUgcRemote().apply { nextSubmitError = UgcReportSubmissionException("VALIDATION_FAILED", "drift") }
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.submitReport(UgcTargetType.Pattern, targetId, UgcReportCategory.Spam, "spam")
            assertIs<UseCaseError.Unknown>(assertIs<UseCaseResult.Failure>(result).error)
        }

    @Test
    fun submitReport_platform_failure_null_code_maps_to_Unknown() =
        runTest {
            val remote = FakeUgcRemote().apply { nextSubmitError = UgcReportSubmissionException(null, "HTTP 503") }
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.submitReport(UgcTargetType.Pattern, targetId, UgcReportCategory.Spam, "spam")
            assertIs<UseCaseError.Unknown>(assertIs<UseCaseResult.Failure>(result).error)
        }

    @Test
    fun submitReport_generic_network_exception_maps_to_Network() =
        runTest {
            val remote = FakeUgcRemote().apply { nextSubmitError = UgcFakeIOException("socket reset") }
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.submitReport(UgcTargetType.Pattern, targetId, UgcReportCategory.Spam, "spam")
            assertIs<UseCaseError.Network>(assertIs<UseCaseResult.Failure>(result).error)
        }

    @Test
    fun submitReport_rethrows_cancellation() =
        runTest {
            val remote = FakeUgcRemote().apply { nextSubmitError = CancellationException("scope cancelled") }
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            assertFailsWith<CancellationException> {
                repo.submitReport(UgcTargetType.Pattern, targetId, UgcReportCategory.Spam, "spam")
            }
        }

    // ===== blockUser =====

    @Test
    fun blockUser_self_block_rejected_before_round_trip() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = repo.blockUser(callerId) // blocking self
            assertEquals(UseCaseError.OperationNotAllowed, assertIs<UseCaseResult.Failure>(result).error)
            assertEquals(0, remote.callsBlock, "self-block must not round-trip")
        }

    @Test
    fun blockUser_happy_path_passes_blocker_and_blocked() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            assertIs<UseCaseResult.Success<Unit>>(repo.blockUser(otherId))
            assertEquals(callerId to otherId, remote.lastBlock)
        }

    @Test
    fun blockUser_remote_exception_maps_via_toUseCaseError() =
        runTest {
            val remote = FakeUgcRemote().apply { nextBlockError = UgcFakeIOException("offline") }
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            assertIs<UseCaseError.Network>(assertIs<UseCaseResult.Failure>(repo.blockUser(otherId)).error)
        }

    @Test
    fun blockUser_rethrows_cancellation() =
        runTest {
            val remote = FakeUgcRemote().apply { nextBlockError = CancellationException("cancel") }
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            assertFailsWith<CancellationException> { repo.blockUser(otherId) }
        }

    // ===== unblockUser =====

    @Test
    fun unblockUser_happy_path() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            assertIs<UseCaseResult.Success<Unit>>(repo.unblockUser(otherId))
            assertEquals(callerId to otherId, remote.lastUnblock)
        }

    @Test
    fun unblockUser_RequiresConnectivity_when_remote_null() =
        runTest {
            val repo = UgcModerationRepositoryImpl(remote = null, authRepository = makeAuth())
            assertEquals(
                UseCaseError.RequiresConnectivity,
                assertIs<UseCaseResult.Failure>(repo.unblockUser(otherId)).error,
            )
        }

    // ===== listBlockedUsers =====

    @Test
    fun listBlockedUsers_returns_list_scoped_to_caller() =
        runTest {
            val remote =
                FakeUgcRemote().apply {
                    blockedUsers = listOf(BlockedUser(otherId, "Mallory"))
                }
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = assertIs<UseCaseResult.Success<List<BlockedUser>>>(repo.listBlockedUsers())
            assertEquals(listOf(BlockedUser(otherId, "Mallory")), result.value)
            assertEquals(callerId, remote.lastListBlocker)
        }

    @Test
    fun listBlockedUsers_empty_is_success() =
        runTest {
            val remote = FakeUgcRemote()
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            val result = assertIs<UseCaseResult.Success<List<BlockedUser>>>(repo.listBlockedUsers())
            assertTrue(result.value.isEmpty())
        }

    @Test
    fun listBlockedUsers_remote_exception_maps_to_failure() =
        runTest {
            val remote = FakeUgcRemote().apply { nextListError = RuntimeException("boom") }
            val repo = UgcModerationRepositoryImpl(remote = remote, authRepository = makeAuth())
            assertIs<UseCaseError.Unknown>(assertIs<UseCaseResult.Failure>(repo.listBlockedUsers()).error)
        }
}
