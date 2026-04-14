package com.knitnote.di

import com.knitnote.data.preferences.OnboardingPreferences
import com.knitnote.data.preferences.OnboardingPreferencesImpl
import org.koin.dsl.module

val preferencesModule =
    module {
        single<OnboardingPreferences> { OnboardingPreferencesImpl(get()) }
    }
