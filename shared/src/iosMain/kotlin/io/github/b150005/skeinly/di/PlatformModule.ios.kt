package io.github.b150005.skeinly.di

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import io.github.b150005.skeinly.data.remote.ConnectivityMonitor
import io.github.b150005.skeinly.db.DriverFactory
import org.koin.dsl.module

val platformModule =
    module {
        single { DriverFactory() }
        single { ConnectivityMonitor() }
        // knit_note_prefs is unencrypted NSUserDefaults — suitable for non-sensitive UX flags only.
        // Use Keychain for auth tokens and user PII.
        single<Settings> { NSUserDefaultsSettings.Factory().create("knit_note_prefs") }
    }
