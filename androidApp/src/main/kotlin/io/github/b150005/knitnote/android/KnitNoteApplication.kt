package io.github.b150005.knitnote.android

import android.app.Application
import android.util.Log
import io.github.b150005.knitnote.data.remote.SupabaseConfig
import io.github.b150005.knitnote.data.remote.isConfigured
import io.github.b150005.knitnote.di.platformModule
import io.github.b150005.knitnote.di.sharedModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class KnitNoteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@KnitNoteApplication)
            modules(listOf(platformModule) + sharedModules)
        }.also {
            Log.i("KnitNote", "Koin initialized — Supabase configured: ${SupabaseConfig.isConfigured}")
        }
    }
}
