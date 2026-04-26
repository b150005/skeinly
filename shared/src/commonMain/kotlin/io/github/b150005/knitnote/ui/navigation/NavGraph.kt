package io.github.b150005.knitnote.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.b150005.knitnote.data.remote.SupabaseConfig
import io.github.b150005.knitnote.data.remote.isConfigured
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.usecase.GetOnboardingCompletedUseCase
import io.github.b150005.knitnote.ui.activityfeed.ActivityFeedScreen
import io.github.b150005.knitnote.ui.auth.AuthViewModel
import io.github.b150005.knitnote.ui.auth.LoginScreen
import io.github.b150005.knitnote.ui.chart.ChartDiffScreen
import io.github.b150005.knitnote.ui.chart.ChartEditorScreen
import io.github.b150005.knitnote.ui.chart.ChartHistoryScreen
import io.github.b150005.knitnote.ui.chart.ChartViewerScreen
import io.github.b150005.knitnote.ui.discovery.DiscoveryScreen
import io.github.b150005.knitnote.ui.onboarding.OnboardingScreen
import io.github.b150005.knitnote.ui.patternedit.PatternEditScreen
import io.github.b150005.knitnote.ui.patternlibrary.PatternLibraryScreen
import io.github.b150005.knitnote.ui.profile.ProfileScreen
import io.github.b150005.knitnote.ui.projectdetail.ProjectDetailScreen
import io.github.b150005.knitnote.ui.projectlist.ProjectListScreen
import io.github.b150005.knitnote.ui.pullrequest.PullRequestFilter
import io.github.b150005.knitnote.ui.pullrequest.PullRequestListScreen
import io.github.b150005.knitnote.ui.settings.SettingsScreen
import io.github.b150005.knitnote.ui.sharedcontent.SharedContentScreen
import io.github.b150005.knitnote.ui.sharedwithme.SharedWithMeScreen
import io.github.b150005.knitnote.ui.symbol.SymbolGalleryScreen
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Serializable
data object Onboarding

@Serializable
data object Login

@Serializable
data object ProjectList

@Serializable
data class ProjectDetail(
    val projectId: String,
)

@Serializable
data object SharedWithMe

@Serializable
data object ActivityFeed

@Serializable
data object Profile

@Serializable
data object Settings

@Serializable
data object PatternLibrary

@Serializable
data class PatternEdit(
    val patternId: String? = null,
)

@Serializable
data object Discovery

@Serializable
data class ChartViewer(
    val patternId: String,
    /**
     * Project this chart is viewed from. Per-segment progress (Phase 34) is
     * stored per-project, so the overlay wiring only engages when opened from
     * a project context. Null when the viewer is reached for bare pattern
     * inspection.
     */
    val projectId: String? = null,
)

@Serializable
data class ChartEditor(
    val patternId: String,
)

/**
 * Phase 37.2 (ADR-013 §6) — newest-first commit history for a chart.
 * Reachable from `ChartViewerScreen`'s overflow menu and from
 * `ProjectDetailScreen`'s pattern-info section.
 */
@Serializable
data class ChartHistory(
    val patternId: String,
)

/**
 * Phase 37.3 (ADR-013 §5 §6) — side-by-side diff between two chart revisions.
 * `baseRevisionId` is nullable so the initial-commit case (no parent) renders
 * an "Initial commit" view in the base pane. `targetRevisionId` is required.
 */
@Serializable
data class ChartDiff(
    val baseRevisionId: String?,
    val targetRevisionId: String,
)

@Serializable
data object SymbolGallery

/**
 * Phase 38.2 (ADR-014 §6 §8) — read-only pull-request list. The chip row in
 * the screen toggles between Incoming / Outgoing without re-navigating, but
 * the entry point picks the *initial* filter:
 * - From `ProjectListScreen` overflow → INCOMING (current user receives
 *   suggestions targeting any pattern they own).
 * - From `ProjectDetailScreen` PatternInfoSection on a non-fork pattern →
 *   INCOMING; on a fork pattern → OUTGOING (per ADR-014 §6).
 */
@Serializable
data class PullRequestList(
    val defaultFilter: PullRequestFilter = PullRequestFilter.INCOMING,
)

@Serializable
data class SharedContent(
    val token: String? = null,
    val shareId: String? = null,
)

