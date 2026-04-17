package io.github.b150005.knitnote.domain.usecase

import app.cash.turbine.test
import io.github.b150005.knitnote.domain.model.AuthState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

class ObserveAuthStateUseCaseTest {
    private val fakeAuth = FakeAuthRepository()
    private val observeAuth = ObserveAuthStateUseCase(fakeAuth)

    @Test
    fun `initial state is Unauthenticated`() =
        runTest {
            observeAuth().test {
                assertIs<AuthState.Unauthenticated>(awaitItem())
            }
        }

    @Test
    fun `emits Authenticated after sign in`() =
        runTest {
            observeAuth().test {
                assertIs<AuthState.Unauthenticated>(awaitItem())

                fakeAuth.setAuthState(AuthState.Authenticated("user-1", "a@b.com"))
                val state = awaitItem()
                assertIs<AuthState.Authenticated>(state)
                kotlin.test.assertEquals("user-1", state.userId)
            }
        }

    @Test
    fun `emits Unauthenticated after sign out`() =
        runTest {
            fakeAuth.setAuthState(AuthState.Authenticated("user-1", "a@b.com"))

            observeAuth().test {
                assertIs<AuthState.Authenticated>(awaitItem())

                fakeAuth.setAuthState(AuthState.Unauthenticated)
                assertIs<AuthState.Unauthenticated>(awaitItem())
            }
        }
}
