package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.AuthState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeleteAccountUseCaseTest {
    private val fakeAuth = FakeAuthRepository()
    private val closeChannels = CloseRealtimeChannelsUseCase(null, null, null)
    private val deleteAccount = DeleteAccountUseCase(fakeAuth, closeChannels)

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

    @Test
    fun `delete account closes all realtime channels before deletion`() =
        runTest {
            val share = FakeShareRepository()
            val comment = FakeCommentRepository()
            val activity = FakeActivityRepository()
            val useCase =
                DeleteAccountUseCase(
                    fakeAuth,
                    CloseRealtimeChannelsUseCase(share, comment, activity),
                )

            fakeAuth.setAuthState(AuthState.Authenticated("user-1", "a@b.com"))
            useCase()

            assertTrue(share.closeChannelCalled)
            assertTrue(comment.closeChannelCalled)
            assertTrue(activity.closeChannelCalled)
        }
}
