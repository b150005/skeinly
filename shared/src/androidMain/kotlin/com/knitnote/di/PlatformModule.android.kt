package com.knitnote.di

import com.knitnote.data.remote.ConnectivityMonitor
import com.knitnote.db.DriverFactory
import org.koin.dsl.module

val platformModule = module {
    single { DriverFactory(get()) }
    single { ConnectivityMonitor(get()) }
}
