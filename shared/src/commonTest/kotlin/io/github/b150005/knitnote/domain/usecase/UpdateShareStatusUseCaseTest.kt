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

class UpdateShareStatusUseCaseTest {
    private val pendingShare =
        Share(
            id = "share-1",
            patternId = "pat-1",
            fromUserId = "sender-id",
            toUserId = "recipient-id",
            permission = SharePermission.VIEW,
            status = ShareStatus.PENDING,
            shareToken = null,
            sharedAt = Instant.fromEpochMilliseconds(1000),
        )

    private fun createUseCase(
        shareRepo: FakeShareRepository? = FakeShareRepository(),
        authRepo: FakeAuthRepository = FakeAuthRepository(),
    ) = UpdateShareStatusUseCase(shareRepo, authRepo)

    @Test
    fun `returns failure when share repository is null`() =
        runTest {
            val useCase = createUseCase(shareRepo = null)
            val result = useCase("share-1", ShareStatus.ACCEPTED)
            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.Validation::class, result.error::class)
        }

    @Test
    fun `returns failure when not signed in`() =
        runTest {
            val useCase = createUseCase()
            val result = useCase("share-1", ShareStatus.ACCEPTED)
            assertIs<UseCaseResult.Failure>(result)
        }

    @Test
    fun `returns failure when share not found`() =
        runTest {
            val authRepo =
                FakeAuthRepository().apply {
                    setAuthState(AuthState.Authenticated("recipient-id", "user@test.com"))
                }
            val useCase = createUseCase(authRepo = authRepo)
            val result = useCase("nonexistent", ShareStatus.ACCEPTED)
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.NotFound>(result.error)
        }

    @Test
    fun `returns failure when user is not the recipient`() =
        runTest {
            val authRepo =
                FakeAuthRepository().apply {
                    setAuthState(AuthState.Authenticated("other-user", "other@test.com"))
                }
            val shareRepo = FakeShareRepository().apply { addShare(pendingShare) }
            val useCase = createUseCase(shareRepo = shareRepo, authRepo = authRepo)

            val result = useCase("share-1", ShareStatus.ACCEPTED)
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns failure when share is not pending`() =
        runTest {
            val authRepo =
                FakeAuthRepository().apply {
                    setAuthState(AuthState.Authenticated("recipient-id", "user@test.com"))
                }
            val acceptedShare = pendingShare.copy(status = ShareStatus.ACCEPTED)
            val shareRepo = FakeShareRepository().apply { addShare(acceptedShare) }
            val useCase = createUseCase(shareRepo = shareRepo, authRepo = authRepo)

            val result = useCase("share-1", ShareStatus.DECLINED)
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns failure for invalid target status PENDING`() =
        runTest {
            val authRepo =
                FakeAuthRepository().apply {
                    setAuthState(AuthState.Authenticated("recipient-id", "user@test.com"))
                }
            val shareRepo = FakeShareRepository().apply { addShare(pendingShare) }
            val useCase = createUseCase(shareRepo = shareRepo, authRepo = authRepo)

            val result = useCase("share-1", ShareStatus.PENDING)
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `accepts pending share successfully`() =
        runTest {
            val authRepo =
                FakeAuthRepository().apply {
                    setAuthState(AuthState.Authenticated("recipient-id", "user@test.com"))
                }
            val shareRepo = FakeShareRepository().apply { addShare(pendingShare) }
            val useCase = createUseCase(shareRepo = shareRepo, authRepo = authRepo)

            val result = useCase("share-1", ShareStatus.ACCEPTED)
            assertIs<UseCaseResult.Success<Share>>(result)
            assertEquals(ShareStatus.ACCEPTED, result.value.status)
        }

    @Test
    fun `declines pending share successfully`() =
        runTest {
            val authRepo =
                FakeAuthRepository().apply {
                    setAuthState(AuthState.Authenticated("recipient-id", "user@test.com"))
                }
            val shareRepo = FakeShareRepository().apply { addShare(pendingShare) }
            val useCase = createUseCase(shareRepo = shareRepo, authRepo = authRepo)

            val result = useCase("share-1", ShareStatus.DECLINED)
            assertIs<UseCaseResult.Success<Share>>(result)
            assertEquals(ShareStatus.DECLINED, result.value.status)
        }
}
