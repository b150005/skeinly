package com.knitnote.di

import com.knitnote.data.remote.ConnectivityMonitor
import com.knitnote.db.DriverFactory
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import org.koin.dsl.module

val platformModule =
    module {
        single { DriverFactory(get()) }
        single { ConnectivityMonitor(get()) }
        // knit_note_prefs is unencrypted — suitable for non-sensitive UX flags only.
        // Use EncryptedSharedPreferences for auth tokens and user PII.
        single<Settings> { SharedPreferencesSettings.Factory(get()).create("knit_note_prefs") }
    }
