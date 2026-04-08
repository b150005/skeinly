package com.knitnote.domain.usecase

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
}
