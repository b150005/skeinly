package io.github.b150005.knitnote.di

import io.github.b150005.knitnote.data.analytics.AnalyticsTracker
import io.github.b150005.knitnote.data.analytics.AnalyticsTrackerImpl
import io.github.b150005.knitnote.data.preferences.AnalyticsPreferences
import io.github.b150005.knitnote.data.preferences.AnalyticsPreferencesImpl
import io.github.b150005.knitnote.data.preferences.OnboardingPreferences
import io.github.b150005.knitnote.data.preferences.OnboardingPreferencesImpl
import org.koin.dsl.module

val preferencesModule =
    module {
        single<OnboardingPreferences> { OnboardingPreferencesImpl(get()) }
        single<AnalyticsPreferences> { AnalyticsPreferencesImpl(get()) }
        // Phase F.3 — single AnalyticsTracker for the process lifetime.
        // The internal SharedFlow is hot; collectors should be the
        // Application layer (Android: KnitNoteApplication, iOS: iOSApp).
        single<AnalyticsTracker> { AnalyticsTrackerImpl(get()) }
    }
