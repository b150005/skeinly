package io.github.b150005.knitnote.di

import io.github.b150005.knitnote.data.preferences.AnalyticsPreferences
import io.github.b150005.knitnote.data.preferences.AnalyticsPreferencesImpl
import io.github.b150005.knitnote.data.preferences.OnboardingPreferences
import io.github.b150005.knitnote.data.preferences.OnboardingPreferencesImpl
import org.koin.dsl.module

val preferencesModule =
    module {
        single<OnboardingPreferences> { OnboardingPreferencesImpl(get()) }
        single<AnalyticsPreferences> { AnalyticsPreferencesImpl(get()) }
    }
