package com.knitnote.ui.sharedwithme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knitnote.domain.model.Share
import com.knitnote.domain.repository.PatternRepository
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
    val patternTitles: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

sealed interface SharedWithMeEvent {
    data object Load : SharedWithMeEvent
    data object ClearError : SharedWithMeEvent
}

class SharedWithMeViewModel(
    private val getReceivedShares: GetReceivedSharesUseCase,
    private val patternRepository: PatternRepository,
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
                            val titles = resolvePatternTitles(result.value)
                            _state.update {
                                it.copy(
                                    shares = result.value,
                                    patternTitles = titles,
                                    isLoading = false,
                                )
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

    private suspend fun resolvePatternTitles(shares: List<Share>): Map<String, String> {
        val distinctIds = shares.map { it.patternId }.distinct()
        return distinctIds.mapNotNull { patternId ->
            patternRepository.getById(patternId)?.let { pattern ->
                patternId to pattern.title
            }
        }.toMap()
    }
}
