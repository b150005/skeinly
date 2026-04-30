package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.data.preferences.OnboardingPreferences

/**
 * Returns whether the user has completed onboarding.
 * Synchronous, infallible — no [UseCaseResult] needed.
 */
class GetOnboardingCompletedUseCase(
    private val preferences: OnboardingPreferences,
) {
    operator fun invoke(): Boolean = preferences.hasSeenOnboarding
}
