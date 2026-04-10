package com.knitnote.ui.sharedwithme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knitnote.domain.model.Share
import com.knitnote.domain.model.ShareStatus
import com.knitnote.domain.model.User
import com.knitnote.domain.repository.PatternRepository
import com.knitnote.domain.repository.UserRepository
import com.knitnote.domain.usecase.GetReceivedSharesUseCase
import com.knitnote.domain.usecase.UpdateShareStatusUseCase
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
    val sharers: Map<String, User> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

sealed interface SharedWithMeEvent {
    data object Load : SharedWithMeEvent
    data object ClearError : SharedWithMeEvent
    data class AcceptShare(val shareId: String) : SharedWithMeEvent
    data class DeclineShare(val shareId: String) : SharedWithMeEvent
}

class SharedWithMeViewModel(
    private val getReceivedShares: GetReceivedSharesUseCase,
    private val patternRepository: PatternRepository,
    private val updateShareStatus: UpdateShareStatusUseCase,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SharedWithMeState())
    val state: StateFlow<SharedWithMeState> = _state.asStateFlow()

    init {
        onEvent(SharedWithMeEvent.Load)
    }

    fun onEvent(event: SharedWithMeEvent) {
        viewModelScope.launch {
            when (event) {
                SharedWithMeEvent.Load -> {
                    _state.update { it.copy(isLoading = true, error = null) }
                    when (val result = getReceivedShares()) {
                        is UseCaseResult.Success -> {
                            val titles = resolvePatternTitles(result.value)
                            val sharers = resolveSharers(result.value)
                            _state.update {
                                it.copy(
                                    shares = result.value,
                                    patternTitles = titles,
                                    sharers = sharers,
                                    isLoading = false,
                                )
                            }
                        }
                        is UseCaseResult.Failure -> {
                            _state.update {
                                it.copy(isLoading = false, error = result.error.toMessage())
                            }
                        }
                    }
                }
                SharedWithMeEvent.ClearError -> {
                    _state.update { it.copy(error = null) }
                }
                is SharedWithMeEvent.AcceptShare -> {
                    handleStatusUpdate(event.shareId, ShareStatus.ACCEPTED)
                }
                is SharedWithMeEvent.DeclineShare -> {
                    handleStatusUpdate(event.shareId, ShareStatus.DECLINED)
                }
            }
        }
    }

    private suspend fun handleStatusUpdate(shareId: String, status: ShareStatus) {
        when (val result = updateShareStatus(shareId, status)) {
            is UseCaseResult.Success -> {
                _state.update { state ->
                    state.copy(
                        shares = state.shares.map { share ->
                            if (share.id == shareId) result.value else share
                        },
                    )
                }
            }
            is UseCaseResult.Failure -> {
                _state.update { it.copy(error = result.error.toMessage()) }
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

    private suspend fun resolveSharers(shares: List<Share>): Map<String, User> {
        val distinctIds = shares.map { it.fromUserId }.distinct()
        return userRepository.getByIds(distinctIds).associateBy { it.id }
    }
}
