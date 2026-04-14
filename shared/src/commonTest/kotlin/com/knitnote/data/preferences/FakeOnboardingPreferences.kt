package com.knitnote.data.preferences

/**
 * In-memory fake for testing onboarding preferences.
 */
class FakeOnboardingPreferences : OnboardingPreferences {
    private var _hasSeenOnboarding = false

    override val hasSeenOnboarding: Boolean
        get() = _hasSeenOnboarding

    override fun markOnboardingComplete() {
        _hasSeenOnboarding = true
    }
}
