package com.knitnote.android

import android.app.Application
import com.knitnote.di.platformModule
import com.knitnote.di.sharedModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class KnitNoteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@KnitNoteApplication)
            modules(listOf(platformModule) + sharedModules)
        }
    }
}
