package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class GetPullRequestUseCaseTest {
    private fun makePr(
        id: String,
        targetPatternId: String = "pat-upstream",
        authorId: String? = "user-fork",
        status: PullRequestStatus = PullRequestStatus.OPEN,
    ) = PullRequest(
        id = id,
        sourcePatternId = "pat-fork",
        sourceBranchId = "br-source-main",
        sourceTipRevisionId = "rev-source-tip",
        targetPatternId = targetPatternId,
        targetBranchId = "br-target-main",
        commonAncestorRevisionId = "rev-ancestor",
        authorId = authorId,
        title = "Some PR",
        description = null,
        status = status,
        mergedRevisionId = null,
        mergedAt = null,
        closedAt = null,
        createdAt = Instant.parse("2026-04-25T10:00:00Z"),
        updatedAt = Instant.parse("2026-04-25T10:00:00Z"),
    )

    @Test
    fun `invoke returns Success when PR is cached`() =
        runTest {
            val repo = FakePullRequestRepository()
            repo.seedById(makePr("pr-1"))
            val useCase = GetPullRequestUseCase(repo)

            val result = useCase("pr-1")

            assertIs<UseCaseResult.Success<PullRequest>>(result)
            assertEquals("pr-1", result.value.id)
        }

    @Test
    fun `invoke returns NotFound when PR id is unknown`() =
        runTest {
            val useCase = GetPullRequestUseCase(FakePullRequestRepository())

            val result = useCase("missing")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.NotFound>(result.error)
        }

    @Test
    fun `invoke wraps repository exception as Failure with mapped UseCaseError`() =
        runTest {
            val repo = FakePullRequestRepository()
            repo.nextGetByIdError = IllegalStateException("boom")
            val useCase = GetPullRequestUseCase(repo)

            val result = useCase("pr-anything")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(result.error)
        }

    @Test
    fun `observe emits matching PR from incoming flow when scope is INCOMING`() =
        runTest {
            val repo = FakePullRequestRepository()
            repo.setIncoming("owner-1", listOf(makePr("pr-target")))
            val useCase = GetPullRequestUseCase(repo)

            val emitted = useCase.observe("pr-target", "owner-1", PullRequestObserveScope.INCOMING).first()

            assertEquals("pr-target", emitted.id)
        }

    @Test
    fun `observe filters out PRs with non-matching id`() =
        runTest {
            val repo = FakePullRequestRepository()
            repo.setIncoming("owner-1", listOf(makePr("pr-a"), makePr("pr-b")))
            val useCase = GetPullRequestUseCase(repo)

            val emitted = useCase.observe("pr-b", "owner-1", PullRequestObserveScope.INCOMING).first()

            assertEquals("pr-b", emitted.id)
        }

    @Test
    fun `observe surfaces an updated PR row when peer Realtime emission flips status`() =
        runTest {
            val repo = FakePullRequestRepository()
            repo.setIncoming("owner-1", listOf(makePr("pr-1")))
            val useCase = GetPullRequestUseCase(repo)

            // Simulate close-from-peer landing through the repository.
            repo.setIncoming(
                "owner-1",
                listOf(makePr("pr-1", status = PullRequestStatus.CLOSED)),
            )

            val emitted = useCase.observe("pr-1", "owner-1", PullRequestObserveScope.INCOMING).first()
            assertEquals(PullRequestStatus.CLOSED, emitted.status)
        }
}
