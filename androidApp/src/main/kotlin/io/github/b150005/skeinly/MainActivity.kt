package io.github.b150005.skeinly

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.b150005.skeinly.config.BuildFlags
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.Screen
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferences
import io.github.b150005.skeinly.notifications.PushTokenRegistrar
import io.github.b150005.skeinly.ui.navigation.BugReportPreview
import io.github.b150005.skeinly.ui.navigation.SkeinlyNavHost
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    /**
     * Phase 39 (W3 / 2026-05-11) — full Universal Link / App Link URL
     * captured from Intent.data when the OS routes a verified
     * `https://b150005.github.io/skeinly/...` link to this Activity.
     * Renamed from the Phase 24.5 era `deepLinkToken` (which carried
     * only the bare share-token UUID extracted from the legacy
     * `skeinly://share/<token>` custom scheme — that scheme was deleted
     * from AndroidManifest.xml in this same slice, no Tech Debt
     * fallback per pre-v1 breaking changes accepted policy).
     *
     * Parsing happens inside the shared `SkeinlyNavHost` via
     * `parseExternalRoute(url)` so route-shape evolution stays in
     * commonMain.
     */
    private var deepLinkUrl by mutableStateOf<String?>(null)

    /**
     * Phase 24.5 (ADR-017 §3.8) — host-relative push route from a
     * notification tap. Set in [onCreate] (cold start with the
     * notification's intent extras) and [onNewIntent] (warm start;
     * `singleTop` launchMode reuses the existing Activity). The shared
     * NavHost reads it via the `pushRoute` parameter and consumes it
     * once via `LaunchedEffect`, calling [onPushRouteConsumed] to
     * clear the state so a recomposition does not re-fire navigation.
     */
    private var pushRoute by mutableStateOf<String?>(null)

    // Phase 39.5 (ADR-015 §1) — 3-finger long-press detector state. The
    // detector watches `dispatchTouchEvent` (root-level) so it sees every
    // pointer event before any descendant Composable consumes it. We only
    // consume the gesture (returning `true`) on the qualifying long-press
    // emission; in every other branch we fall through to the default
    // dispatch so existing scrolls / taps stay intact.
    private var threeFingerStartTime: Long = 0L
    private val analyticsPrefs: AnalyticsPreferences by lazy { get<AnalyticsPreferences>() }

    // Captured from the Compose graph so the gesture handler can navigate
    // outside of a Composable scope. Set by `setContent` and cleared in
    // `onDestroy` so a configuration change rebinds cleanly.
    private var navController: NavHostController? = null

    // Phase 24.2e (ADR-017 §3.6) — Activity-scoped runtime POST_NOTIFICATIONS
    // prompt. The shared `PushTokenRegistrar` (Koin singleton, lives across
    // configuration changes) holds a callback we wire here in `onCreate`
    // and clear in `onDestroy` so the registrar never points at a destroyed
    // Activity. The launcher MUST be created at field-init time per the
    // ActivityResultLauncher contract — calling `registerForActivityResult`
    // after `Activity.onCreate` completes throws.
    private val pushTokenRegistrar: PushTokenRegistrar by lazy { get<PushTokenRegistrar>() }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pushTokenRegistrar.onPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkUrl = extractDeepLinkUrl(intent)
        pushRoute = extractPushRoute(intent)
        // Phase 24.2e — wire the Activity-scoped permission launcher to the
        // shared `PushTokenRegistrar`. The closure fires the Activity's
        // `ActivityResultLauncher.launch(POST_NOTIFICATIONS)`; the registered
        // callback at field-init forwards the result back via
        // `pushTokenRegistrar.onPermissionResult`. Cleared in `onDestroy` so
        // a re-created Activity rebinds cleanly without the registrar
        // holding a destroyed Activity reference.
        pushTokenRegistrar.attachLauncher {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Phase 39.3 (ADR-015 §6) — resolve once at activity creation;
        // tracker is a Koin singleton so a second resolve from inside
        // the Composable would return the same instance, but pulling at
        // the activity boundary is cheaper.
        val analyticsTracker: AnalyticsTracker = get()
        setContent {
            SkeinlyTheme {
                val nc = rememberNavController()
                navController = nc
                // Phase 39.3 (ADR-015 §6) — emit `ScreenViewed` to PostHog
                // and the `EventRingBuffer` whenever the back-stack top
                // changes. Bridges the type-safe Compose Navigation route
                // (`destination.route` is the Kotlin FQN serializer name,
                // optionally with `/{param}` suffixes) onto the shared
                // [Screen] enum's testTag-aligned wire values via simple
                // FQN-strip + simple-name match — see
                // [routeStringToScreen] for the mapping. Unmappable routes
                // (e.g. ForgotPassword, which is intentionally outside the
                // engagement-funnel signal) silently no-op.
                DisposableEffect(nc) {
                    val listener =
                        NavController.OnDestinationChangedListener { _, destination, _ ->
                            val screen = routeStringToScreen(destination.route) ?: return@OnDestinationChangedListener
                            analyticsTracker.track(AnalyticsEvent.ScreenViewed(screen))
                        }
                    nc.addOnDestinationChangedListener(listener)
                    onDispose { nc.removeOnDestinationChangedListener(listener) }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                ) {
                    SkeinlyNavHost(
                        navController = nc,
                        deepLinkUrl = deepLinkUrl,
                        pushRoute = pushRoute,
                        onPushRouteConsumed = { pushRoute = null },
                    )
                }
            }
        }
    }

    /**
     * Phase 39.5 (ADR-015 §1) — root-level 3-finger long-press detector.
     *
     * Why root: the ADR rejected gesture-detection inside the Compose
     * pointer-input modifier because nested Composables (LazyList, swipe
     * containers) consume multi-touch sequences before the outer modifier
     * can observe them. `dispatchTouchEvent` runs **before** the view
     * tree's hit-testing, so the detector sees every pointer regardless
     * of which descendant ultimately handles the gesture.
     *
     * Gating: silently no-ops on production binaries (`BuildFlags.isBeta`
     * false) and when diagnostic-data sharing is OFF
     * (`analyticsOptIn.value` false). Both gates short-circuit on the
     * fast path so the dispatch overhead on production is a single
     * boolean read per touch event — measurably zero against the
     * Choreographer cadence.
     *
     * 500ms threshold matches Compose's `combinedClickable` long-press
     * default and the Phase 39.0.2 Sprint B M6 swipe-context-menu
     * pattern. The user "puts down their needles" before the report
     * fires, mirroring the deliberate-gesture rationale from the
     * knitter voice in ADR-015 §1.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (BuildFlags.isBeta && analyticsPrefs.analyticsOptIn.value) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount == 3) {
                        threeFingerStartTime = SystemClock.uptimeMillis()
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (ev.pointerCount <= 2) {
                        threeFingerStartTime = 0L
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val started = threeFingerStartTime
                    if (
                        ev.pointerCount == 3 &&
                        started > 0L &&
                        SystemClock.uptimeMillis() - started >= LONG_PRESS_THRESHOLD_MS
                    ) {
                        // Reset *before* navigating so a long-running
                        // dispatch on the navigation hand-off cannot
                        // re-fire the detector during the same gesture.
                        threeFingerStartTime = 0L
                        openBugReportPreview()
                        return true // consume — drop the gesture from
                        // descendant handlers, otherwise the still-down
                        // 3-finger contact would resume scroll/zoom on
                        // whatever surface was underneath.
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun openBugReportPreview() {
        // Race tolerance: navController is only null between onDestroy
        // and the next onCreate (configuration change). `runCatching` on
        // navigate() bounds any IllegalStateException emitted by
        // NavController if the back-stack is in flight.
        val nc = navController ?: return
        runCatching { nc.navigate(BugReportPreview) }
    }

    override fun onDestroy() {
        navController = null
        // Phase 24.2e — clear the Activity-scoped launcher reference so
        // a config-change tear-down does not leave the registrar pointing
        // at a destroyed Activity. `detachLauncher` also completes any
        // in-flight `requestPermission` deferred with `false` so a
        // user-tapped "Enable" suspended over the tear-down resolves
        // cleanly rather than leaking a coroutine.
        pushTokenRegistrar.detachLauncher()
        super.onDestroy()
    }

    /**
     * Phase 39.3 (ADR-015 §6) — extracts the simple class name from a
     * Compose-Navigation type-safe route string and maps it to [Screen].
     *
     * `destination.route` shape: `io.github.b150005.skeinly.ui.navigation.ProjectList`
     * for `data object ProjectList`, or
     * `io.github.b150005.skeinly.ui.navigation.ProjectDetail/{projectId}`
     * for `data class ProjectDetail(val projectId: String)`. We strip the
     * package prefix with `substringAfterLast('.')` and any `/{param}`
     * suffix with `substringBefore('/')`, leaving the simple name.
     *
     * Routes that do not map to a tracked screen (`ForgotPassword`, etc.)
     * return null — the listener silently skips them rather than
     * fire a placeholder, so PostHog dashboards stay clean.
     */
    private fun routeStringToScreen(route: String?): Screen? {
        if (route.isNullOrEmpty()) return null
        val simpleName = route.substringAfterLast('.').substringBefore('/')
        return when (simpleName) {
            "Onboarding" -> Screen.Onboarding
            "Login" -> Screen.Login
            "ProjectList" -> Screen.ProjectList
            "ProjectDetail" -> Screen.ProjectDetail
            "Profile" -> Screen.Profile
            "Settings" -> Screen.Settings
            "Discovery" -> Screen.Discovery
            "PatternLibrary" -> Screen.PatternLibrary
            "PatternEdit" -> Screen.PatternEdit
            "ChartViewer" -> Screen.ChartViewer
            "ChartEditor" -> Screen.ChartEditor
            "ChartHistory" -> Screen.ChartHistory
            "ChartComparison" -> Screen.ChartComparison
            "ChartConflictResolution" -> Screen.ChartConflictResolution
            "SymbolGallery" -> Screen.SymbolGallery
            "ActivityFeed" -> Screen.ActivityFeed
            "SharedWithMe" -> Screen.SharedWithMe
            "SharedContent" -> Screen.SharedContent
            "SuggestionList" -> Screen.SuggestionList
            "SuggestionDetail" -> Screen.SuggestionDetail
            "BugReportPreview" -> Screen.BugReportPreview
            else -> null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkUrl = extractDeepLinkUrl(intent)
        // Phase 24.5 — `singleTop` launchMode delivers FCM-tap intents
        // here when the app is already in foreground/background.
        // Re-extract on every fresh intent so the NavHost LaunchedEffect
        // observes a state change and routes through.
        val nextRoute = extractPushRoute(intent)
        if (nextRoute != null) {
            pushRoute = nextRoute
        }
    }

    /**
     * Phase 24.5 (ADR-017 §3.8) — extract the host-relative deep-link
     * route from a notification-tap intent. FCM auto-fills `data`
     * map entries as intent extras on the launcher Activity at tap
     * time; the Edge Function sends `data.route = "pull-request/<prId>"`
     * (see `supabase/functions/notify-on-write/fcm.ts`). Validate
     * non-blank to avoid surfacing a bogus blank-extra navigation.
     */
    private fun extractPushRoute(intent: Intent?): String? {
        val raw = intent?.getStringExtra(EXTRA_PUSH_ROUTE) ?: return null
        return raw.takeIf { it.isNotBlank() }
    }

    /**
     * Phase 39 (W3 / 2026-05-11) — extract a Universal Link / App Link
     * URL from a launch / re-launch Intent. Returns the full URL string
     * (rather than a parsed route) so the shared `SkeinlyNavHost` owns
     * the parsing via `parseExternalRoute(url)` — evolutions of the URL
     * family stay in commonMain.
     *
     * The OS routes a URL here only if the AndroidManifest intent-filter
     * matches AND Play's autoVerify domain check has confirmed
     * `https://b150005.github.io/.well-known/assetlinks.json` lists the
     * package + signing fingerprint. Defense-in-depth: we still validate
     * scheme/host here so a misconfigured intent-filter (or a cleartext
     * fallback path) cannot leak unverified URLs into the nav surface.
     *
     * Returns null when the intent has no URI, or the URI is not under
     * https://b150005.github.io/skeinly/ — `parseExternalRoute` re-validates
     * the prefix as defense-in-depth, but rejecting at the host boundary
     * keeps the nav surface clean.
     */
    private fun extractDeepLinkUrl(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "https" || data.host != "b150005.github.io") return null
        if (data.path?.startsWith("/skeinly/") != true) return null
        return data.toString()
    }

    companion object {
        /**
         * Phase 24.5 (ADR-017 §3.8) — intent-extra key carrying the
         * push-tap route forwarded from
         * [io.github.b150005.skeinly.notifications.SkeinlyMessagingService]
         * (foreground delivery) or FCM's auto-displayed notification's
         * tap intent (background / killed delivery; FCM populates
         * intent extras from the `data` map verbatim, so the FCM
         * payload's `data.route` field surfaces under this key).
         */
        const val EXTRA_PUSH_ROUTE: String = "route"

        private const val LONG_PRESS_THRESHOLD_MS: Long = 500L
    }
}
