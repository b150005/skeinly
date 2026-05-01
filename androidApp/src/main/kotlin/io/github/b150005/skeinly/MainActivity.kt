package io.github.b150005.skeinly

import android.content.Intent
import android.os.Bundle
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
import androidx.navigation.compose.rememberNavController
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.Screen
import io.github.b150005.skeinly.ui.navigation.SkeinlyNavHost
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    private var deepLinkToken by mutableStateOf<String?>(null)

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
                val navController = rememberNavController()
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
                DisposableEffect(navController) {
                    val listener =
                        NavController.OnDestinationChangedListener { _, destination, _ ->
                            val screen = routeStringToScreen(destination.route) ?: return@OnDestinationChangedListener
                            analyticsTracker.track(AnalyticsEvent.ScreenViewed(screen))
                        }
                    navController.addOnDestinationChangedListener(listener)
                    onDispose { navController.removeOnDestinationChangedListener(listener) }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                ) {
                    SkeinlyNavHost(
                        navController = navController,
                        deepLinkToken = deepLinkToken,
                    )
                }
            }
        }
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
}
