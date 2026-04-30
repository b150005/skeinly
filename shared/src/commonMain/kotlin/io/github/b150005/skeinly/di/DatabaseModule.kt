package io.github.b150005.skeinly.di

import io.github.b150005.skeinly.db.DriverFactory
import io.github.b150005.skeinly.db.SkeinlyDatabase
import org.koin.dsl.module

val databaseModule =
    module {
        single { get<DriverFactory>().createDriver() }
        single { SkeinlyDatabase(get()) }
    }
