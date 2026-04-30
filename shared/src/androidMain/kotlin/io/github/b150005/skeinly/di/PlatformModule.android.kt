package io.github.b150005.skeinly.di

import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.b150005.skeinly.data.remote.ConnectivityMonitor
import io.github.b150005.skeinly.db.DriverFactory
import org.koin.dsl.module

val platformModule =
    module {
        single { DriverFactory(get()) }
        single { ConnectivityMonitor(get()) }
        // skeinly_prefs is unencrypted — suitable for non-sensitive UX flags only.
        // Use EncryptedSharedPreferences for auth tokens and user PII.
        single<Settings> { SharedPreferencesSettings.Factory(get()).create("skeinly_prefs") }
    }
