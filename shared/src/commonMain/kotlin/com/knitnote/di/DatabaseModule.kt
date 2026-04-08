package com.knitnote.di

import com.knitnote.db.DriverFactory
import com.knitnote.db.KnitNoteDatabase
import org.koin.dsl.module

val databaseModule = module {
    single { get<DriverFactory>().createDriver() }
    single { KnitNoteDatabase(get()) }
}
