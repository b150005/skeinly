package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.AuthState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignOutUseCaseTest {
    private val fakeAuth = FakeAuthRepository()
    private val closeChannels = CloseRealtimeChannelsUseCase(null, null, null)
    private val signOut = SignOutUseCase(fakeAuth, closeChannels)

    @Test
    fun `sign out returns Success`() =
        runTest {
            fakeAuth.setAuthState(AuthState.Authenticated("user-1", "a@b.com"))
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

    @Test
    fun `sign out closes all realtime channels before signing out`() =
        runTest {
            val share = FakeShareRepository()
            val comment = FakeCommentRepository()
            val activity = FakeActivityRepository()
            val useCase =
                SignOutUseCase(
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
