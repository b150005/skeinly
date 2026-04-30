package io.github.b150005.skeinly.di

import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.AnalyticsTrackerImpl
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferences
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferencesImpl
import io.github.b150005.skeinly.data.preferences.OnboardingPreferences
import io.github.b150005.skeinly.data.preferences.OnboardingPreferencesImpl
import org.koin.dsl.module

val preferencesModule =
    module {
        single<OnboardingPreferences> { OnboardingPreferencesImpl(get()) }
        single<AnalyticsPreferences> { AnalyticsPreferencesImpl(get()) }
        // Phase F.3 — single AnalyticsTracker for the process lifetime.
        // The internal SharedFlow is hot; collectors should be the
        // Application layer (Android: SkeinlyApplication, iOS: iOSApp).
        single<AnalyticsTracker> { AnalyticsTrackerImpl(get()) }
    }
