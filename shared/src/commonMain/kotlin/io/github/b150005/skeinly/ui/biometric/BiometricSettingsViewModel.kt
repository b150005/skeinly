package io.github.b150005.skeinly.ui.biometric

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.biometric.BiometricAvailability
import io.github.b150005.skeinly.data.preferences.BiometricPreferences
import io.github.b150005.skeinly.data.preferences.ThresholdChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 26.6 (ADR-022 §6.5) — drives the BiometricSettingsScreen.
 *
 * State carries:
 *  - [BiometricSettingsState.enabled] — current opt-in preference.
 *  - [BiometricSettingsState.threshold] — current re-auth threshold
 *    bucket.
 *  - [BiometricSettingsState.availability] — read from the
 *    [BiometricAvailability] supplier at init time. The Settings UI
 *    surfaces the OS availability so a user on a device without
 *    biometric / PIN can see why the toggle is disabled.
 *
 * Lambda-seam DI: [queryAvailability] is a `() -> BiometricAvailability`
 * supplier (NOT the `expect class` directly). The `expect class`
 * surfaces as a `final class` to commonTest which cannot be subclassed
 * for fakes — same DI pattern as
 * [io.github.b150005.skeinly.ui.notifications.NotificationPermissionViewModel]
 * (Phase 24.2c-1).
 */
data class BiometricSettingsState(
    val enabled: Boolean = false,
    val threshold: ThresholdChoice = ThresholdChoice.FiveMinutes,
    val availability: BiometricAvailability = BiometricAvailability.Available,
) {
    /** True if the OS can satisfy a prompt (or PIN fallback). When
     *  false, the toggle row should disable + the unavailable status
     *  copy renders below. */
    val canToggle: Boolean get() = availability == BiometricAvailability.Available
}

sealed interface BiometricSettingsEvent {
    data class ToggleEnabled(
        val value: Boolean,
    ) : BiometricSettingsEvent

    data class SelectThreshold(
        val choice: ThresholdChoice,
    ) : BiometricSettingsEvent

    /**
     * Phase 26.7 (Tech Debt carryover from Phase 26.6) — re-query OS
     * availability when the screen re-appears. Required because the
     * user can deep-link to OS Settings via the "Open settings" affordance
     * (or simply background the app, enroll a biometric / PIN, and come
     * back), and the screen needs to reflect the new availability state
     * without requiring a process restart.
     *
     * Compose collects `LaunchedEffect(Unit)` to fire this on every
     * screen mount; iOS routes through `.onAppear`. Idempotent — calling
     * it on an unchanged availability emits the same state copy.
     */
    data object RefreshAvailability : BiometricSettingsEvent
}

class BiometricSettingsViewModel(
    private val preferences: BiometricPreferences,
    private val queryAvailability: () -> BiometricAvailability,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            BiometricSettingsState(
                enabled = preferences.biometricEnabled.value,
                threshold = ThresholdChoice.fromSeconds(preferences.reauthThresholdSeconds.value),
                availability = queryAvailability(),
            ),
        )
    val state: StateFlow<BiometricSettingsState> = _state.asStateFlow()

    init {
        observePreferences()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferences.biometricEnabled
                .combine(preferences.reauthThresholdSeconds) { enabled, thresholdSec ->
                    enabled to ThresholdChoice.fromSeconds(thresholdSec)
                }.collect { (enabled, threshold) ->
                    _state.update { it.copy(enabled = enabled, threshold = threshold) }
                }
        }
    }

    fun onEvent(event: BiometricSettingsEvent) {
        when (event) {
            is BiometricSettingsEvent.ToggleEnabled -> {
                // Re-query availability at toggle time — the user may
                // have walked through OS Settings to enroll biometric
                // since the screen mounted. Disallow turning ON when
                // the OS can't satisfy a prompt; pass through OFF
                // regardless (the user may want to flip the switch off
                // even on an unavailable device).
                if (event.value) {
                    val current = queryAvailability()
                    _state.update { it.copy(availability = current) }
                    if (current != BiometricAvailability.Available) return
                }
                preferences.setBiometricEnabled(event.value)
            }
            is BiometricSettingsEvent.SelectThreshold ->
                preferences.setReauthThresholdSeconds(event.choice.seconds)
            BiometricSettingsEvent.RefreshAvailability -> {
                val current = queryAvailability()
                _state.update { it.copy(availability = current) }
            }
        }
    }
}
