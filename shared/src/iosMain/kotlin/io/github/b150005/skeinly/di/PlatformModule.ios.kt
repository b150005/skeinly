package io.github.b150005.skeinly.di

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import io.github.b150005.skeinly.data.remote.ConnectivityMonitor
import io.github.b150005.skeinly.db.DriverFactory
import io.github.b150005.skeinly.notifications.OsSettingsLauncher
import io.github.b150005.skeinly.notifications.PushTokenRegistrar
import io.github.b150005.skeinly.platform.BugSubmissionLauncher
import io.github.b150005.skeinly.platform.DeviceContextProvider
import org.koin.dsl.module

val platformModule =
    module {
        single { DriverFactory() }
        single { ConnectivityMonitor() }
        // skeinly_prefs is unencrypted NSUserDefaults — suitable for non-sensitive UX flags only.
        // Use Keychain for auth tokens and user PII.
        single<Settings> { NSUserDefaultsSettings.Factory().create("skeinly_prefs") }
        // Phase 39.5 (ADR-015 §3) — bug-report URL launcher + device
        // context. iOS actuals do not depend on `Context` so they're
        // parameterless `single { ... }`.
        single { BugSubmissionLauncher() }
        single { DeviceContextProvider() }
        // Phase 24.2b (ADR-017 §3.6) — push-token + permission registrar.
        // 24.2b ships a no-op stub; 24.2d swaps in UNUserNotificationCenter +
        // registerForRemoteNotifications wiring behind the same expect/actual
        // surface.
        single { PushTokenRegistrar() }
        // Phase 24.2c (ADR-017 §3.6) — opens the OS app-notification
        // Settings page so a denied user can re-enable.
        single { OsSettingsLauncher() }
    }
