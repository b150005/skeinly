package com.knitnote.di

import com.knitnote.data.remote.ConnectivityMonitor
import com.knitnote.db.DriverFactory
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import org.koin.dsl.module

val platformModule =
    module {
        single { DriverFactory() }
        single { ConnectivityMonitor() }
        // knit_note_prefs is unencrypted NSUserDefaults — suitable for non-sensitive UX flags only.
        // Use Keychain for auth tokens and user PII.
        single<Settings> { NSUserDefaultsSettings.Factory().create("knit_note_prefs") }
    }
