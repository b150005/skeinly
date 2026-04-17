package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.data.preferences.FakeOnboardingPreferences
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompleteOnboardingUseCaseTest {
    private val preferences = FakeOnboardingPreferences()
    private val useCase = CompleteOnboardingUseCase(preferences)

    @Test
    fun `marks onboarding as completed`() {
        assertFalse(preferences.hasSeenOnboarding)
        useCase()
        assertTrue(preferences.hasSeenOnboarding)
    }

    @Test
    fun `calling multiple times is idempotent`() {
        useCase()
        useCase()
        assertTrue(preferences.hasSeenOnboarding)
    }
}
