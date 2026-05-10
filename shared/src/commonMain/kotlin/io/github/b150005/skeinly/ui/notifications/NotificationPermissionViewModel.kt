package io.github.b150005.skeinly.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.notifications.NotificationPermissionPrompter
import io.github.b150005.skeinly.notifications.NotificationPermissionStatus
import io.github.b150005.skeinly.notifications.NotificationPromptTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 24.2 (ADR-017 §3.6) — drives the in-app pre-permission explainer
 * + Settings row. Holds the OS-level [NotificationPermissionStatus] (re-
 * read on each TriggerEncountered + RefreshStatus event) + the explainer
 * visibility bit (the user-side decision: "are we showing the dialog?").
 *
 * The explainer surfaces only when BOTH:
 *   1. OS status is [NotificationPermissionStatus.NOT_DETERMINED] (we
 *      can still trigger the OS prompt — DENIED requires Settings deep-
 *      link instead).
 *   2. [NotificationPermissionPrompter.shouldPrompt] returns true (the
 *      user has not already responded to a prior trigger).
 *
 * Once the user taps Enable / Not now we record the response in the
 * prompter so subsequent triggers do not re-surface; the OS-permission
 * gate persists independently in the OS itself.
 */
data class NotificationPermissionState(
    val osStatus: NotificationPermissionStatus = NotificationPermissionStatus.NOT_DETERMINED,
    /** True while the in-app pre-permission explainer is on screen. */
    val isExplainerVisible: Boolean = false,
    /**
     * True from the moment [NotificationPermissionEvent.UserAcceptedExplainer]
     * fires until [NotificationPermissionStatus] resolves. Surfaced so the
     * UI can disable the Enable button + show a small spinner while the
     * OS prompt round-trip is in flight.
     */
    val isRequestingPermission: Boolean = false,
)

sealed interface NotificationPermissionEvent {
    /**
     * Fired by an entry-point screen when the user crossed the threshold
     * for that trigger (e.g. SuggestionListScreen Incoming with PRs).
     * The VM decides whether to surface the explainer based on OS state +
     * prompter state.
     */
    data class TriggerEncountered(
        val trigger: NotificationPromptTrigger,
    ) : NotificationPermissionEvent

    /** User tapped "Enable" on the in-app explainer. */
    data object UserAcceptedExplainer : NotificationPermissionEvent

    /** User tapped "Not now" on the in-app explainer. */
    data object UserDismissedExplainer : NotificationPermissionEvent

    /** User tapped the "Open Settings" CTA on the Settings row. */
    data object OpenOsSettingsRequested : NotificationPermissionEvent

    /**
     * Re-reads the OS permission state. Fired from `init` and on screen
     * resume so the Settings row stays accurate after the user toggles
     * permission in OS Settings while the app is backgrounded.
     */
    data object RefreshStatus : NotificationPermissionEvent
}

class NotificationPermissionViewModel(
    private val prompter: NotificationPermissionPrompter,
    private val queryPermissionStatus: suspend () -> NotificationPermissionStatus,
    private val requestPermission: suspend () -> NotificationPermissionStatus,
    private val registerForPushNotifications: suspend (locale: String) -> String?,
    private val openOsSettings: () -> Unit,
    private val locale: String = DEFAULT_LOCALE,
) : ViewModel() {
    private val _state = MutableStateFlow(NotificationPermissionState())
    val state: StateFlow<NotificationPermissionState> = _state.asStateFlow()

    init {
        refreshStatus()
    }

    fun onEvent(event: NotificationPermissionEvent) {
        when (event) {
            is NotificationPermissionEvent.TriggerEncountered -> handleTrigger(event.trigger)
            NotificationPermissionEvent.UserAcceptedExplainer -> handleUserAccepted()
            NotificationPermissionEvent.UserDismissedExplainer -> handleUserDismissed()
            NotificationPermissionEvent.OpenOsSettingsRequested -> handleOpenOsSettings()
            NotificationPermissionEvent.RefreshStatus -> refreshStatus()
        }
    }

    private fun handleTrigger(trigger: NotificationPromptTrigger) {
        viewModelScope.launch {
            val osStatus =
                try {
                    queryPermissionStatus()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // OS status read failure is non-fatal — we keep the
                    // current state and let the UI render the row as
                    // "Disabled". The next refresh will retry.
                    return@launch
                }
            _state.update { it.copy(osStatus = osStatus) }
            if (osStatus == NotificationPermissionStatus.NOT_DETERMINED &&
                prompter.shouldPrompt(trigger)
            ) {
                _state.update { it.copy(isExplainerVisible = true) }
            }
        }
    }

    private fun handleUserAccepted() {
        prompter.recordPermissionAsked()
        _state.update { it.copy(isExplainerVisible = false, isRequestingPermission = true) }
        viewModelScope.launch {
            val newStatus =
                try {
                    requestPermission()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    NotificationPermissionStatus.NOT_DETERMINED
                }
            _state.update {
                it.copy(
                    osStatus = newStatus,
                    isRequestingPermission = false,
                )
            }
            if (newStatus == NotificationPermissionStatus.GRANTED) {
                try {
                    registerForPushNotifications(locale)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Token acquisition failure is non-fatal at the UI
                    // layer; the OS will retry independently and a future
                    // app foreground will pick up the rotated token.
                }
            }
        }
    }

    private fun handleUserDismissed() {
        // ADR §3.6 reads "Not now" as a global decision — the trigger
        // arg is informational only. PR_LIST_INCOMING_WITH_PRS is the
        // first-encounter trigger by convention, used as the audit-trail
        // anchor; the underlying state is still global.
        prompter.recordInAppDismiss(NotificationPromptTrigger.PR_LIST_INCOMING_WITH_PRS)
        _state.update { it.copy(isExplainerVisible = false) }
    }

    private fun handleOpenOsSettings() {
        openOsSettings()
    }

    private fun refreshStatus() {
        viewModelScope.launch {
            val osStatus =
                try {
                    queryPermissionStatus()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch
                }
            _state.update { it.copy(osStatus = osStatus) }
        }
    }

    private companion object {
        /**
         * Phase 24.2c placeholder. 24.2d threads the real
         * [io.github.b150005.skeinly.platform.DeviceContext] locale
         * (BCP-47, normalized from `NSLocale.currentLocale.localeIdentifier`
         * on iOS / `Locale.getDefault().toLanguageTag()` on Android).
         */
        const val DEFAULT_LOCALE = "en-US"
    }
}
