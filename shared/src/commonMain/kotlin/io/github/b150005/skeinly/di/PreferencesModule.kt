package io.github.b150005.skeinly.di

import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.AnalyticsTrackerImpl
import io.github.b150005.skeinly.data.analytics.EventRingBuffer
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferences
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferencesImpl
import io.github.b150005.skeinly.data.preferences.OnboardingPreferences
import io.github.b150005.skeinly.data.preferences.OnboardingPreferencesImpl
import io.github.b150005.skeinly.notifications.NotificationPermissionPrompter
import io.github.b150005.skeinly.notifications.NotificationPermissionPrompterImpl
import org.koin.dsl.module

val preferencesModule =
    module {
        single<OnboardingPreferences> { OnboardingPreferencesImpl(get()) }
        single<AnalyticsPreferences> { AnalyticsPreferencesImpl(get()) }
        // Phase 24.2 (ADR-017 §3.6) — gates the in-app pre-permission
        // explainer. State is one bit (Settings-backed); the Trigger enum
        // reaches across screens (PR list / detail / comment-post).
        single<NotificationPermissionPrompter> { NotificationPermissionPrompterImpl(get()) }
        // Phase F.3 — single AnalyticsTracker for the process lifetime.
        // The internal SharedFlow is hot; collectors should be the
        // Application layer (Android: SkeinlyApplication, iOS: iOSApp).
        single<AnalyticsTracker> { AnalyticsTrackerImpl(get()) }
        // Phase 39.3 (ADR-015 §6) — bug-report event trail. Phase 39.5
        // attaches the snapshot to every report; Application layer must
        // call `.start(applicationScope)` exactly once at init time.
        single { EventRingBuffer(tracker = get()) }
    }
