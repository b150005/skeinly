package com.knitnote.ui.comments

import com.knitnote.domain.model.AuthState
import com.knitnote.domain.model.Comment
import com.knitnote.domain.model.CommentTargetType
import com.knitnote.domain.model.User
import com.knitnote.domain.usecase.CreateCommentUseCase
import com.knitnote.domain.usecase.DeleteCommentUseCase
import com.knitnote.domain.usecase.FakeAuthRepository
import com.knitnote.domain.usecase.FakeCommentRepository
import com.knitnote.domain.usecase.FakeUserRepository
import com.knitnote.domain.usecase.GetCommentsUseCase
import com.knitnote.data.repository.OfflineUserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommentSectionViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var commentRepo: FakeCommentRepository
    private lateinit var authRepo: FakeAuthRepository
    private lateinit var userRepo: FakeUserRepository

    private val now = Instant.fromEpochMilliseconds(1000)

    private val testUser = User(
        id = "user-1",
        displayName = "Alice",
        avatarUrl = null,
        bio = null,
        createdAt = now,
    )

    private fun makeComment(
        id: String,
        authorId: String = "user-1",
        body: String = "Test comment",
    ) = Comment(
        id = id,
        authorId = authorId,
        targetType = CommentTargetType.PROJECT,
        targetId = "12345678-1234-1234-1234-123456789012",
        body = body,
        createdAt = now,
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        commentRepo = FakeCommentRepository()
        authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
        userRepo = FakeUserRepository()
        userRepo.addUser(testUser)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): CommentSectionViewModel {
        val getComments = GetCommentsUseCase(commentRepo)
        val createComment = CreateCommentUseCase(commentRepo, authRepo)
        val deleteCommentUseCase = DeleteCommentUseCase(commentRepo, authRepo)
        return CommentSectionViewModel(
            targetType = CommentTargetType.PROJECT,
            targetId = "12345678-1234-1234-1234-123456789012",
            getComments = getComments,
            createComment = createComment,
            deleteCommentUseCase = deleteCommentUseCase,
            userRepository = userRepo,
        )
    }

    @Test
    fun `loads comments and resolves authors`() = runTest {
        commentRepo.addComment(makeComment("c-1"))

        val viewModel = createViewModel()
        val state = viewModel.state.value

        assertFalse(state.isLoading)
        assertEquals(1, state.comments.size)
        assertEquals("Alice", state.authors["user-1"]?.displayName)
    }

    @Test
    fun `shows empty state when no comments`() = runTest {
        val viewModel = createViewModel()
        val state = viewModel.state.value

        assertFalse(state.isLoading)
        assertTrue(state.comments.isEmpty())
    }

    @Test
    fun `posts comment successfully`() = runTest {
        val viewModel = createViewModel()

        viewModel.onEvent(CommentSectionEvent.PostComment("Hello!"))

        val state = viewModel.state.value
        assertFalse(state.isSending)
        assertEquals(1, state.comments.size)
        assertEquals("Hello!", state.comments.first().body)
    }

    @Test
    fun `shows error for empty comment body`() = runTest {
        val viewModel = createViewModel()

        viewModel.onEvent(CommentSectionEvent.PostComment("   "))

        val state = viewModel.state.value
        assertFalse(state.isSending)
        assertNotNull(state.error)
    }

    @Test
    fun `deletes own comment`() = runTest {
        commentRepo.addComment(makeComment("c-1"))

        val viewModel = createViewModel()
        assertEquals(1, viewModel.state.value.comments.size)

        viewModel.onEvent(CommentSectionEvent.DeleteComment("c-1"))

        assertEquals(0, viewModel.state.value.comments.size)
    }

    @Test
    fun `shows error when deleting other user comment`() = runTest {
        commentRepo.addComment(makeComment("c-1", authorId = "other-user"))

        val viewModel = createViewModel()

        viewModel.onEvent(CommentSectionEvent.DeleteComment("c-1"))

        assertNotNull(viewModel.state.value.error)
        // Comment should still be there
        assertEquals(1, viewModel.state.value.comments.size)
    }

    @Test
    fun `clears error on ClearError event`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(CommentSectionEvent.PostComment("   "))
        assertNotNull(viewModel.state.value.error)

        viewModel.onEvent(CommentSectionEvent.ClearError)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `works with offline user repository`() = runTest {
        commentRepo.addComment(makeComment("c-1"))

        val getComments = GetCommentsUseCase(commentRepo)
        val createComment = CreateCommentUseCase(commentRepo, authRepo)
        val deleteCommentUseCase = DeleteCommentUseCase(commentRepo, authRepo)
        val viewModel = CommentSectionViewModel(
            targetType = CommentTargetType.PROJECT,
            targetId = "12345678-1234-1234-1234-123456789012",
            getComments = getComments,
            createComment = createComment,
            deleteCommentUseCase = deleteCommentUseCase,
            userRepository = OfflineUserRepository(),
        )

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals(1, state.comments.size)
        assertTrue(state.authors.isEmpty())
    }

    @Test
    fun `filters comments by target`() = runTest {
        commentRepo.addComment(makeComment("c-1"))
        commentRepo.addComment(
            Comment(
                id = "c-2",
                authorId = "user-1",
                targetType = CommentTargetType.PROJECT,
                targetId = "other-proj",
                body = "Other project",
                createdAt = now,
            ),
        )

        val viewModel = createViewModel()
        val state = viewModel.state.value

        assertEquals(1, state.comments.size)
        assertEquals("c-1", state.comments.first().id)
    }
}
