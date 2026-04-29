package io.github.b150005.knitnote.data.preferences

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase F2: PostHog analytics opt-in preference.
 *
 * Default is OFF (opt-in, not opt-out) per Phase 27a no-tracking stance.
 * The Settings screen surfaces a toggle that flips this; PostHog SDK
 * init is gated on the resulting flag (see KnitNoteApplication.kt /
 * iOSApp.swift). When the user toggles ON mid-session, the SDK is
 * initialized lazily; toggling OFF mid-session calls PostHog's optOut
 * to suspend further capture without losing the existing session.
 */
interface AnalyticsPreferences {
    /** Reactive state — UI binds to this so the Settings toggle reflects truth. */
    val analyticsOptIn: StateFlow<Boolean>

    fun setAnalyticsOptIn(value: Boolean)
}

internal class AnalyticsPreferencesImpl(
    private val settings: Settings,
) : AnalyticsPreferences {
    private val _analyticsOptIn =
        MutableStateFlow(settings.getBoolean(KEY_ANALYTICS_OPT_IN, false))
    override val analyticsOptIn: StateFlow<Boolean> = _analyticsOptIn.asStateFlow()

    override fun setAnalyticsOptIn(value: Boolean) {
        settings.putBoolean(KEY_ANALYTICS_OPT_IN, value)
        _analyticsOptIn.value = value
    }

    private companion object {
        const val KEY_ANALYTICS_OPT_IN = "analytics_opt_in"
    }
}