@Composable
fun KnitNoteNavHost(
    navController: NavHostController,
    deepLinkToken: String? = null,
) {
    // Check onboarding state
    val getOnboardingCompleted: GetOnboardingCompletedUseCase = koinInject()
    val hasSeenOnboarding = remember { getOnboardingCompleted() }

    // If Supabase is not configured, skip auth entirely (local-only mode)
    val requiresAuth = SupabaseConfig.isConfigured
    var pendingDeepLinkToken by remember { mutableStateOf(deepLinkToken) }

    // Sync parameter changes (e.g., from onNewIntent) into local state
    LaunchedEffect(deepLinkToken) {
        if (deepLinkToken != null) {
            pendingDeepLinkToken = deepLinkToken
        }
    }

    if (requiresAuth) {
        val authViewModel: AuthViewModel = koinViewModel()
        val authUiState by authViewModel.state.collectAsState()

        LaunchedEffect(authUiState.authState) {
            when (authUiState.authState) {
                is AuthState.Authenticated -> {
                    navController.navigate(ProjectList) {
                        popUpTo(Login) { inclusive = true }
                    }
                    // Navigate to deep link after authentication
                    pendingDeepLinkToken?.let { token ->
                        navController.navigate(SharedContent(token = token))
                        pendingDeepLinkToken = null
                    }
                }
                is AuthState.Unauthenticated -> {
                    navController.navigate(Login) {
                        popUpTo(0) { inclusive = true }
                    }
                }
                else -> { /* Loading / Error — stay on current screen */ }
            }
        }
    } else {
        // No auth required — handle deep link immediately
        LaunchedEffect(pendingDeepLinkToken) {
            pendingDeepLinkToken?.let { token ->
                navController.navigate(SharedContent(token = token))
                pendingDeepLinkToken = null
            }
        }
    }

    // Onboarding is a UX gate only. Auth enforcement is handled by LaunchedEffect(authUiState.authState)
    // above and cannot be bypassed by clearing the onboarding preference.
    val startDestination: Any =
        if (!hasSeenOnboarding) {
            Onboarding
        } else if (requiresAuth) {
            Login
        } else {
            ProjectList
        }

    NavHost(navController = navController, startDestination = startDestination) {
        composable<Onboarding> {
            OnboardingScreen(
                onComplete = {
                    val next: Any = if (requiresAuth) Login else ProjectList
                    navController.navigate(next) {
                        popUpTo(Onboarding) { inclusive = true }
                    }
                },
            )
        }
        composable<Login> {
            LoginScreen()
        }
        composable<ProjectList> {
            ProjectListScreen(
                onProjectClick = { projectId ->
                    navController.navigate(ProjectDetail(projectId = projectId))
                },
                onPatternLibraryClick = {
                    navController.navigate(PatternLibrary)
                },
                onSharedWithMeClick = {
                    navController.navigate(SharedWithMe)
                },
                onActivityFeedClick = {
                    navController.navigate(ActivityFeed)
                },
                onProfileClick = {
                    navController.navigate(Profile)
                },
                onSettingsClick = {
                    navController.navigate(Settings)
                },
                onDiscoverClick = {
                    navController.navigate(Discovery)
                },
                onSymbolGalleryClick = {
                    navController.navigate(SymbolGallery)
                },
                onSuggestionsClick = {
                    navController.navigate(PullRequestList(defaultFilter = PullRequestFilter.INCOMING))
                },
            )
        }
        composable<SymbolGallery> {
            SymbolGalleryScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<Discovery> {
            DiscoveryScreen(
                onBack = { navController.popBackStack() },
                onForked = { projectId ->
                    navController.navigate(ProjectDetail(projectId = projectId)) {
                        popUpTo(ProjectList)
                    }
                },
                // Phase 36.4 (ADR-012 §5): chart-preview thumbnail tap opens
                // the read-only chart viewer with no project context.
                onChartViewerClick = { patternId ->
                    navController.navigate(ChartViewer(patternId = patternId, projectId = null))
                },
            )
        }
        composable<PatternLibrary> {
            PatternLibraryScreen(
                onPatternClick = { patternId ->
                    navController.navigate(PatternEdit(patternId = patternId))
                },
                onCreatePattern = {
                    navController.navigate(PatternEdit())
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<PatternEdit> { backStackEntry ->
            val route = backStackEntry.toRoute<PatternEdit>()
            PatternEditScreen(
                patternId = route.patternId,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Profile> {
            ProfileScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<Settings> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onAccountDeleted = {
                    navController.navigate(Login) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable<ActivityFeed> {
            ActivityFeedScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<SharedWithMe> {
            SharedWithMeScreen(
                onBack = { navController.popBackStack() },
                onShareClick = { shareId ->
                    navController.navigate(SharedContent(shareId = shareId))
                },
            )
        }
        composable<ProjectDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<ProjectDetail>()
            ProjectDetailScreen(
                projectId = route.projectId,
                onBack = { navController.popBackStack() },
                onChartViewerClick = { patternId ->
                    navController.navigate(
                        ChartViewer(patternId = patternId, projectId = route.projectId),
                    )
                },
                onChartEditorClick = { patternId ->
                    navController.navigate(ChartEditor(patternId = patternId))
                },
                // Phase 36.5 (ADR-012 §6): "Forked from" attribution tap routes
                // to the source pattern's read-only chart viewer. `projectId =
                // null` because the user is browsing someone else's pattern —
                // segment overlay belongs to the current project, not the source.
                onParentPatternClick = { parentPatternId ->
                    navController.navigate(
                        ChartViewer(patternId = parentPatternId, projectId = null),
                    )
                },
                // Phase 37.2 (ADR-013 §6).
                onChartHistoryClick = { patternId ->
                    navController.navigate(ChartHistory(patternId = patternId))
                },
                // Phase 38.2 (ADR-014 §6) — default filter Incoming when the
                // current pattern is the upstream, Outgoing when it is itself
                // a fork (the user authored PRs against the upstream).
                onSuggestionsClick = { isFork ->
                    val filter =
                        if (isFork) {
                            PullRequestFilter.OUTGOING
                        } else {
                            PullRequestFilter.INCOMING
                        }
                    navController.navigate(PullRequestList(defaultFilter = filter))
                },
            )
        }
        composable<ChartViewer> { backStackEntry ->
            val route = backStackEntry.toRoute<ChartViewer>()
            ChartViewerScreen(
                patternId = route.patternId,
                projectId = route.projectId,
                onBack = { navController.popBackStack() },
                onHistoryClick = {
                    navController.navigate(ChartHistory(patternId = route.patternId))
                },
            )
        }
        composable<ChartEditor> { backStackEntry ->
            val route = backStackEntry.toRoute<ChartEditor>()
            ChartEditorScreen(
                patternId = route.patternId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<ChartHistory> { backStackEntry ->
            val route = backStackEntry.toRoute<ChartHistory>()
            ChartHistoryScreen(
                patternId = route.patternId,
                onBack = { navController.popBackStack() },
                // Phase 37.3 (ADR-013 §6): tap a revision row → diff against its
                // parent. ViewModel resolves `baseRevisionId` from the tapped
                // row's `parentRevisionId`; null base routes to the initial-commit
                // view in `ChartDiffScreen`.
                onRevisionClick = { baseRevisionId, targetRevisionId ->
                    navController.navigate(
                        ChartDiff(baseRevisionId = baseRevisionId, targetRevisionId = targetRevisionId),
                    )
                },
            )
        }
        composable<ChartDiff> { backStackEntry ->
            val route = backStackEntry.toRoute<ChartDiff>()
            ChartDiffScreen(
                baseRevisionId = route.baseRevisionId,
                targetRevisionId = route.targetRevisionId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<PullRequestList> { backStackEntry ->
            val route = backStackEntry.toRoute<PullRequestList>()
            PullRequestListScreen(
                defaultFilter = route.defaultFilter,
                onBack = { navController.popBackStack() },
                // Phase 38.3 will route to PullRequestDetailScreen — 38.2 leaves
                // tap routing as a no-op so the list-only surface ships first.
                onPullRequestClick = { _ -> },
            )
        }
        composable<SharedContent> { backStackEntry ->
            val route = backStackEntry.toRoute<SharedContent>()
            SharedContentScreen(
                token = route.token,
                shareId = route.shareId,
                onBack = { navController.popBackStack() },
                onForked = { projectId ->
                    navController.navigate(ProjectDetail(projectId = projectId)) {
                        popUpTo(ProjectList)
                    }
                },
            )
        }
    }
}
