package io.github.b150005.knitnote

import android.app.Application
import android.util.Log
import io.github.b150005.knitnote.data.remote.SupabaseConfig
import io.github.b150005.knitnote.data.remote.isConfigured
import io.github.b150005.knitnote.di.platformModule
import io.github.b150005.knitnote.di.sharedModules
import io.sentry.android.core.SentryAndroid
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class KnitNoteApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Phase F1: init Sentry crash + error reporting BEFORE Koin so any
        // Koin init failure is captured. Empty DSN means local dev — skip.
        val sentryDsn = BuildConfig.SENTRY_DSN_ANDROID
        if (sentryDsn.isNotEmpty()) {
            SentryAndroid.init(this) { options ->
                options.dsn = sentryDsn
                options.release = BuildConfig.VERSION_NAME
                // Phase F1 ships with breadcrumbs but conservative perf
                // sampling — we'll tune via Sentry dashboard once telemetry
                // arrives. 1.0 captures everything; lowering to 0.2 for
                // production-shaped traffic happens in a Phase F+ pass.
                options.tracesSampleRate = 0.2
                options.isAttachScreenshot = false // Privacy: no screenshots
                options.isAttachViewHierarchy = false // Privacy: no UI tree dumps
            }
        }

        startKoin {
            androidContext(this@KnitNoteApplication)
            modules(listOf(platformModule) + sharedModules)
        }.also {
            Log.i(
                "KnitNote",
                "Koin initialized — Supabase configured: ${SupabaseConfig.isConfigured}, " +
                    "Sentry: ${if (sentryDsn.isNotEmpty()) "enabled" else "disabled"}",
            )
        }
    }
}
