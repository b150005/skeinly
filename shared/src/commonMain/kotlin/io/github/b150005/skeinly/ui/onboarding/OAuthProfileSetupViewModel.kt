package io.github.b150005.skeinly.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.domain.model.OAuthOnboardingMetadata
import io.github.b150005.skeinly.domain.model.User
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toErrorMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 26.6 (ADR-022 §6.6) — backing state for the post-OAuth profile
 * setup gate.
 *
 * Visibility rule: the NavGraph mounts this screen only when the
 * AuthRepository surfaces non-empty [OAuthOnboardingMetadata.displayName]
 * (Apple / Google) AND the current profile row's display_name is empty
 * AND the one-time `OAuthProfileSetupPreferences.isCompleted` flag is
 * false. The view never has to handle the "user has nothing to set up"
 * branch — it always starts with a pre-fill seed.
 */
data class OAuthProfileSetupState(
    /** User-editable name field. Seeded from [OAuthOnboardingMetadata.displayName]. */
    val displayName: String = "",
    /** Optional avatar URL discovered from OAuth (Google `picture`). Null = no
     *  picture surface; the import tile is hidden. */
    val pictureUrl: String? = null,
    /** True while [SubmitEvent] is in flight — name save + optional avatar import. */
    val isSubmitting: Boolean = false,
    /** True while the OAuth avatar import is fetching + uploading. Distinct
     *  from [isSubmitting] so the avatar tile can render its own progress
     *  indicator without dimming the rest of the screen. */
    val isImportingAvatar: Boolean = false,
    /** True once the user accepts the offered OAuth picture. Drives the
     *  "Use this picture" tile selected-state UI. False also covers the
     *  "no picture surface" path — the screen renders with avatar absent. */
    val avatarImported: Boolean = false,
    /** Surfaces user-visible errors from save / import paths. */
    val error: ErrorMessage? = null,
)

sealed interface OAuthProfileSetupEvent {
    data class UpdateDisplayName(
        val value: String,
    ) : OAuthProfileSetupEvent

    /** Tap on the offered OAuth picture tile. */
    data object UseOAuthAvatar : OAuthProfileSetupEvent

    /** Tap on "Choose different" — clears the imported flag. Phase 26.6
     *  does NOT ship in-screen photo picker integration; Settings →
     *  Profile remains the surface for arbitrary uploads. */
    data object ChooseDifferentAvatar : OAuthProfileSetupEvent

    /** Save tap — commits display name + currently-selected avatar. */
    data object Submit : OAuthProfileSetupEvent

    /** Skip tap — explicit user decision to defer. Marks the gate
     *  preference complete WITHOUT touching display_name. The user can
     *  still rename via Settings → Profile. */
    data object Skip : OAuthProfileSetupEvent

    data object ClearError : OAuthProfileSetupEvent
}

/**
 * One-shot navigation event surfaced once the gate is complete (either
 * Save succeeded OR Skip was tapped). The Compose screen collects this
 * to call `navController.navigate(ProjectList) { popUpTo this gate }`.
 */
sealed interface OAuthProfileSetupNavEvent {
    data object Completed : OAuthProfileSetupNavEvent
}

/**
 * Lambda-seam DI mirrors `BugReportPreviewViewModel` (Phase 39 W5b) +
 * `NotificationPermissionViewModel` (Phase 24.2). The production wiring
 * in `ViewModelModule` binds:
 *   - [importAvatar]   → `ImportOAuthAvatarUseCase::invoke`
 *   - [saveDisplayName] → wraps `UpdateProfileUseCase`
 *   - [markGateCompleted] → `OAuthProfileSetupPreferences::markCompleted`
 *
 * Tests instantiate with recording lambdas to verify call routing
 * without standing up Supabase / Ktor / Storage layers.
 */
class OAuthProfileSetupViewModel(
    private val metadata: OAuthOnboardingMetadata,
    private val importAvatar: suspend (pictureUrl: String) -> UseCaseResult<String>,
    private val saveDisplayName: suspend (displayName: String, avatarUrl: String?) -> UseCaseResult<User>,
    private val markGateCompleted: () -> Unit,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            OAuthProfileSetupState(
                displayName = metadata.displayName.orEmpty(),
                pictureUrl = metadata.pictureUrl,
                avatarImported = false,
            ),
        )
    val state: StateFlow<OAuthProfileSetupState> = _state.asStateFlow()

    private val _navChannel = Channel<OAuthProfileSetupNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<OAuthProfileSetupNavEvent> = _navChannel.receiveAsFlow()

    private var importedAvatarUrl: String? = null

    fun onEvent(event: OAuthProfileSetupEvent) {
        when (event) {
            is OAuthProfileSetupEvent.UpdateDisplayName ->
                _state.update { it.copy(displayName = event.value, error = null) }
            OAuthProfileSetupEvent.UseOAuthAvatar -> performImportAvatar()
            OAuthProfileSetupEvent.ChooseDifferentAvatar -> {
                importedAvatarUrl = null
                _state.update { it.copy(avatarImported = false, error = null) }
            }
            OAuthProfileSetupEvent.Submit -> performSubmit()
            OAuthProfileSetupEvent.Skip -> performSkip()
            OAuthProfileSetupEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun performImportAvatar() {
        // Guard against concurrent taps while a fetch is in flight.
        if (_state.value.isImportingAvatar) return
        val pictureUrl = _state.value.pictureUrl ?: return
        viewModelScope.launch {
            _state.update { it.copy(isImportingAvatar = true, error = null) }
            when (val result = importAvatar(pictureUrl)) {
                is UseCaseResult.Success -> {
                    importedAvatarUrl = result.value
                    _state.update {
                        it.copy(isImportingAvatar = false, avatarImported = true)
                    }
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(
                            isImportingAvatar = false,
                            error = result.error.toErrorMessage(),
                        )
                    }
            }
        }
    }

    private fun performSubmit() {
        if (_state.value.isSubmitting) return
        val trimmed = _state.value.displayName.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = ErrorMessage.FieldRequired) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true) }
            when (val result = saveDisplayName(trimmed, importedAvatarUrl)) {
                is UseCaseResult.Success -> {
                    markGateCompleted()
                    _state.update { it.copy(isSubmitting = false) }
                    _navChannel.send(OAuthProfileSetupNavEvent.Completed)
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            error = result.error.toErrorMessage(),
                        )
                    }
            }
        }
    }

    private fun performSkip() {
        markGateCompleted()
        viewModelScope.launch {
            _navChannel.send(OAuthProfileSetupNavEvent.Completed)
        }
    }
}
