package io.github.b150005.knitnote.domain.usecase

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

class SignUpUseCaseTest {
    private val fakeAuth = FakeAuthRepository()
    private val signUp = SignUpUseCase(fakeAuth)

    @Test
    fun `sign up with valid credentials returns Success`() =
        runTest {
            val result = signUp("user@example.com", "password123")
            assertIs<UseCaseResult.Success<Unit>>(result)
        }

    @Test
    fun `sign up with blank email returns Validation error`() =
        runTest {
            val result = signUp("", "password123")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `sign up with short password returns Validation error`() =
        runTest {
            val result = signUp("user@example.com", "12345")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `sign up failure returns Failure with Unknown error`() =
        runTest {
            fakeAuth.signUpError = RuntimeException("Email already exists")
            val result = signUp("user@example.com", "password123")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(result.error)
        }
}
