package io.github.b150005.skeinly.di

import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.AnalyticsTrackerImpl
import io.github.b150005.skeinly.data.analytics.EventRingBuffer
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferences
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferencesImpl
import io.github.b150005.skeinly.data.preferences.AppConfigPreferences
import io.github.b150005.skeinly.data.preferences.AppConfigPreferencesImpl
import io.github.b150005.skeinly.data.preferences.BiometricPreferences
import io.github.b150005.skeinly.data.preferences.BiometricPreferencesImpl
import io.github.b150005.skeinly.data.preferences.OnboardingPreferences
import io.github.b150005.skeinly.data.preferences.OnboardingPreferencesImpl
import io.github.b150005.skeinly.notifications.NotificationPermissionPrompter
import io.github.b150005.skeinly.notifications.NotificationPermissionPrompterImpl
import org.koin.dsl.module

val preferencesModule =
    module {
        single<OnboardingPreferences> { OnboardingPreferencesImpl(get()) }
        single<AnalyticsPreferences> { AnalyticsPreferencesImpl(get()) }
        // Phase 39 (W4 / 2026-05-11) — force-update gate cache. Persists
        // the last successfully fetched `app_config` row so the gate has
        // a value to evaluate against on offline launches. Settings-backed
        // (SharedPreferences / NSUserDefaults).
        single<AppConfigPreferences> { AppConfigPreferencesImpl(get()) }
        // Phase 26.6 (ADR-022 §6.5) — biometric re-auth preferences.
        // Settings-backed (skeinly_prefs, non-encrypted) because the
        // values are UX state (opt-in flag + threshold seconds) rather
        // than credentials. The biometric template itself never enters
        // the app — see BiometricAuthenticator KDoc.
        single<BiometricPreferences> { BiometricPreferencesImpl(get()) }
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
