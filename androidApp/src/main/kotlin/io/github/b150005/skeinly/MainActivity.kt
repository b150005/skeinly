package io.github.b150005.skeinly

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import io.github.b150005.skeinly.ui.navigation.BugReportPreview
import io.github.b150005.skeinly.ui.navigation.SkeinlyNavHost
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    private var deepLinkToken by mutableStateOf<String?>(null)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkToken = extractShareToken(intent)
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
                        deepLinkToken = deepLinkToken,
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
            "ChartDiff" -> Screen.ChartDiff
            "ChartConflictResolution" -> Screen.ChartConflictResolution
            "SymbolGallery" -> Screen.SymbolGallery
            "ActivityFeed" -> Screen.ActivityFeed
            "SharedWithMe" -> Screen.SharedWithMe
            "SharedContent" -> Screen.SharedContent
            "PullRequestList" -> Screen.PullRequestList
            "PullRequestDetail" -> Screen.PullRequestDetail
            "BugReportPreview" -> Screen.BugReportPreview
            else -> null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkToken = extractShareToken(intent)
    }

    private fun extractShareToken(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "skeinly" || data.host != "share") return null
        val segment = data.lastPathSegment ?: return null

        // Validate token format: UUID v4 (tokens are generated via Uuid.random().toString())
        val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        return if (uuidPattern.matches(segment)) segment else null
    }

    private companion object {
        const val LONG_PRESS_THRESHOLD_MS: Long = 500L
    }
}
