package com.knitnote.ui.navigation

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
import com.knitnote.data.remote.SupabaseConfig
import com.knitnote.data.remote.isConfigured
import com.knitnote.domain.model.AuthState
import com.knitnote.domain.usecase.GetOnboardingCompletedUseCase
import com.knitnote.ui.activityfeed.ActivityFeedScreen
import com.knitnote.ui.auth.AuthViewModel
import com.knitnote.ui.auth.LoginScreen
import com.knitnote.ui.discovery.DiscoveryScreen
import com.knitnote.ui.onboarding.OnboardingScreen
import com.knitnote.ui.patternedit.PatternEditScreen
import com.knitnote.ui.patternlibrary.PatternLibraryScreen
import com.knitnote.ui.profile.ProfileScreen
import com.knitnote.ui.projectdetail.ProjectDetailScreen
import com.knitnote.ui.projectlist.ProjectListScreen
import com.knitnote.ui.settings.SettingsScreen
import com.knitnote.ui.sharedcontent.SharedContentScreen
import com.knitnote.ui.sharedwithme.SharedWithMeScreen
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
