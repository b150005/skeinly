package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant

class CloseSuggestionUseCaseTest {
    private lateinit var prRepo: FakeSuggestionRepository
    private lateinit var authRepo: FakeAuthRepository
    private lateinit var useCase: CloseSuggestionUseCase

    @BeforeTest
    fun setUp() {
        prRepo = FakeSuggestionRepository()
        authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated(userId = "user-fork", email = "f@example.com"))
        useCase = CloseSuggestionUseCase(prRepo, authRepo)
    }

    private fun openPr() =
        Suggestion(
            id = "pr-1",
            sourcePatternId = "pat-fork",
            sourceBranchId = "br-source-main",
            sourceTipRevisionId = "rev-source-tip",
            targetPatternId = "pat-upstream",
            targetBranchId = "br-target-main",
            commonAncestorRevisionId = "rev-ancestor",
            authorId = "user-fork",
            title = "Reworked sleeve",
            description = null,
            status = SuggestionStatus.OPEN,
            appliedVersionId = null,
            appliedAt = null,
            closedAt = null,
            createdAt = Instant.parse("2026-04-25T10:00:00Z"),
            updatedAt = Instant.parse("2026-04-25T10:00:00Z"),
        )

    @Test
    fun `invoke closes an open PR and stamps closedAt`() =
        runTest {
            val result = useCase(openPr())

            assertIs<UseCaseResult.Success<Suggestion>>(result)
            assertEquals(SuggestionStatus.CLOSED, result.value.status)
            assertNotNull(result.value.closedAt)
            assertNotNull(prRepo.lastClosed)
        }

    @Test
    fun `invoke rejects a PR that is already merged`() =
        runTest {
            val merged = openPr().copy(status = SuggestionStatus.APPLIED)

            val result = useCase(merged)

            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.OperationNotAllowed, result.error)
        }

    @Test
    fun `invoke rejects a PR that is already closed`() =
        runTest {
            val closed = openPr().copy(status = SuggestionStatus.CLOSED)

            val result = useCase(closed)

            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.OperationNotAllowed, result.error)
        }

    @Test
    fun `invoke fails Authentication when user is not signed in`() =
        runTest {
            authRepo.setAuthState(AuthState.Unauthenticated)

            val result = useCase(openPr())

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Authentication>(result.error)
        }

    @Test
    fun `invoke wraps a repository exception as Failure`() =
        runTest {
            prRepo.nextCloseError = IllegalStateException("network down")

            val result = useCase(openPr())

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(result.error)
        }
}
