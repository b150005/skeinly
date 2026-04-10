package com.knitnote.domain.usecase

import com.knitnote.data.repository.OfflineUserRepository
import com.knitnote.domain.model.AuthState
import com.knitnote.domain.model.User
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateProfileUseCaseTest {

    private fun testUser(id: String = "user-1") = User(
        id = id,
        displayName = "Original Name",
        avatarUrl = null,
        bio = null,
        createdAt = Clock.System.now(),
    )

    private fun authenticatedSetup(): Pair<FakeAuthRepository, FakeUserRepository> {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
        val userRepo = FakeUserRepository()
        userRepo.addUser(testUser("user-1"))
        return authRepo to userRepo
    }

    @Test
    fun `updates display name and bio successfully`() = runTest {
        val (authRepo, userRepo) = authenticatedSetup()
        val useCase = UpdateProfileUseCase(authRepo, userRepo)

        val result = useCase(
            displayName = "New Name",
            bio = "My new bio",
            avatarUrl = null,
        )

        assertIs<UseCaseResult.Success<User>>(result)
        assertEquals("New Name", result.value.displayName)
        assertEquals("My new bio", result.value.bio)
    }

    @Test
    fun `fails when not authenticated`() = runTest {
        val authRepo = FakeAuthRepository()
        val userRepo = FakeUserRepository()
        val useCase = UpdateProfileUseCase(authRepo, userRepo)

        val result = useCase("Name", null, null)

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `fails when offline and profile not found`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
        val useCase = UpdateProfileUseCase(authRepo, OfflineUserRepository())

        val result = useCase("Name", null, null)

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }

    @Test
    fun `fails when display name is empty`() = runTest {
        val (authRepo, userRepo) = authenticatedSetup()
        val useCase = UpdateProfileUseCase(authRepo, userRepo)

        val result = useCase("   ", null, null)

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `fails when display name exceeds max length`() = runTest {
        val (authRepo, userRepo) = authenticatedSetup()
        val useCase = UpdateProfileUseCase(authRepo, userRepo)

        val result = useCase("A".repeat(51), null, null)

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `fails when bio exceeds max length`() = runTest {
        val (authRepo, userRepo) = authenticatedSetup()
        val useCase = UpdateProfileUseCase(authRepo, userRepo)

        val result = useCase("Name", "B".repeat(501), null)

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `trims whitespace from display name`() = runTest {
        val (authRepo, userRepo) = authenticatedSetup()
        val useCase = UpdateProfileUseCase(authRepo, userRepo)

        val result = useCase("  Trimmed Name  ", null, null)

        assertIs<UseCaseResult.Success<User>>(result)
        assertEquals("Trimmed Name", result.value.displayName)
    }

    @Test
    fun `sets bio to null when blank`() = runTest {
        val (authRepo, userRepo) = authenticatedSetup()
        val useCase = UpdateProfileUseCase(authRepo, userRepo)

        val result = useCase("Name", "   ", null)

        assertIs<UseCaseResult.Success<User>>(result)
        assertEquals(null, result.value.bio)
    }

    @Test
    fun `fails when profile not found`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-99", "test@example.com"))
        val userRepo = FakeUserRepository() // no user added
        val useCase = UpdateProfileUseCase(authRepo, userRepo)

        val result = useCase("Name", null, null)

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }
}
