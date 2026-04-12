package com.knitnote.domain.usecase

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class SignOutUseCaseTest {
    private val fakeAuth = FakeAuthRepository()
    private val signOut = SignOutUseCase(fakeAuth, null, null, null)

    @Test
    fun `sign out returns Success`() =
        runTest {
            fakeAuth.setAuthState(
                com.knitnote.domain.model.AuthState
                    .Authenticated("user-1", "a@b.com"),
            )
            val result = signOut()
            assertIs<UseCaseResult.Success<Unit>>(result)
            assertNull(fakeAuth.getCurrentUserId())
        }

    @Test
    fun `sign out failure returns Failure`() =
        runTest {
            fakeAuth.signOutError = RuntimeException("Network error")
            val result = signOut()
            assertIs<UseCaseResult.Failure>(result)
        }
}
