package io.github.b150005.skeinly.ui.auth

import app.cash.turbine.test
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.domain.usecase.ObserveAuthStateUseCase
import io.github.b150005.skeinly.domain.usecase.SignInUseCase
import io.github.b150005.skeinly.domain.usecase.SignUpUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var authRepo: FakeAuthRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        authRepo = FakeAuthRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): AuthViewModel =
        AuthViewModel(
            observeAuthState = ObserveAuthStateUseCase(authRepo),
            signIn = SignInUseCase(authRepo),
            signUp = SignUpUseCase(authRepo),
        )

    @Test
    fun `initial state is unauthenticated`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(AuthState.Unauthenticated, state.authState)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `update email updates state`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.UpdateEmail("test@example.com"))

            viewModel.state.test {
                val state = awaitItem()
                assertEquals("test@example.com", state.email)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `update password updates state`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.UpdatePassword("secret123"))

            viewModel.state.test {
                val state = awaitItem()
                assertEquals("secret123", state.password)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `toggle mode switches between sign in and sign up`() =
        runTest {
            val viewModel = createViewModel()

            assertFalse(viewModel.state.value.isSignUp)

            viewModel.onEvent(AuthEvent.ToggleMode)

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.isSignUp)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `successful sign in clears submitting state`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.UpdateEmail("test@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNull(state.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `failed sign in shows error`() =
        runTest {
            authRepo.signInError = RuntimeException("Invalid credentials")
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.UpdateEmail("test@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNotNull(state.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `clear error resets error`() =
        runTest {
            authRepo.signInError = RuntimeException("Invalid credentials")
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.UpdateEmail("test@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)

            viewModel.onEvent(AuthEvent.ClearError)

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `successful sign up clears submitting state`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.ToggleMode)
            viewModel.onEvent(AuthEvent.UpdateEmail("new@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNull(state.error)
                assertNull(state.emailConfirmationSentTo)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `sign up with email-confirmation required surfaces emailConfirmationSentTo and clears password`() =
        runTest {
            // Locks the post-bug behavior surfaced 2026-05-13: Supabase
            // Dashboard had Confirm email enabled in production, so
            // signUpWith succeeded at HTTP but no session was created.
            // The ViewModel must transition to a "check your email" state
            // and clear the password field — NOT stay silent waiting for
            // an Authenticated transition that will never arrive.
            authRepo.signUpEmailConfirmationRequired = true
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.ToggleMode)
            viewModel.onEvent(AuthEvent.UpdateEmail("new@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNull(state.error)
                assertEquals("new@example.com", state.emailConfirmationSentTo)
                assertEquals("", state.password)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dismiss email confirmation returns to sign-in mode with cleared password`() =
        runTest {
            // After EmailConfirmationRequired surfaces the "check your
            // email" view, the user taps "ログイン画面に戻る" to return.
            // The ViewModel must reset emailConfirmationSentTo, flip
            // isSignUp back to false, and ensure no stale password
            // remains in form state.
            authRepo.signUpEmailConfirmationRequired = true
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.ToggleMode)
            viewModel.onEvent(AuthEvent.UpdateEmail("new@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)
            viewModel.onEvent(AuthEvent.DismissEmailConfirmation)

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.emailConfirmationSentTo)
                assertFalse(state.isSignUp)
                assertEquals("", state.password)
                // Email is intentionally retained so the user can sign-in
                // immediately after confirming via the link — not blanked.
                assertEquals("new@example.com", state.email)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `sign up with already-registered email surfaces same UI as new email to preserve Supabase obscurity`() =
        runTest {
            // Models the 2026-05-13 operator confusion: user repeatedly
            // tried to sign up with their own pre-existing
            // b150005@outlook.jp email. Supabase returned HTTP 200 OK
            // with empty identities each time, by design to prevent
            // email-enumeration attacks (OWASP A07 — Identification
            // and Authentication Failures).
            //
            // Critical security property: the UI for AlreadyRegistered
            // must be INDISTINGUISHABLE from the UI for
            // EmailConfirmationRequired. Specifically:
            //   - no UserAlreadyExists error alert (would leak that
            //     the email exists)
            //   - no auto-flip to sign-in mode (would also leak)
            //   - same emailConfirmationSentTo population so the same
            //     "check your email" view renders
            //
            // The legitimate owner discovers their existing-account
            // status via the "you may already have an account, try
            // signing in" hint surfaced on the confirmation screen —
            // observable only to someone with access to their inbox
            // (who would see no confirmation email arrive), not to a
            // network observer or screen-recorder attacker.
            authRepo.signUpEmailAlreadyRegistered = true
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.ToggleMode)
            viewModel.onEvent(AuthEvent.UpdateEmail("existing@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                // CRITICAL: no error surfaced — same as new-email branch
                assertNull(state.error)
                // CRITICAL: stays in sign-up mode — same as new-email
                // branch. Dismissing the confirmation view (via
                // AuthEvent.DismissEmailConfirmation) flips both cases
                // consistently to sign-in mode.
                assertTrue(state.isSignUp)
                // CRITICAL: same observable surface as new-email branch
                assertEquals("existing@example.com", state.emailConfirmationSentTo)
                // Password cleared (matches EmailConfirmationRequired)
                assertEquals("", state.password)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
