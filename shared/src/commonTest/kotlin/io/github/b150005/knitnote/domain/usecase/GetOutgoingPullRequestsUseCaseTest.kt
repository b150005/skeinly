package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class GetOutgoingPullRequestsUseCaseTest {
    private fun makePr(
        id: String,
        authorId: String = "user-1",
        status: PullRequestStatus = PullRequestStatus.OPEN,
        createdAtIso: String = "2026-04-25T10:00:00Z",
    ) = PullRequest(
        id = id,
        sourcePatternId = "pat-fork",
        sourceBranchId = "br-source-main",
        sourceTipRevisionId = "rev-source-tip",
        targetPatternId = "pat-upstream",
        targetBranchId = "br-target-main",
        commonAncestorRevisionId = "rev-ancestor",
        authorId = authorId,
        title = "Tweaked stitches",
        description = null,
        status = status,
        mergedRevisionId = null,
        mergedAt = null,
        closedAt = null,
        createdAt = Instant.parse(createdAtIso),
        updatedAt = Instant.parse(createdAtIso),
    )

    @Test
    fun `invoke returns Success with seeded outgoing prs`() =
        runTest {
            val repo = FakePullRequestRepository()
            repo.setOutgoing("user-1", listOf(makePr("pr-out-1"), makePr("pr-out-2")))
            val useCase = GetOutgoingPullRequestsUseCase(repo)

            val result = useCase("user-1")

            assertIs<UseCaseResult.Success<List<PullRequest>>>(result)
            assertEquals(listOf("pr-out-1", "pr-out-2"), result.value.map { it.id })
        }

    @Test
    fun `invoke returns Success with empty list when none cached`() =
        runTest {
            val useCase = GetOutgoingPullRequestsUseCase(FakePullRequestRepository())

            val result = useCase("user-1")

            assertIs<UseCaseResult.Success<List<PullRequest>>>(result)
            assertTrue(result.value.isEmpty())
        }

    @Test
    fun `invoke wraps repository exception as Failure with mapped UseCaseError`() =
        runTest {
            val repo = FakePullRequestRepository()
            repo.nextGetOutgoingError = IllegalStateException("network down")
            val useCase = GetOutgoingPullRequestsUseCase(repo)

            val result = useCase("user-1")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(result.error)
        }

    @Test
    fun `observe returns Flow seeded from repository`() =
        runTest {
            val repo = FakePullRequestRepository()
            repo.setOutgoing("user-1", listOf(makePr("pr-live")))
            val useCase = GetOutgoingPullRequestsUseCase(repo)

            val emitted = useCase.observe("user-1").first()

            assertEquals(1, emitted.size)
            assertEquals("pr-live", emitted.first().id)
        }

    @Test
    fun `observe is scoped to ownerId only`() =
        runTest {
            val repo = FakePullRequestRepository()
            repo.setOutgoing("user-1", listOf(makePr("pr-mine", authorId = "user-1")))
            repo.setOutgoing("user-other", listOf(makePr("pr-not-mine", authorId = "user-other")))
            val useCase = GetOutgoingPullRequestsUseCase(repo)

            val mine = useCase.observe("user-1").first()

            assertEquals(listOf("pr-mine"), mine.map { it.id })
        }
}
