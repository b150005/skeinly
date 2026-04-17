package io.github.b150005.knitnote.domain.usecase

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloseRealtimeChannelsUseCaseTest {
    @Test
    fun `closes all channels when all repositories are present`() =
        runTest {
            val share = FakeShareRepository()
            val comment = FakeCommentRepository()
            val activity = FakeActivityRepository()
            val useCase = CloseRealtimeChannelsUseCase(share, comment, activity)

            useCase()

            assertTrue(share.closeChannelCalled)
            assertTrue(comment.closeChannelCalled)
            assertTrue(activity.closeChannelCalled)
        }

    @Test
    fun `handles null repositories gracefully`() =
        runTest {
            val useCase = CloseRealtimeChannelsUseCase(null, null, null)
            useCase()
        }

    @Test
    fun `closes available channels when some repositories are null`() =
        runTest {
            val share = FakeShareRepository()
            val useCase = CloseRealtimeChannelsUseCase(share, null, null)

            useCase()

            assertTrue(share.closeChannelCalled)
        }

    @Test
    fun `closes remaining channels even when one throws`() =
        runTest {
            val share =
                FakeShareRepository().apply {
                    closeChannelError = RuntimeException("share close failed")
                }
            val comment = FakeCommentRepository()
            val activity = FakeActivityRepository()
            val useCase = CloseRealtimeChannelsUseCase(share, comment, activity)

            assertFailsWith<RuntimeException> { useCase() }

            assertTrue(share.closeChannelCalled)
            assertTrue(comment.closeChannelCalled)
            assertTrue(activity.closeChannelCalled)
        }
}
