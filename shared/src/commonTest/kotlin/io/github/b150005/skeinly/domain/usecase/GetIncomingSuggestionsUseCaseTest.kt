package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class GetIncomingSuggestionsUseCaseTest {
    private fun makePr(
        id: String,
        targetPatternId: String = "pat-upstream",
        sourcePatternId: String = "pat-fork",
        authorId: String? = "user-fork",
        status: SuggestionStatus = SuggestionStatus.OPEN,
        createdAtIso: String = "2026-04-25T10:00:00Z",
    ) = Suggestion(
        id = id,
        sourcePatternId = sourcePatternId,
        sourceBranchId = "br-source-main",
        sourceTipRevisionId = "rev-source-tip",
        targetPatternId = targetPatternId,
        targetBranchId = "br-target-main",
        commonAncestorRevisionId = "rev-ancestor",
        authorId = authorId,
        title = "Reworked sleeve",
        description = null,
        status = status,
        appliedVersionId = null,
        appliedAt = null,
        closedAt = null,
        createdAt = Instant.parse(createdAtIso),
        updatedAt = Instant.parse(createdAtIso),
    )

    @Test
    fun `invoke returns Success with seeded incoming prs`() =
        runTest {
            val repo = FakeSuggestionRepository()
            repo.setIncoming("owner-1", listOf(makePr("pr-1"), makePr("pr-2")))
            val useCase = GetIncomingSuggestionsUseCase(repo)

            val result = useCase("owner-1")

            assertIs<UseCaseResult.Success<List<Suggestion>>>(result)
            assertEquals(listOf("pr-1", "pr-2"), result.value.map { it.id })
        }

    @Test
    fun `invoke returns Success with empty list when none cached`() =
        runTest {
            val useCase = GetIncomingSuggestionsUseCase(FakeSuggestionRepository())

            val result = useCase("owner-1")

            assertIs<UseCaseResult.Success<List<Suggestion>>>(result)
            assertTrue(result.value.isEmpty())
        }

    @Test
    fun `invoke wraps repository exception as Failure with mapped UseCaseError`() =
        runTest {
            val repo = FakeSuggestionRepository()
            repo.nextGetIncomingError = IllegalStateException("boom")
            val useCase = GetIncomingSuggestionsUseCase(repo)

            val result = useCase("owner-1")

            assertIs<UseCaseResult.Failure>(result)
            // Generic exception → Unknown bucket via toUseCaseError().
            assertIs<UseCaseError.Unknown>(result.error)
        }

    @Test
    fun `observe returns Flow seeded from repository`() =
        runTest {
            val repo = FakeSuggestionRepository()
            repo.setIncoming("owner-1", listOf(makePr("pr-live")))
            val useCase = GetIncomingSuggestionsUseCase(repo)

            val emitted = useCase.observe("owner-1").first()

            assertEquals(1, emitted.size)
            assertEquals("pr-live", emitted.first().id)
        }

    @Test
    fun `observe is scoped to ownerId only`() =
        runTest {
            val repo = FakeSuggestionRepository()
            repo.setIncoming("owner-1", listOf(makePr("pr-mine", targetPatternId = "pat-mine")))
            repo.setIncoming("owner-other", listOf(makePr("pr-not-mine", targetPatternId = "pat-other")))
            val useCase = GetIncomingSuggestionsUseCase(repo)

            val mine = useCase.observe("owner-1").first()

            assertEquals(listOf("pr-mine"), mine.map { it.id })
        }
}
