package io.github.b150005.knitnote.di

import io.github.b150005.knitnote.db.DriverFactory
import io.github.b150005.knitnote.db.KnitNoteDatabase
import org.koin.dsl.module

val databaseModule =
    module {
        single { get<DriverFactory>().createDriver() }
        single { KnitNoteDatabase(get()) }
    }
