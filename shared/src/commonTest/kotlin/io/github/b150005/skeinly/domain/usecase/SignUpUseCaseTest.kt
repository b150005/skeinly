package io.github.b150005.skeinly.domain.usecase

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `sign up with blank email returns FieldRequired`() =
        runTest {
            val result = signUp("", "password123")
            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.FieldRequired, result.error)
        }

    @Test
    fun `sign up with short password returns PasswordTooShort`() =
        runTest {
            val result = signUp("user@example.com", "12345")
            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.PasswordTooShort, result.error)
        }

    @Test
    fun `sign up failure returns Failure with Unknown error`() =
        runTest {
            fakeAuth.signUpError = RuntimeException("Email already exists")
            val result = signUp("user@example.com", "password123")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(result.error)
        }

    @Test
    fun `sign up timeout propagates to Failure not silent hang`() =
        runTest {
            // Pre-alpha 2026-05-13 regression guard. Symptom that triggered
            // this test: SUPABASE init had no `requestTimeout` configured,
            // so a network-unreachable host blocked the Ktor coroutine
            // indefinitely. The ViewModel never saw a Failure and the UI
            // alert never fired — user perceived "tap button, nothing
            // happens". With `SUPABASE_REQUEST_TIMEOUT = 20 s` set on
            // SupabaseClientBuilder.requestTimeout, the network layer now
            // throws an Exception subclass on timeout, which
            // SignUpUseCase's `catch (e: Exception)` arm converts to
            // UseCaseResult.Failure. This test locks in that contract at
            // the use-case layer without depending on supabase-kt internals
            // by simulating any network-IO exception (the canonical
            // production timeout shape is HttpRequestTimeoutException,
            // which is an IOException subtype).
            fakeAuth.signUpError = RuntimeException("HTTP request timed out")
            val result = signUp("user@example.com", "password123")
            assertIs<UseCaseResult.Failure>(result)
            // The error type doesn't need to be specific — what matters is
            // that the failure is observable. Mapping to a specific
            // user-facing message ("Connection timed out, please retry") is
            // an i18n + UseCaseError catalog concern, not this test's scope.
        }
}
