package io.github.b150005.knitnote.ui.sharedcontent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Share
import io.github.b150005.knitnote.domain.model.SharePermission
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.ForkSharedPatternUseCase
import io.github.b150005.knitnote.domain.usecase.ResolveShareTokenUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toErrorMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SharedContentState(
    val pattern: Pattern? = null,
    val share: Share? = null,
    val projectCount: Int = 0,
    val isLoading: Boolean = true,
    val error: ErrorMessage? = null,
    val isForkInProgress: Boolean = false,
)

sealed interface SharedContentEvent {
    data object Fork : SharedContentEvent

    data object ClearError : SharedContentEvent
}

class SharedContentViewModel(
    private val token: String? = null,
    private val shareId: String? = null,
    private val resolveShareToken: ResolveShareTokenUseCase,
    private val forkSharedPattern: ForkSharedPatternUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(SharedContentState())
    val state: StateFlow<SharedContentState> = _state.asStateFlow()

    private val _forkedProjectChannel = Channel<String>(Channel.BUFFERED)
    val forkedProjectId: Flow<String> = _forkedProjectChannel.receiveAsFlow()

    init {
        resolveContent()
    }

    fun onEvent(event: SharedContentEvent) {
        when (event) {
            SharedContentEvent.Fork -> fork()
            SharedContentEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun resolveContent() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            when (val result = resolveShareToken(token = token, shareId = shareId)) {
                is UseCaseResult.Success -> {
                    _state.update {
                        it.copy(
                            pattern = result.value.pattern,
                            share = result.value.share,
                            projectCount = result.value.projects.size,
                            isLoading = false,
                        )
                    }
                }
                is UseCaseResult.Failure -> {
                    _state.update {
                        it.copy(isLoading = false, error = result.error.toErrorMessage())
                    }
                }
            }
        }
    }

    private fun fork() {
        val share = _state.value.share ?: return
        if (share.permission != SharePermission.FORK) return

        viewModelScope.launch {
            _state.update { it.copy(isForkInProgress = true, error = null) }

            when (val result = forkSharedPattern(share.id)) {
                is UseCaseResult.Success -> {
                    _state.update { it.copy(isForkInProgress = false) }
                    _forkedProjectChannel.send(result.value.project.id)
                }
                is UseCaseResult.Failure -> {
                    _state.update {
                        it.copy(
                            isForkInProgress = false,
                            error = result.error.toErrorMessage(),
                        )
                    }
                }
            }
        }
    }
}
