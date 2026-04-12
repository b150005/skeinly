package com.knitnote.domain.usecase

import com.knitnote.domain.model.AuthState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class DeleteAccountUseCaseTest {
    private val fakeAuth = FakeAuthRepository()
    private val deleteAccount = DeleteAccountUseCase(fakeAuth, null, null, null)

    @Test
    fun `delete account returns Success`() =
        runTest {
            fakeAuth.setAuthState(AuthState.Authenticated("user-1", "a@b.com"))
            val result = deleteAccount()
            assertIs<UseCaseResult.Success<Unit>>(result)
            assertNull(fakeAuth.getCurrentUserId())
        }

    @Test
    fun `delete account failure returns Failure`() =
        runTest {
            fakeAuth.deleteAccountError = RuntimeException("Server error")
            val result = deleteAccount()
            assertIs<UseCaseResult.Failure>(result)
        }

    @Test
    fun `delete account clears auth state`() =
        runTest {
            fakeAuth.setAuthState(AuthState.Authenticated("user-1", "a@b.com"))
            deleteAccount()
            assertIs<AuthState.Unauthenticated>(fakeAuth.observeAuthState().first())
        }
}
