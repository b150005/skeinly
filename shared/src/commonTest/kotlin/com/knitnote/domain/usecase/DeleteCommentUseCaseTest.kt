package com.knitnote.domain.usecase

import com.knitnote.domain.model.AuthState
import com.knitnote.domain.model.Comment
import com.knitnote.domain.model.CommentTargetType
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeleteCommentUseCaseTest {

    private val now = Instant.fromEpochMilliseconds(1000)

    private fun makeComment(id: String, authorId: String = "user-1") = Comment(
        id = id,
        authorId = authorId,
        targetType = CommentTargetType.PROJECT,
        targetId = "proj-1",
        body = "Test comment",
        createdAt = now,
    )

    @Test
    fun `returns failure when comment repository is null`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
        val useCase = DeleteCommentUseCase(null, authRepo)

        val result = useCase("c-1")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `returns failure when not authenticated`() = runTest {
        val useCase = DeleteCommentUseCase(FakeCommentRepository(), FakeAuthRepository())

        val result = useCase("c-1")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `returns failure when comment not found`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
        val useCase = DeleteCommentUseCase(FakeCommentRepository(), authRepo)

        val result = useCase("non-existent")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }

    @Test
    fun `returns not found when user is not author`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-2", "test@test.com"))
        val commentRepo = FakeCommentRepository()
        commentRepo.addComment(makeComment("c-1", authorId = "user-1"))
        val useCase = DeleteCommentUseCase(commentRepo, authRepo)

        val result = useCase("c-1")
        assertIs<UseCaseResult.Failure>(result)
        // Returns NotFound (not Validation) to prevent ID enumeration
        assertIs<UseCaseError.NotFound>(result.error)
    }

    @Test
    fun `deletes own comment successfully`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
        val commentRepo = FakeCommentRepository()
        commentRepo.addComment(makeComment("c-1"))
        val useCase = DeleteCommentUseCase(commentRepo, authRepo)

        val result = useCase("c-1")
        assertIs<UseCaseResult.Success<Unit>>(result)

        val remaining = commentRepo.getByTarget(CommentTargetType.PROJECT, "proj-1")
        assertEquals(0, remaining.size)
    }
}
