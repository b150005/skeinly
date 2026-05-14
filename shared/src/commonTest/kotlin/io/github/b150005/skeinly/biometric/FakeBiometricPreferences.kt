package io.github.b150005.skeinly.biometric

import io.github.b150005.skeinly.data.preferences.BiometricPreferences
import io.github.b150005.skeinly.data.preferences.ThresholdChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [BiometricPreferences] for tests. Initial values set via
 * constructor; flips via `setBiometricEnabled` / `setReauthThresholdSeconds`
 * immediately reflect in the StateFlows.
 */
class FakeBiometricPreferences(
    initialEnabled: Boolean = false,
    initialThresholdSeconds: Long = ThresholdChoice.FiveMinutes.seconds,
) : BiometricPreferences {
    private val _enabled = MutableStateFlow(initialEnabled)
    override val biometricEnabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _thresholdSeconds = MutableStateFlow(initialThresholdSeconds)
    override val reauthThresholdSeconds: StateFlow<Long> = _thresholdSeconds.asStateFlow()

    override fun setBiometricEnabled(value: Boolean) {
        _enabled.value = value
    }

    override fun setReauthThresholdSeconds(value: Long) {
        _thresholdSeconds.value = ThresholdChoice.fromSeconds(value).seconds
    }
}
