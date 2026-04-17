package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.data.preferences.OnboardingPreferences

/**
 * Returns whether the user has completed onboarding.
 * Synchronous, infallible — no [UseCaseResult] needed.
 */
class GetOnboardingCompletedUseCase(
    private val preferences: OnboardingPreferences,
) {
    operator fun invoke(): Boolean = preferences.hasSeenOnboarding
}
