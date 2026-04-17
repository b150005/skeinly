package io.github.b150005.knitnote.domain.usecase

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SignInUseCaseTest {
    private val fakeAuth = FakeAuthRepository()
    private val signIn = SignInUseCase(fakeAuth)

    @Test
    fun `sign in with valid credentials returns Success`() =
        runTest {
            val result = signIn("user@example.com", "password123")
            assertIs<UseCaseResult.Success<Unit>>(result)
        }

    @Test
    fun `sign in updates auth state to Authenticated`() =
        runTest {
            signIn("user@example.com", "password123")
            assertEquals("test-user-id", fakeAuth.getCurrentUserId())
        }

    @Test
    fun `sign in failure returns Failure with Unknown error`() =
        runTest {
            fakeAuth.signInError = RuntimeException("Invalid credentials")
            val result = signIn("user@example.com", "wrong")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(result.error)
        }
}
