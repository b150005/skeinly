package com.knitnote.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knitnote.domain.model.User
import com.knitnote.domain.usecase.GetCurrentUserUseCase
import com.knitnote.domain.usecase.UpdateProfileUseCase
import com.knitnote.domain.usecase.UseCaseError
import com.knitnote.domain.usecase.UseCaseResult
import com.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileState(
    val user: User? = null,
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val editDisplayName: String = "",
    val editBio: String = "",
    val error: String? = null,
)

sealed interface ProfileEvent {
    data object LoadProfile : ProfileEvent
    data object StartEditing : ProfileEvent
    data object CancelEditing : ProfileEvent
    data object SaveProfile : ProfileEvent
    data class UpdateDisplayName(val value: String) : ProfileEvent
    data class UpdateBio(val value: String) : ProfileEvent
    data object ClearError : ProfileEvent
}

class ProfileViewModel(
    private val getCurrentUser: GetCurrentUserUseCase,
    private val updateProfile: UpdateProfileUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private val _saveSuccessChannel = Channel<Unit>(Channel.BUFFERED)
    val saveSuccess: Flow<Unit> = _saveSuccessChannel.receiveAsFlow()

    init {
        loadProfile()
    }

    fun onEvent(event: ProfileEvent) {
        when (event) {
            ProfileEvent.LoadProfile -> loadProfile()
            ProfileEvent.StartEditing -> startEditing()
            ProfileEvent.CancelEditing -> cancelEditing()
            ProfileEvent.SaveProfile -> saveProfile()
            is ProfileEvent.UpdateDisplayName -> _state.update { it.copy(editDisplayName = event.value) }
            is ProfileEvent.UpdateBio -> _state.update { it.copy(editBio = event.value) }
            ProfileEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            when (val result = getCurrentUser()) {
                is UseCaseResult.Success -> _state.update {
                    it.copy(user = result.value, isLoading = false)
                }
                is UseCaseResult.Failure -> _state.update {
                    it.copy(isLoading = false, error = result.error.toMessage())
                }
            }
        }
    }

    private fun startEditing() {
        val user = _state.value.user ?: return
        _state.update {
            it.copy(
                isEditing = true,
                editDisplayName = user.displayName,
                editBio = user.bio ?: "",
            )
        }
    }

    private fun cancelEditing() {
        _state.update { it.copy(isEditing = false) }
    }

    private fun saveProfile() {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val currentState = _state.value
            when (val result = updateProfile(
                displayName = currentState.editDisplayName,
                bio = currentState.editBio.takeIf { it.isNotBlank() },
                avatarUrl = currentState.user?.avatarUrl,
            )) {
                is UseCaseResult.Success -> {
                    _state.update {
                        it.copy(
                            user = result.value,
                            isEditing = false,
                            isSaving = false,
                        )
                    }
                    _saveSuccessChannel.send(Unit)
                }
                is UseCaseResult.Failure -> _state.update {
                    it.copy(isSaving = false, error = result.error.toMessage())
                }
            }
        }
    }
}
