package io.github.b150005.knitnote.domain.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UseCaseResultTest {
    @Test
    fun `Success wraps value correctly`() {
        val result: UseCaseResult<String> = UseCaseResult.Success("hello")

        assertIs<UseCaseResult.Success<String>>(result)
        assertEquals("hello", result.value)
    }

    @Test
    fun `Failure wraps NotFound error`() {
        val result: UseCaseResult<String> = UseCaseResult.Failure(UseCaseError.NotFound("not found"))

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
        assertEquals("not found", (result.error as UseCaseError.NotFound).message)
    }

    @Test
    fun `Failure wraps Validation error`() {
        val result: UseCaseResult<String> = UseCaseResult.Failure(UseCaseError.Validation("invalid"))

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
        assertEquals("invalid", (result.error as UseCaseError.Validation).message)
    }

    @Test
    fun `Failure wraps Unknown error with cause`() {
        val exception = RuntimeException("boom")
        val result: UseCaseResult<String> = UseCaseResult.Failure(UseCaseError.Unknown(exception))

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Unknown>(result.error)
        assertEquals(exception, (result.error as UseCaseError.Unknown).cause)
    }

    // --- toUseCaseError() mapper tests ---

    @Test
    fun `toUseCaseError maps known auth error to Authentication`() {
        val exception = RuntimeException("invalid_credentials")
        assertIs<UseCaseError.Authentication>(exception.toUseCaseError())
    }

    @Test
    fun `toUseCaseError maps user_already_exists to Authentication`() {
        val exception = RuntimeException("user_already_exists")
        assertIs<UseCaseError.Authentication>(exception.toUseCaseError())
    }

    @Test
    fun `toUseCaseError maps email_not_confirmed to Authentication`() {
        val exception = RuntimeException("email_not_confirmed")
        assertIs<UseCaseError.Authentication>(exception.toUseCaseError())
    }

    @Test
    fun `toUseCaseError maps generic RuntimeException to Unknown`() {
        val exception = RuntimeException("something went wrong")
        assertIs<UseCaseError.Unknown>(exception.toUseCaseError())
    }

    // --- toMessage() tests for new error types ---

    @Test
    fun `Authentication error toMessage returns friendly text`() {
        val error = UseCaseError.Authentication(RuntimeException("invalid_credentials"))
        assertEquals("Invalid email or password", error.toMessage())
    }

    @Test
    fun `Network error toMessage returns connectivity message`() {
        val error = UseCaseError.Network(RuntimeException("timeout"))
        assertEquals("Network error. Please check your connection and try again.", error.toMessage())
    }
}
