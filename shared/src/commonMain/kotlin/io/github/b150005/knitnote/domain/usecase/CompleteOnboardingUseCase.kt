package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.data.preferences.OnboardingPreferences

/**
 * Marks onboarding as completed so it is not shown again.
 * Synchronous, infallible — no [UseCaseResult] needed.
 */
class CompleteOnboardingUseCase(
    private val preferences: OnboardingPreferences,
) {
    operator fun invoke() {
        preferences.markOnboardingComplete()
    }
}
