package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.PullRequestComment
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PostPullRequestCommentUseCaseTest {
    private lateinit var prRepo: FakePullRequestRepository
    private lateinit var authRepo: FakeAuthRepository
    private lateinit var useCase: PostPullRequestCommentUseCase

    @BeforeTest
    fun setUp() {
        prRepo = FakePullRequestRepository()
        authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated(userId = "user-fork", email = "f@example.com"))
        useCase = PostPullRequestCommentUseCase(prRepo, authRepo)
    }

    @Test
    fun `invoke posts a trimmed comment with the current user as author`() =
        runTest {
            val result = useCase("pr-1", "  Looks great!  ")

            assertIs<UseCaseResult.Success<PullRequestComment>>(result)
            assertEquals("Looks great!", result.value.body)
            assertEquals("user-fork", result.value.authorId)
            assertEquals("pr-1", result.value.pullRequestId)
            assertNotNull(prRepo.lastPosted)
        }

    @Test
    fun `invoke rejects an empty body`() =
        runTest {
            val result = useCase("pr-1", "   ")

            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.FieldRequired, result.error)
        }

    @Test
    fun `invoke rejects a body longer than the 5000 char ceiling`() =
        runTest {
            val tooLong = "x".repeat(PostPullRequestCommentUseCase.MAX_BODY_LENGTH + 1)

            val result = useCase("pr-1", tooLong)

            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.FieldTooLong, result.error)
        }

    @Test
    fun `invoke fails Authentication when no user is signed in`() =
        runTest {
            authRepo.setAuthState(AuthState.Unauthenticated)

            val result = useCase("pr-1", "hi")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Authentication>(result.error)
        }

    @Test
    fun `invoke wraps a repository exception as Unknown Failure`() =
        runTest {
            prRepo.nextPostCommentError = IllegalStateException("net down")

            val result = useCase("pr-1", "hi")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(result.error)
        }

    @Test
    fun `invoke generates a fresh id and createdAt timestamp on every call`() =
        runTest {
            val first = useCase("pr-1", "first")
            val second = useCase("pr-1", "second")

            val firstValue = (first as UseCaseResult.Success).value
            val secondValue = (second as UseCaseResult.Success).value
            // Distinct UUIDs guarantee idempotent INSERT-or-IGNORE on the
            // remote side won't silently coalesce two distinct posts.
            assertNotNull(firstValue.id)
            assertNotNull(secondValue.id)
            assertEquals(false, firstValue.id == secondValue.id)
        }
}
