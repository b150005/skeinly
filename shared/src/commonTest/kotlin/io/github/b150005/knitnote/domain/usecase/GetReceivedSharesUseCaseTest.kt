package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.Share
import io.github.b150005.knitnote.domain.model.SharePermission
import io.github.b150005.knitnote.domain.model.ShareStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class GetReceivedSharesUseCaseTest {
    private fun makeShare(
        id: String,
        toUserId: String?,
    ) = Share(
        id = id,
        patternId = "pat-1",
        fromUserId = "other-user",
        toUserId = toUserId,
        permission = SharePermission.VIEW,
        status = ShareStatus.ACCEPTED,
        shareToken = "token-$id",
        sharedAt = Instant.fromEpochMilliseconds(1000),
    )

    @Test
    fun `returns failure when share repository is null`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val useCase = GetReceivedSharesUseCase(null, authRepo)

            val result = useCase()
            assertIs<UseCaseResult.Failure>(result)
        }

    @Test
    fun `returns failure when not authenticated`() =
        runTest {
            val useCase = GetReceivedSharesUseCase(FakeShareRepository(), FakeAuthRepository())
            val result = useCase()
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns received shares for current user`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val shareRepo = FakeShareRepository()
            shareRepo.addShare(makeShare("s-1", "user-1"))
            shareRepo.addShare(makeShare("s-2", "user-1"))
            shareRepo.addShare(makeShare("s-3", "other-user"))
            val useCase = GetReceivedSharesUseCase(shareRepo, authRepo)

            val result = useCase()

            assertIs<UseCaseResult.Success<List<Share>>>(result)
            assertEquals(2, result.value.size)
        }

    @Test
    fun `returns empty list when no shares received`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val useCase = GetReceivedSharesUseCase(FakeShareRepository(), authRepo)

            val result = useCase()

            assertIs<UseCaseResult.Success<List<Share>>>(result)
            assertEquals(0, result.value.size)
        }
}
