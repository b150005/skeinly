package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.PullRequestComment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class GetPullRequestCommentsUseCaseTest {
    private fun makeComment(
        id: String,
        prId: String = "pr-1",
        body: String = "Looks good",
    ) = PullRequestComment(
        id = id,
        pullRequestId = prId,
        authorId = "user-1",
        body = body,
        createdAt = Instant.parse("2026-04-25T10:00:00Z"),
    )

    @Test
    fun `invoke returns Success with seeded comments`() =
        runTest {
            val repo = FakePullRequestRepository()
            repo.setComments("pr-1", listOf(makeComment("c1"), makeComment("c2")))
            val useCase = GetPullRequestCommentsUseCase(repo)

            val result = useCase("pr-1")

            assertIs<UseCaseResult.Success<List<PullRequestComment>>>(result)
            assertEquals(listOf("c1", "c2"), result.value.map { it.id })
        }

    @Test
    fun `invoke returns Success with empty list when none cached`() =
        runTest {
            val useCase = GetPullRequestCommentsUseCase(FakePullRequestRepository())

            val result = useCase("pr-empty")

            assertIs<UseCaseResult.Success<List<PullRequestComment>>>(result)
            assertTrue(result.value.isEmpty())
        }

    @Test
    fun `invoke wraps repository exception as Failure`() =
        runTest {
            val repo = FakePullRequestRepository()
            repo.nextGetCommentsError = IllegalStateException("network down")
            val useCase = GetPullRequestCommentsUseCase(repo)

            val result = useCase("pr-1")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(result.error)
        }

    @Test
    fun `observe Flow emits seeded comments`() =
        runTest {
            val repo = FakePullRequestRepository()
            repo.setComments("pr-1", listOf(makeComment("c1")))
            val useCase = GetPullRequestCommentsUseCase(repo)

            val emitted = useCase.observe("pr-1").first()

            assertEquals(1, emitted.size)
            assertEquals("c1", emitted.first().id)
        }
}
