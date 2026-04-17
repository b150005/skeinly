package io.github.b150005.knitnote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.usecase.DeleteAccountUseCase
import io.github.b150005.knitnote.domain.usecase.ObserveAuthStateUseCase
import io.github.b150005.knitnote.domain.usecase.SignOutUseCase
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

data class SettingsState(
    val email: String? = null,
    val isLoading: Boolean = true,
    val isDeletingAccount: Boolean = false,
    val error: String? = null,
)

sealed interface SettingsEvent {
    data object SignOut : SettingsEvent

    data object DeleteAccountConfirmed : SettingsEvent

    data object ClearError : SettingsEvent
}

class SettingsViewModel(
    private val observeAuthState: ObserveAuthStateUseCase,
    private val signOut: SignOutUseCase,
    private val deleteAccount: DeleteAccountUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _accountDeletedChannel = Channel<Unit>(Channel.BUFFERED)
    val accountDeleted: Flow<Unit> = _accountDeletedChannel.receiveAsFlow()

    init {
        loadSettings()
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            SettingsEvent.SignOut -> performSignOut()
            SettingsEvent.DeleteAccountConfirmed -> performDeleteAccount()
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
                    _state.update { it.copy(error = result.error.toMessage()) }
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
                        it.copy(isDeletingAccount = false, error = result.error.toMessage())
                    }
            }
        }
    }
}
