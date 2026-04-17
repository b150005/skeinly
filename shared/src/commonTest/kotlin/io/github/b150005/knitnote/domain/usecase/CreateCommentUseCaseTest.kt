package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.Comment
import io.github.b150005.knitnote.domain.model.CommentTargetType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CreateCommentUseCaseTest {
    @Test
    fun `returns failure when comment repository is null`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val useCase = CreateCommentUseCase(null, authRepo)

            val result = useCase(CommentTargetType.PROJECT, "proj-1", "Hello")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns failure when not authenticated`() =
        runTest {
            val useCase = CreateCommentUseCase(FakeCommentRepository(), FakeAuthRepository())

            val result = useCase(CommentTargetType.PROJECT, "12345678-1234-1234-1234-123456789012", "Hello")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns failure when body is empty`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val useCase = CreateCommentUseCase(FakeCommentRepository(), authRepo)

            val result = useCase(CommentTargetType.PROJECT, "12345678-1234-1234-1234-123456789012", "   ")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `creates comment successfully`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val commentRepo = FakeCommentRepository()
            val useCase = CreateCommentUseCase(commentRepo, authRepo)

            val result = useCase(CommentTargetType.PROJECT, "12345678-1234-1234-1234-123456789012", "Great pattern!")

            assertIs<UseCaseResult.Success<Comment>>(result)
            assertEquals("user-1", result.value.authorId)
            assertEquals("Great pattern!", result.value.body)
            assertEquals(CommentTargetType.PROJECT, result.value.targetType)
            assertEquals("12345678-1234-1234-1234-123456789012", result.value.targetId)
        }

    @Test
    fun `trims whitespace from body`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val useCase = CreateCommentUseCase(FakeCommentRepository(), authRepo)

            val result = useCase(CommentTargetType.PROJECT, "12345678-1234-1234-1234-123456789012", "  Hello  ")

            assertIs<UseCaseResult.Success<Comment>>(result)
            assertEquals("Hello", result.value.body)
        }

    @Test
    fun `returns failure when body exceeds max length`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val useCase = CreateCommentUseCase(FakeCommentRepository(), authRepo)

            val longBody = "a".repeat(2001)
            val result = useCase(CommentTargetType.PROJECT, "12345678-1234-1234-1234-123456789012", longBody)
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns failure when target ID is not a UUID`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val useCase = CreateCommentUseCase(FakeCommentRepository(), authRepo)

            val result = useCase(CommentTargetType.PROJECT, "not-a-uuid", "Hello")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `stores comment in repository`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val commentRepo = FakeCommentRepository()
            val useCase = CreateCommentUseCase(commentRepo, authRepo)

            useCase(CommentTargetType.PROJECT, "12345678-1234-1234-1234-123456789012", "Test")

            val stored = commentRepo.getByTarget(CommentTargetType.PROJECT, "12345678-1234-1234-1234-123456789012")
            assertEquals(1, stored.size)
        }
}
