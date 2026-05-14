package io.github.b150005.skeinly.data.preferences

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 26.6 (ADR-022 §6.5) — biometric re-auth preferences. Stores:
 *
 *  - `biometric_enabled` (Boolean, default `false`) — whether the user
 *    has opted into the re-auth-on-foreground gate from Settings.
 *    Sensitive-action gates (account deletion, MFA disable) are
 *    independent and fire regardless of this flag — they read the OS
 *    biometric availability directly via [BiometricAuthenticator] and
 *    prompt unconditionally when surfacing the destructive action.
 *  - `biometric_reauth_threshold_seconds` (Long, default `300` = 5 min)
 *    — duration since last background-transition that must elapse
 *    before the foreground-resume gate fires. Bounded to one of
 *    [ThresholdChoice].
 *
 * Backed by the non-encrypted `skeinly_prefs` `Settings` instance
 * because these values are UX state — not credentials — and live
 * alongside `analytics_opt_in` (precedent: [AnalyticsPreferences]).
 * The biometric *template* itself never enters the app — see
 * [BiometricAuthenticator] KDoc.
 */
interface BiometricPreferences {
    /** Reactive opt-in state — UI binds the toggle to this. */
    val biometricEnabled: StateFlow<Boolean>

    /** Reactive re-auth threshold (seconds since last background). */
    val reauthThresholdSeconds: StateFlow<Long>

    fun setBiometricEnabled(value: Boolean)

    fun setReauthThresholdSeconds(value: Long)
}

/** Bounded set of threshold choices surfaced in the BiometricSettings
 *  picker. Long values used directly for the underlying Settings write. */
enum class ThresholdChoice(
    val seconds: Long,
) {
    OneMinute(60L),
    FiveMinutes(300L),
    FifteenMinutes(900L),
    OneHour(3600L),
    ;

    companion object {
        /** Defensive coercion — any persisted value outside the bounded
         *  set collapses to [FiveMinutes] (the default).  */
        fun fromSeconds(value: Long): ThresholdChoice = entries.firstOrNull { it.seconds == value } ?: FiveMinutes
    }
}

internal class BiometricPreferencesImpl(
    private val settings: Settings,
) : BiometricPreferences {
    private val _biometricEnabled =
        MutableStateFlow(settings.getBoolean(KEY_ENABLED, false))
    override val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _reauthThresholdSeconds =
        MutableStateFlow(settings.getLong(KEY_THRESHOLD_SECONDS, ThresholdChoice.FiveMinutes.seconds))
    override val reauthThresholdSeconds: StateFlow<Long> = _reauthThresholdSeconds.asStateFlow()

    override fun setBiometricEnabled(value: Boolean) {
        settings.putBoolean(KEY_ENABLED, value)
        _biometricEnabled.value = value
    }

    override fun setReauthThresholdSeconds(value: Long) {
        // Defensive coercion at the write boundary — a future caller
        // passing an arbitrary Long (e.g. UI bug, deep-link param) can
        // never persist a value outside the bounded set.
        val coerced = ThresholdChoice.fromSeconds(value).seconds
        settings.putLong(KEY_THRESHOLD_SECONDS, coerced)
        _reauthThresholdSeconds.value = coerced
    }

    private companion object {
        const val KEY_ENABLED = "biometric_enabled"
        const val KEY_THRESHOLD_SECONDS = "biometric_reauth_threshold_seconds"
    }
}
