package com.knitnote.ui.auth

import app.cash.turbine.test
import com.knitnote.domain.model.AuthState
import com.knitnote.domain.usecase.FakeAuthRepository
import com.knitnote.domain.usecase.ObserveAuthStateUseCase
import com.knitnote.domain.usecase.SignInUseCase
import com.knitnote.domain.usecase.SignUpUseCase
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

    private fun createViewModel(): AuthViewModel = AuthViewModel(
        observeAuthState = ObserveAuthStateUseCase(authRepo),
        signIn = SignInUseCase(authRepo),
        signUp = SignUpUseCase(authRepo),
    )

    @Test
    fun `initial state is unauthenticated`() = runTest {
        val viewModel = createViewModel()

        viewModel.state.test {
            val state = awaitItem()
            assertEquals(AuthState.Unauthenticated, state.authState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update email updates state`() = runTest {
        val viewModel = createViewModel()

        viewModel.onEvent(AuthEvent.UpdateEmail("test@example.com"))

        viewModel.state.test {
            val state = awaitItem()
            assertEquals("test@example.com", state.email)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update password updates state`() = runTest {
        val viewModel = createViewModel()

        viewModel.onEvent(AuthEvent.UpdatePassword("secret123"))

        viewModel.state.test {
            val state = awaitItem()
            assertEquals("secret123", state.password)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggle mode switches between sign in and sign up`() = runTest {
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
    fun `successful sign in clears submitting state`() = runTest {
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
    fun `failed sign in shows error`() = runTest {
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
    fun `clear error resets error`() = runTest {
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
    fun `successful sign up clears submitting state`() = runTest {
        val viewModel = createViewModel()

        viewModel.onEvent(AuthEvent.ToggleMode)
        viewModel.onEvent(AuthEvent.UpdateEmail("new@example.com"))
        viewModel.onEvent(AuthEvent.UpdatePassword("password"))
        viewModel.onEvent(AuthEvent.Submit)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isSubmitting)
            assertNull(state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
