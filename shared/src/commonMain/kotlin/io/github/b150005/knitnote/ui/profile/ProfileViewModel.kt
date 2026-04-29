package io.github.b150005.knitnote.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.GetCurrentUserUseCase
import io.github.b150005.knitnote.domain.usecase.UpdateProfileUseCase
import io.github.b150005.knitnote.domain.usecase.UploadAvatarUseCase
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

data class ProfileState(
    val user: User? = null,
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val isUploadingAvatar: Boolean = false,
    val editDisplayName: String = "",
    val editBio: String = "",
    val error: ErrorMessage? = null,
)

sealed interface ProfileEvent {
    data object LoadProfile : ProfileEvent

    data object StartEditing : ProfileEvent

    data object CancelEditing : ProfileEvent

    data object SaveProfile : ProfileEvent

    data class UpdateDisplayName(
        val value: String,
    ) : ProfileEvent

    data class UpdateBio(
        val value: String,
    ) : ProfileEvent

    data class UploadAvatar(
        val imageData: ByteArray,
        val fileName: String,
    ) : ProfileEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is UploadAvatar && imageData.contentEquals(other.imageData) && fileName == other.fileName)

        override fun hashCode(): Int = 31 * imageData.contentHashCode() + fileName.hashCode()
    }

    data object ClearError : ProfileEvent
}

class ProfileViewModel(
    private val getCurrentUser: GetCurrentUserUseCase,
    private val updateProfile: UpdateProfileUseCase,
    private val uploadAvatar: UploadAvatarUseCase,
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
            is ProfileEvent.UploadAvatar -> performUploadAvatar(event.imageData, event.fileName)
            ProfileEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun performUploadAvatar(
        imageData: ByteArray,
        fileName: String,
    ) {
        val currentUser = _state.value.user ?: return
        viewModelScope.launch {
            _state.update { it.copy(isUploadingAvatar = true) }
            when (val uploadResult = uploadAvatar(imageData, fileName)) {
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(isUploadingAvatar = false, error = uploadResult.error.toErrorMessage())
                    }
                is UseCaseResult.Success -> {
                    // Patch the new avatar URL into the User row, preserving
                    // displayName + bio. UpdateProfileUseCase requires all-or-
                    // nothing because it routes through a single Supabase
                    // update — see UserRepository.updateProfile.
                    when (
                        val patchResult =
                            updateProfile(
                                displayName = currentUser.displayName,
                                bio = currentUser.bio,
                                avatarUrl = uploadResult.value,
                            )
                    ) {
                        is UseCaseResult.Success ->
                            _state.update {
                                it.copy(user = patchResult.value, isUploadingAvatar = false)
                            }
                        is UseCaseResult.Failure ->
                            _state.update {
                                it.copy(
                                    isUploadingAvatar = false,
                                    error = patchResult.error.toErrorMessage(),
                                )
                            }
                    }
                }
            }
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            when (val result = getCurrentUser()) {
                is UseCaseResult.Success ->
                    _state.update {
                        it.copy(user = result.value, isLoading = false)
                    }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(isLoading = false, error = result.error.toErrorMessage())
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
            when (
                val result =
                    updateProfile(
                        displayName = currentState.editDisplayName,
                        bio = currentState.editBio.takeIf { it.isNotBlank() },
                        avatarUrl = currentState.user?.avatarUrl,
                    )
            ) {
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
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(isSaving = false, error = result.error.toErrorMessage())
                    }
            }
        }
    }
}
