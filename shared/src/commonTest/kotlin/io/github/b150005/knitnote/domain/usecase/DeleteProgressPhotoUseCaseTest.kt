package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.data.remote.FakeRemoteStorageDataSource
import io.github.b150005.knitnote.domain.model.AuthState
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

class DeleteProgressPhotoUseCaseTest {
    private lateinit var storage: FakeRemoteStorageDataSource
    private lateinit var authRepository: FakeAuthRepository
    private lateinit var useCase: DeleteProgressPhotoUseCase

    @BeforeTest
    fun setUp() {
        storage = FakeRemoteStorageDataSource()
        authRepository = FakeAuthRepository()
        authRepository.setAuthState(AuthState.Authenticated(userId = "user-1", email = "test@test.com"))
        useCase = DeleteProgressPhotoUseCase(storage, authRepository)
    }

    @Test
    fun `delete own photo succeeds`() =
        kotlinx.coroutines.test.runTest {
            val result = useCase("user-1/project-1/photo.jpg")
            assertIs<UseCaseResult.Success<Unit>>(result)
        }

    @Test
    fun `delete other user photo returns validation error`() =
        kotlinx.coroutines.test.runTest {
            val result = useCase("other-user/project-1/photo.jpg")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `null storage returns network error`() =
        kotlinx.coroutines.test.runTest {
            val noStorageUseCase = DeleteProgressPhotoUseCase(null, authRepository)
            val result = noStorageUseCase("user-1/project-1/photo.jpg")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Network>(result.error)
        }

    @Test
    fun `no auth returns validation error`() =
        kotlinx.coroutines.test.runTest {
            authRepository.setAuthState(AuthState.Unauthenticated)
            val result = useCase("user-1/project-1/photo.jpg")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `storage exception returns failure`() =
        kotlinx.coroutines.test.runTest {
            storage.deleteError = RuntimeException("Delete failed")
            val result = useCase("user-1/project-1/photo.jpg")
            assertIs<UseCaseResult.Failure>(result)
        }
}
