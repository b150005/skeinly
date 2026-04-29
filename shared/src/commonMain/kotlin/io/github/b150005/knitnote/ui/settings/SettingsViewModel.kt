package io.github.b150005.knitnote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.data.preferences.AnalyticsPreferences
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.usecase.DeleteAccountUseCase
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.ObserveAuthStateUseCase
import io.github.b150005.knitnote.domain.usecase.SignOutUseCase
import io.github.b150005.knitnote.domain.usecase.UpdateEmailUseCase
import io.github.b150005.knitnote.domain.usecase.UpdatePasswordUseCase
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

data class SettingsState(
    val email: String? = null,
    val isLoading: Boolean = true,
    val isDeletingAccount: Boolean = false,
    val isChangingPassword: Boolean = false,
    val isChangingEmail: Boolean = false,
    val pendingChangePasswordDialog: Boolean = false,
    val pendingChangeEmailDialog: Boolean = false,
    val analyticsOptIn: Boolean = false,
    val error: ErrorMessage? = null,
)

sealed interface SettingsEvent {
    data object SignOut : SettingsEvent

    data object DeleteAccountConfirmed : SettingsEvent

    data object RequestChangePassword : SettingsEvent

    data object DismissChangePassword : SettingsEvent

    data class ConfirmChangePassword(
        val newPassword: String,
    ) : SettingsEvent

    data object RequestChangeEmail : SettingsEvent

    data object DismissChangeEmail : SettingsEvent

    data class ConfirmChangeEmail(
        val newEmail: String,
    ) : SettingsEvent

    data class SetAnalyticsOptIn(
        val value: Boolean,
    ) : SettingsEvent

    data object ClearError : SettingsEvent
}

/**
 * One-shot toast events surfaced to the screen via a [Channel]. Captured
 * with sealed type so future toast types (e.g., "email_confirmed") can
 * extend without changing the ViewModel API surface.
 */
sealed interface SettingsToastEvent {
    data object PasswordChanged : SettingsToastEvent

    data object EmailChangePending : SettingsToastEvent
}

class SettingsViewModel(
    private val observeAuthState: ObserveAuthStateUseCase,
    private val signOut: SignOutUseCase,
    private val deleteAccount: DeleteAccountUseCase,
    private val updatePassword: UpdatePasswordUseCase,
    private val updateEmail: UpdateEmailUseCase,
    private val analyticsPreferences: AnalyticsPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _accountDeletedChannel = Channel<Unit>(Channel.BUFFERED)
    val accountDeleted: Flow<Unit> = _accountDeletedChannel.receiveAsFlow()

    private val _toastChannel = Channel<SettingsToastEvent>(Channel.BUFFERED)
    val toastEvents: Flow<SettingsToastEvent> = _toastChannel.receiveAsFlow()

    init {
        loadSettings()
        observeAnalyticsOptIn()
    }

    private fun observeAnalyticsOptIn() {
        viewModelScope.launch {
            analyticsPreferences.analyticsOptIn.collect { value ->
                _state.update { it.copy(analyticsOptIn = value) }
            }
        }
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            SettingsEvent.SignOut -> performSignOut()
            SettingsEvent.DeleteAccountConfirmed -> performDeleteAccount()
            SettingsEvent.RequestChangePassword ->
                _state.update { it.copy(pendingChangePasswordDialog = true) }
            SettingsEvent.DismissChangePassword ->
                _state.update { it.copy(pendingChangePasswordDialog = false) }
            is SettingsEvent.ConfirmChangePassword -> performChangePassword(event.newPassword)
            SettingsEvent.RequestChangeEmail ->
                _state.update { it.copy(pendingChangeEmailDialog = true) }
            SettingsEvent.DismissChangeEmail ->
                _state.update { it.copy(pendingChangeEmailDialog = false) }
            is SettingsEvent.ConfirmChangeEmail -> performChangeEmail(event.newEmail)
            is SettingsEvent.SetAnalyticsOptIn ->
                analyticsPreferences.setAnalyticsOptIn(event.value)
            SettingsEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            observeAuthState()
                .collect { authState ->
                    when (authState) {
                        is AuthState.Authenticated ->
                            _state.update {
                                it.copy(email = authState.email, isLoading = false)
                            }
                        else ->
                            _state.update { it.copy(isLoading = false) }
                    }
                }
        }
    }

    private fun performSignOut() {
        viewModelScope.launch {
            when (val result = signOut()) {
                is UseCaseResult.Success -> { /* Auth state observer handles navigation */ }
                is UseCaseResult.Failure ->
                    _state.update { it.copy(error = result.error.toErrorMessage()) }
            }
        }
    }

    private fun performDeleteAccount() {
        viewModelScope.launch {
            _state.update { it.copy(isDeletingAccount = true) }
            when (val result = deleteAccount()) {
                is UseCaseResult.Success -> {
                    _state.update { it.copy(isDeletingAccount = false) }
                    _accountDeletedChannel.send(Unit)
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(isDeletingAccount = false, error = result.error.toErrorMessage())
                    }
            }
        }
    }

    private fun performChangePassword(newPassword: String) {
        viewModelScope.launch {
            _state.update { it.copy(isChangingPassword = true) }
            when (val result = updatePassword(newPassword)) {
                is UseCaseResult.Success -> {
                    _state.update {
                        it.copy(
                            isChangingPassword = false,
                            pendingChangePasswordDialog = false,
                        )
                    }
                    _toastChannel.send(SettingsToastEvent.PasswordChanged)
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(
                            isChangingPassword = false,
                            error = result.error.toErrorMessage(),
                        )
                    }
            }
        }
    }

    private fun performChangeEmail(newEmail: String) {
        viewModelScope.launch {
            _state.update { it.copy(isChangingEmail = true) }
            when (val result = updateEmail(newEmail)) {
                is UseCaseResult.Success -> {
                    _state.update {
                        it.copy(
                            isChangingEmail = false,
                            pendingChangeEmailDialog = false,
                        )
                    }
                    _toastChannel.send(SettingsToastEvent.EmailChangePending)
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(
                            isChangingEmail = false,
                            error = result.error.toErrorMessage(),
                        )
                    }
            }
        }
    }
}
