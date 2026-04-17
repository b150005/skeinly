package io.github.b150005.knitnote.di

import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.b150005.knitnote.data.remote.ConnectivityMonitor
import io.github.b150005.knitnote.db.DriverFactory
import org.koin.dsl.module

val platformModule =
    module {
        single { DriverFactory(get()) }
        single { ConnectivityMonitor(get()) }
        // knit_note_prefs is unencrypted — suitable for non-sensitive UX flags only.
        // Use EncryptedSharedPreferences for auth tokens and user PII.
        single<Settings> { SharedPreferencesSettings.Factory(get()).create("knit_note_prefs") }
    }
