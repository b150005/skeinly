package com.knitnote

import com.knitnote.di.platformModule
import com.knitnote.di.sharedModules
import org.koin.core.context.startKoin

fun initKoin() {
    startKoin {
        modules(listOf(platformModule) + sharedModules)
    }
}
