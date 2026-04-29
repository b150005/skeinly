package io.github.b150005.knitnote.ui.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.Comment
import io.github.b150005.knitnote.domain.model.CommentTargetType
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.repository.UserRepository
import io.github.b150005.knitnote.domain.usecase.CreateCommentUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteCommentUseCase
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.GetCommentsUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommentSectionState(
    val comments: List<Comment> = emptyList(),
    val authors: Map<String, User> = emptyMap(),
    val isLoading: Boolean = true,
    val error: ErrorMessage? = null,
    val isSending: Boolean = false,
)

sealed interface CommentSectionEvent {
    data class PostComment(
        val body: String,
    ) : CommentSectionEvent

    data class DeleteComment(
        val commentId: String,
    ) : CommentSectionEvent

    data object ClearError : CommentSectionEvent
}

class CommentSectionViewModel(
    private val targetType: CommentTargetType,
    private val targetId: String,
    private val getComments: GetCommentsUseCase,
    private val createComment: CreateCommentUseCase,
    private val deleteCommentUseCase: DeleteCommentUseCase,
    private val userRepository: UserRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CommentSectionState())
    val state: StateFlow<CommentSectionState> = _state.asStateFlow()

    init {
        observeComments()
    }

    fun onEvent(event: CommentSectionEvent) {
        when (event) {
            is CommentSectionEvent.PostComment -> postComment(event.body)
            is CommentSectionEvent.DeleteComment -> deleteComment(event.commentId)
            CommentSectionEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun observeComments() {
        getComments
            .observe(targetType, targetId)
            .onEach { comments ->
                resolveAuthors(comments)
                _state.update {
                    it.copy(
                        comments = comments,
                        isLoading = false,
                    )
                }
            }.catch { e ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = ErrorMessage.LoadFailed,
                    )
                }
            }.launchIn(viewModelScope)
    }

    private fun postComment(body: String) {
        viewModelScope.launch {
            _state.update { it.copy(isSending = true) }
            when (val result = createComment(targetType, targetId, body)) {
                is UseCaseResult.Success -> {
                    _state.update { it.copy(isSending = false) }
                }
                is UseCaseResult.Failure -> {
                    _state.update {
                        it.copy(isSending = false, error = result.error.toErrorMessage())
                    }
                }
            }
        }
    }

    private fun deleteComment(commentId: String) {
        viewModelScope.launch {
            when (val result = deleteCommentUseCase(commentId)) {
                is UseCaseResult.Success -> { /* Realtime will update the list */ }
                is UseCaseResult.Failure -> {
                    _state.update { it.copy(error = result.error.toErrorMessage()) }
                }
            }
        }
    }

    private suspend fun resolveAuthors(comments: List<Comment>) {
        val currentAuthors = _state.value.authors
        val newAuthorIds = comments.map { it.authorId }.distinct() - currentAuthors.keys
        if (newAuthorIds.isEmpty()) return

        val resolved =
            newAuthorIds
                .mapNotNull { authorId ->
                    userRepository.getById(authorId)?.let { authorId to it }
                }.toMap()

        _state.update { it.copy(authors = currentAuthors + resolved) }
    }
}
