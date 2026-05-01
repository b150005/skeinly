package io.github.b150005.skeinly.di

import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.b150005.skeinly.data.remote.ConnectivityMonitor
import io.github.b150005.skeinly.db.DriverFactory
import io.github.b150005.skeinly.platform.BugSubmissionLauncher
import io.github.b150005.skeinly.platform.DeviceContextProvider
import org.koin.dsl.module

val platformModule =
    module {
        single { DriverFactory(get()) }
        single { ConnectivityMonitor(get()) }
        // skeinly_prefs is unencrypted — suitable for non-sensitive UX flags only.
        // Use EncryptedSharedPreferences for auth tokens and user PII.
        single<Settings> { SharedPreferencesSettings.Factory(get()).create("skeinly_prefs") }
        // Phase 39.5 (ADR-015 §3) — bug-report system browser launcher
        // and read-only device context provider. Both reach for the
        // application Context via Koin's existing `androidContext()`
        // wiring, so no additional setup is needed at the Application
        // layer beyond the modules being included.
        single { BugSubmissionLauncher(get()) }
        single { DeviceContextProvider(get()) }
    }
