package com.knitnote.data.preferences

import com.russhwolf.settings.Settings

/**
 * Abstraction for onboarding-related preferences.
 * Backed by [Settings] (SharedPreferences on Android, NSUserDefaults on iOS).
 */
interface OnboardingPreferences {
    val hasSeenOnboarding: Boolean

    fun markOnboardingComplete()
}

internal class OnboardingPreferencesImpl(
    private val settings: Settings,
) : OnboardingPreferences {
    override val hasSeenOnboarding: Boolean
        get() = settings.getBoolean(KEY_HAS_SEEN_ONBOARDING, false)

    override fun markOnboardingComplete() {
        settings.putBoolean(KEY_HAS_SEEN_ONBOARDING, true)
    }

    private companion object {
        const val KEY_HAS_SEEN_ONBOARDING = "has_seen_onboarding"
    }
}
