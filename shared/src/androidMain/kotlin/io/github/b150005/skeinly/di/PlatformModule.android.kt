package io.github.b150005.skeinly.di

import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.b150005.skeinly.data.remote.ConnectivityMonitor
import io.github.b150005.skeinly.db.DriverFactory
import io.github.b150005.skeinly.notifications.OsSettingsLauncher
import io.github.b150005.skeinly.notifications.PushTokenRegistrar
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
        // Phase 24.2e (ADR-017 §3.5, §3.6) — push-token + permission
        // registrar. 24.2e swaps in FirebaseMessaging.getInstance().token
        // + Activity-scoped POST_NOTIFICATIONS launcher wiring + the
        // commonMain `DeviceTokenRepository` upsert path behind the same
        // expect/actual surface that 24.2b shipped as a stub.
        single { PushTokenRegistrar(get(), get()) }
        // Phase 24.2c (ADR-017 §3.6) — opens the OS app-notification
        // Settings page so a denied user can re-enable.
        single { OsSettingsLauncher(get()) }
    }
