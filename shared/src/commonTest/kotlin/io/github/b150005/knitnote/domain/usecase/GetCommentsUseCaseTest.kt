package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Comment
import io.github.b150005.knitnote.domain.model.CommentTargetType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class GetCommentsUseCaseTest {
    private val now = Instant.fromEpochMilliseconds(1000)

    private fun makeComment(
        id: String,
        targetType: CommentTargetType = CommentTargetType.PROJECT,
        targetId: String = "proj-1",
    ) = Comment(
        id = id,
        authorId = "user-1",
        targetType = targetType,
        targetId = targetId,
        body = "Test comment $id",
        createdAt = now,
    )

    @Test
    fun `returns failure when comment repository is null`() =
        runTest {
            val useCase = GetCommentsUseCase(null)
            val result = useCase(CommentTargetType.PROJECT, "proj-1")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns comments for target`() =
        runTest {
            val repo = FakeCommentRepository()
            repo.addComment(makeComment("c-1"))
            repo.addComment(makeComment("c-2"))
            repo.addComment(makeComment("c-3", targetId = "proj-2"))
            val useCase = GetCommentsUseCase(repo)

            val result = useCase(CommentTargetType.PROJECT, "proj-1")

            assertIs<UseCaseResult.Success<List<Comment>>>(result)
            assertEquals(2, result.value.size)
        }

    @Test
    fun `returns empty list when no comments`() =
        runTest {
            val useCase = GetCommentsUseCase(FakeCommentRepository())

            val result = useCase(CommentTargetType.PROJECT, "proj-1")

            assertIs<UseCaseResult.Success<List<Comment>>>(result)
            assertEquals(0, result.value.size)
        }

    @Test
    fun `observe returns empty flow when repository is null`() =
        runTest {
            val useCase = GetCommentsUseCase(null)
            val result = useCase.observe(CommentTargetType.PROJECT, "proj-1").first()
            assertTrue(result.isEmpty())
        }

    @Test
    fun `filters by target type`() =
        runTest {
            val repo = FakeCommentRepository()
            repo.addComment(makeComment("c-1", targetType = CommentTargetType.PROJECT, targetId = "id-1"))
            repo.addComment(makeComment("c-2", targetType = CommentTargetType.PATTERN, targetId = "id-1"))
            val useCase = GetCommentsUseCase(repo)

            val result = useCase(CommentTargetType.PATTERN, "id-1")

            assertIs<UseCaseResult.Success<List<Comment>>>(result)
            assertEquals(1, result.value.size)
            assertEquals("c-2", result.value.first().id)
        }
}
