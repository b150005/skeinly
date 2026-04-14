package com.knitnote.domain.usecase

import com.knitnote.data.preferences.FakeOnboardingPreferences
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GetOnboardingCompletedUseCaseTest {
    private val preferences = FakeOnboardingPreferences()
    private val useCase = GetOnboardingCompletedUseCase(preferences)

    @Test
    fun `returns false when onboarding has not been completed`() {
        assertFalse(useCase())
    }

    @Test
    fun `returns true after onboarding is completed`() {
        preferences.markOnboardingComplete()
        assertTrue(useCase())
    }
}
