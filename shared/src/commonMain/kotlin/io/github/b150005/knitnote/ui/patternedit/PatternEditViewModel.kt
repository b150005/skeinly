package io.github.b150005.knitnote.ui.patternedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.repository.PatternRepository
import io.github.b150005.knitnote.domain.usecase.CreatePatternUseCase
import io.github.b150005.knitnote.domain.usecase.UpdatePatternUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PatternEditState(
    val patternId: String? = null,
    val title: String = "",
    val description: String = "",
    val difficulty: Difficulty? = null,
    val gauge: String = "",
    val yarnInfo: String = "",
    val needleSize: String = "",
    val visibility: Visibility = Visibility.PRIVATE,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

sealed interface PatternEditEvent {
    data class UpdateTitle(
        val title: String,
    ) : PatternEditEvent

    data class UpdateDescription(
        val description: String,
    ) : PatternEditEvent

    data class UpdateDifficulty(
        val difficulty: Difficulty?,
    ) : PatternEditEvent

    data class UpdateGauge(
        val gauge: String,
    ) : PatternEditEvent

    data class UpdateYarnInfo(
        val yarnInfo: String,
    ) : PatternEditEvent

    data class UpdateNeedleSize(
        val needleSize: String,
    ) : PatternEditEvent

    data class UpdateVisibility(
        val visibility: Visibility,
    ) : PatternEditEvent

    data object Save : PatternEditEvent

    data object ClearError : PatternEditEvent
}

class PatternEditViewModel(
    private val patternId: String?,
    private val patternRepository: PatternRepository,
    private val createPattern: CreatePatternUseCase,
    private val updatePattern: UpdatePatternUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(PatternEditState(patternId = patternId))
    val state: StateFlow<PatternEditState> = _state.asStateFlow()

    private val _saveSuccessChannel = Channel<Unit>(Channel.BUFFERED)
    val saveSuccess: Flow<Unit> = _saveSuccessChannel.receiveAsFlow()

    init {
        if (patternId != null) {
            loadPattern(patternId)
        }
    }

    private fun loadPattern(id: String) {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val pattern = patternRepository.getById(id)
            if (pattern != null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        title = pattern.title,
                        description = pattern.description.orEmpty(),
                        difficulty = pattern.difficulty,
                        gauge = pattern.gauge.orEmpty(),
                        yarnInfo = pattern.yarnInfo.orEmpty(),
                        needleSize = pattern.needleSize.orEmpty(),
                        visibility = pattern.visibility,
                    )
                }
            } else {
                _state.update {
                    it.copy(isLoading = false, error = "Pattern not found")
                }
            }
        }
    }

    fun onEvent(event: PatternEditEvent) {
        when (event) {
            is PatternEditEvent.UpdateTitle -> _state.update { it.copy(title = event.title) }
            is PatternEditEvent.UpdateDescription -> _state.update { it.copy(description = event.description) }
            is PatternEditEvent.UpdateDifficulty -> _state.update { it.copy(difficulty = event.difficulty) }
            is PatternEditEvent.UpdateGauge -> _state.update { it.copy(gauge = event.gauge) }
            is PatternEditEvent.UpdateYarnInfo -> _state.update { it.copy(yarnInfo = event.yarnInfo) }
            is PatternEditEvent.UpdateNeedleSize -> _state.update { it.copy(needleSize = event.needleSize) }
            is PatternEditEvent.UpdateVisibility -> _state.update { it.copy(visibility = event.visibility) }
            PatternEditEvent.Save -> save()
            PatternEditEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun save() {
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val current = _state.value
            val result =
                if (patternId != null) {
                    updatePattern(
                        patternId = patternId,
                        title = current.title,
                        description = current.description,
                        difficulty = current.difficulty,
                        gauge = current.gauge,
                        yarnInfo = current.yarnInfo,
                        needleSize = current.needleSize,
                        visibility = current.visibility,
                    )
                } else {
                    createPattern(
                        title = current.title,
                        description = current.description,
                        difficulty = current.difficulty,
                        gauge = current.gauge,
                        yarnInfo = current.yarnInfo,
                        needleSize = current.needleSize,
                        visibility = current.visibility,
                    )
                }

            when (result) {
                is UseCaseResult.Success -> {
                    _state.update { it.copy(isSaving = false) }
                    _saveSuccessChannel.send(Unit)
                }
                is UseCaseResult.Failure -> {
                    _state.update { it.copy(isSaving = false, error = result.error.toMessage()) }
                }
            }
        }
    }
}
