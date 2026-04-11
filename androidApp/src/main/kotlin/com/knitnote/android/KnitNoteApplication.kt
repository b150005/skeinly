package com.knitnote.android

import android.app.Application
import android.util.Log
import com.knitnote.data.remote.SupabaseConfig
import com.knitnote.data.remote.isConfigured
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
        }.also {
            Log.i("KnitNote", "Koin initialized — Supabase configured: ${SupabaseConfig.isConfigured}")
        }
    }
}
