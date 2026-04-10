package com.knitnote.domain.usecase

import com.knitnote.data.repository.OfflineUserRepository
import com.knitnote.domain.model.AuthState
import com.knitnote.domain.model.User
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GetCurrentUserUseCaseTest {

    private fun testUser(id: String = "user-1") = User(
        id = id,
        displayName = "Test User",
        avatarUrl = null,
        bio = "A test user",
        createdAt = Clock.System.now(),
    )

    @Test
    fun `returns user when authenticated and profile exists`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))

        val userRepo = FakeUserRepository()
        userRepo.addUser(testUser("user-1"))

        val useCase = GetCurrentUserUseCase(authRepo, userRepo)
        val result = useCase()

        assertIs<UseCaseResult.Success<User>>(result)
        assertEquals("Test User", result.value.displayName)
    }

    @Test
    fun `fails when not authenticated`() = runTest {
        val authRepo = FakeAuthRepository()
        val userRepo = FakeUserRepository()

        val useCase = GetCurrentUserUseCase(authRepo, userRepo)
        val result = useCase()

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `fails when offline and profile not found`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))

        val useCase = GetCurrentUserUseCase(authRepo, OfflineUserRepository())
        val result = useCase()

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }

    @Test
    fun `fails when profile not found`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))

        val userRepo = FakeUserRepository() // no user added

        val useCase = GetCurrentUserUseCase(authRepo, userRepo)
        val result = useCase()

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }
}
