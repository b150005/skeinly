package com.knitnote.ui.sharedwithme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knitnote.domain.model.Share
import com.knitnote.domain.usecase.GetReceivedSharesUseCase
import com.knitnote.domain.usecase.UseCaseResult
import com.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SharedWithMeState(
    val shares: List<Share> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

sealed interface SharedWithMeEvent {
    data object Load : SharedWithMeEvent
    data object ClearError : SharedWithMeEvent
}

class SharedWithMeViewModel(
    private val getReceivedShares: GetReceivedSharesUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(SharedWithMeState())
    val state: StateFlow<SharedWithMeState> = _state.asStateFlow()

    init {
        onEvent(SharedWithMeEvent.Load)
    }

    fun onEvent(event: SharedWithMeEvent) {
        when (event) {
            SharedWithMeEvent.Load -> {
                viewModelScope.launch {
                    _state.update { it.copy(isLoading = true, error = null) }
                    when (val result = getReceivedShares()) {
                        is UseCaseResult.Success -> {
                            _state.update {
                                it.copy(shares = result.value, isLoading = false)
                            }
                        }
                        is UseCaseResult.Failure -> {
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    error = result.error.toMessage(),
                                )
                            }
                        }
                    }
                }
            }
            SharedWithMeEvent.ClearError -> {
                _state.update { it.copy(error = null) }
            }
        }
    }
}
