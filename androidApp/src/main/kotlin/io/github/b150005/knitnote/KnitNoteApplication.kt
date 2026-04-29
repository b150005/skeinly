package io.github.b150005.knitnote

import android.app.Application
import android.util.Log
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import io.github.b150005.knitnote.data.analytics.AnalyticsTracker
import io.github.b150005.knitnote.data.preferences.AnalyticsPreferences
import io.github.b150005.knitnote.data.remote.SupabaseConfig
import io.github.b150005.knitnote.data.remote.isConfigured
import io.github.b150005.knitnote.di.platformModule
import io.github.b150005.knitnote.di.sharedModules
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.util.concurrent.atomic.AtomicBoolean

class KnitNoteApplication : Application() {
    // Application-lifetime scope for cross-cutting observers (analytics opt-in
    // flow). Default dispatcher is fine — PostHog SDK calls are non-blocking
    // and do not need the main thread.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

        // Phase F2: PostHog product analytics. Default OFF (opt-in, not
        // opt-out) per Phase 27a no-tracking stance. The SDK is initialized
        // lazily on the first ON flip so no events are queued/sent before
        // the user explicitly consents. Toggling OFF mid-session calls
        // PostHog.optOut() to suspend further capture without losing the
        // existing session; toggling ON again calls optIn() (no re-init).
        val posthogApiKey = BuildConfig.POSTHOG_API_KEY
        if (posthogApiKey.isNotEmpty()) {
            val analyticsPrefs: AnalyticsPreferences = get()
            // Phase F.3 promoted `posthogInitialized` from a captured `var` to
            // an AtomicBoolean because the analytics-events collector below
            // reads it from a separate coroutine. AtomicBoolean is the JVM
            // visibility guarantee the cross-coroutine read needs.
            val posthogInitialized = AtomicBoolean(false)
            applicationScope.launch {
                analyticsPrefs.analyticsOptIn.collect { optIn ->
                    when {
                        optIn && !posthogInitialized.get() -> {
                            val config =
                                PostHogAndroidConfig(
                                    apiKey = posthogApiKey,
                                    host = BuildConfig.POSTHOG_HOST,
                                ).apply {
                                    // Privacy-respecting defaults: no autocapture
                                    // of touch events, no screen-view ping, no
                                    // app-lifecycle pings, no session replay,
                                    // no automatic $feature_flag_called events.
                                    // Phase F+ wires explicit PostHog.capture()
                                    // calls for the events we actually want.
                                    captureScreenViews = false
                                    captureApplicationLifecycleEvents = false
                                    captureDeepLinks = false
                                    sessionReplay = false
                                    sendFeatureFlagEvent = false
                                }
                            PostHogAndroid.setup(this@KnitNoteApplication, config)
                            posthogInitialized.set(true)
                            Log.i("KnitNote", "PostHog initialized (analytics opt-in)")
                        }
                        !optIn && posthogInitialized.get() -> {
                            PostHog.optOut()
                            Log.i("KnitNote", "PostHog opt-out (toggled off)")
                        }
                        optIn && posthogInitialized.get() -> {
                            // Re-enable after toggling off then on in the same
                            // session — the SDK's optIn() flips a flag without
                            // re-initializing.
                            PostHog.optIn()
                            Log.i("KnitNote", "PostHog opt-in (re-enabled)")
                        }
                    }
                }
            }

            // Phase F.3 — bridge shared AnalyticsTracker events to PostHog.
            // The tracker itself silently no-ops when opt-in is OFF, so this
            // collector only sees events that the user has consented to.
            // The `posthogInitialized.get()` guard handles the brief window
            // between opt-in flipping ON and the SDK setup() landing — any
            // event that lands in that gap is silently dropped (PostHog SDK
            // would emit a "not initialized" warning otherwise).
            //
            // Phase F.4 — `event.properties` is `Map<String, Any>?` matching
            // PostHog Android's expected `properties: Map<String, Any>?`
            // shape. Bool / Int / Double / String values pass through to
            // PostHog's typed event-property storage; cardinality discipline
            // is enforced at the call site (see AnalyticsEvents.Props KDoc).
            val analyticsTracker: AnalyticsTracker = get()
            applicationScope.launch {
                analyticsTracker.events.collect { event ->
                    if (posthogInitialized.get()) {
                        PostHog.capture(event = event.name, properties = event.properties)
                    }
                }
            }
        }
    }
}
