package com.knitnote.ui.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knitnote.domain.model.Comment
import com.knitnote.domain.model.CommentTargetType
import com.knitnote.domain.model.User
import com.knitnote.domain.repository.UserRepository
import com.knitnote.domain.usecase.CreateCommentUseCase
import com.knitnote.domain.usecase.DeleteCommentUseCase
import com.knitnote.domain.usecase.GetCommentsUseCase
import com.knitnote.domain.usecase.UseCaseResult
import com.knitnote.domain.usecase.toMessage
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
    val error: String? = null,
    val isSending: Boolean = false,
)

sealed interface CommentSectionEvent {
    data class PostComment(val body: String) : CommentSectionEvent
    data class DeleteComment(val commentId: String) : CommentSectionEvent
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
        getComments.observe(targetType, targetId)
            .onEach { comments ->
                resolveAuthors(comments)
                _state.update {
                    it.copy(
                        comments = comments,
                        isLoading = false,
                    )
                }
            }
            .catch { e ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load comments",
                    )
                }
            }
            .launchIn(viewModelScope)
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
                        it.copy(isSending = false, error = result.error.toMessage())
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
                    _state.update { it.copy(error = result.error.toMessage()) }
                }
            }
        }
    }

    private suspend fun resolveAuthors(comments: List<Comment>) {
        val currentAuthors = _state.value.authors
        val newAuthorIds = comments.map { it.authorId }.distinct() - currentAuthors.keys
        if (newAuthorIds.isEmpty()) return

        val resolved = newAuthorIds.mapNotNull { authorId ->
            userRepository.getById(authorId)?.let { authorId to it }
        }.toMap()

        _state.update { it.copy(authors = currentAuthors + resolved) }
    }
}
