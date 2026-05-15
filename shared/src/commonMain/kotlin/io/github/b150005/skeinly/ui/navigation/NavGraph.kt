package io.github.b150005.skeinly.ui.navigation

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
import io.github.b150005.skeinly.data.analytics.PaywallTrigger
import io.github.b150005.skeinly.data.remote.SupabaseConfig
import io.github.b150005.skeinly.data.remote.isConfigured
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.usecase.GetOnboardingCompletedUseCase
import io.github.b150005.skeinly.ui.activityfeed.ActivityFeedScreen
import io.github.b150005.skeinly.ui.auth.AuthViewModel
import io.github.b150005.skeinly.ui.auth.ForgotPasswordScreen
import io.github.b150005.skeinly.ui.auth.LoginScreen
import io.github.b150005.skeinly.ui.auth.MfaChallengeScreen
import io.github.b150005.skeinly.ui.auth.MfaEnrollmentScreen
import io.github.b150005.skeinly.ui.bugreport.BugReportPreviewScreen
import io.github.b150005.skeinly.ui.chart.ChartComparisonScreen
import io.github.b150005.skeinly.ui.chart.ChartEditorScreen
import io.github.b150005.skeinly.ui.chart.ChartHistoryScreen
import io.github.b150005.skeinly.ui.chart.ChartViewerScreen
import io.github.b150005.skeinly.ui.connections.ConnectionsScreen
import io.github.b150005.skeinly.ui.discovery.DiscoveryScreen
import io.github.b150005.skeinly.ui.forceupdate.ForceUpdateGate
import io.github.b150005.skeinly.ui.onboarding.OAuthProfileSetupScreen
import io.github.b150005.skeinly.ui.onboarding.OnboardingScreen
import io.github.b150005.skeinly.ui.packmanagement.PackManagementScreen
import io.github.b150005.skeinly.ui.patternedit.PatternEditScreen
import io.github.b150005.skeinly.ui.patternlibrary.PatternLibraryScreen
import io.github.b150005.skeinly.ui.paywall.PaywallResult
import io.github.b150005.skeinly.ui.paywall.PaywallScreen
import io.github.b150005.skeinly.ui.profile.ProfileScreen
import io.github.b150005.skeinly.ui.projectdetail.ProjectDetailScreen
import io.github.b150005.skeinly.ui.projectlist.ProjectListScreen
import io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionScreen
import io.github.b150005.skeinly.ui.pullrequest.SuggestionDetailScreen
import io.github.b150005.skeinly.ui.pullrequest.SuggestionFilter
import io.github.b150005.skeinly.ui.pullrequest.SuggestionListScreen
import io.github.b150005.skeinly.ui.settings.SettingsScreen
import io.github.b150005.skeinly.ui.settings.WipeDataConfirmPhraseScreen
import io.github.b150005.skeinly.ui.sharedcontent.SharedContentScreen
import io.github.b150005.skeinly.ui.sharedwithme.SharedWithMeScreen
import io.github.b150005.skeinly.ui.symbol.SymbolGalleryScreen
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Serializable
data object Onboarding

@Serializable
data object Login

@Serializable
data object ForgotPassword

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
data class ChartComparison(
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
data class SuggestionList(
    val defaultFilter: SuggestionFilter = SuggestionFilter.INCOMING,
)

/**
 * Phase 38.3 (ADR-014 §6 §8) — single PR detail surface. Reached from
 * `SuggestionListScreen` row taps. The "Open pull request" entry on
 * `ChartViewerScreen`'s overflow menu also routes here (after the open-PR
 * flow lands; 38.3 ships the data layer + use case, the ChartViewer
 * compose-form-and-submit flow is a follow-up).
 */
@Serializable
data class SuggestionDetail(
    val prId: String,
)

/**
 * Phase 38.4 (ADR-014 §6) — interactive conflict resolution surface.
 * Reached from `SuggestionDetailScreen` when the user confirms merge AND
 * `ConflictDetector.detect(...)` returns at least one conflict. Auto-clean
 * merges bypass this route and invoke `ApplySuggestionUseCase` directly.
 */
@Serializable
data class ChartConflictResolution(
    val prId: String,
)

@Serializable
data class SharedContent(
    val token: String? = null,
    val shareId: String? = null,
)

/**
 * Phase 39.5 (ADR-015 §6) — bug-report preview screen reachable from
 * Settings → Beta → "Send Feedback" and from the platform-specific
 * gesture triggers (Android 3-finger long-press, iOS shake). Beta-only;
 * production builds never navigate here because the entry points are
 * gated on [io.github.b150005.skeinly.config.BuildFlags.isBeta].
 */
@Serializable
data object BugReportPreview

/**
 * Phase 41.3b (ADR-016 §5.1) — paywall route. Always reachable from
 * Settings → "Subscribe to Pro" (NOT beta-gated, unlike the Bug Report
 * Preview). Future Phase 41.4 will also surface this route from a
 * lock-badge tap on the chart editor palette and from the chart viewer
 * `?` cell-tap path.
 *
 * The [trigger] discriminator lands in `PaywallViewModel`'s init analytics
 * event so funnel analysis can tell which entry converts best (Settings
 * vs auto-lock-in-editor vs ?-cell tap). PaywallTrigger surface at the
 * KoinHelper bridge requires `parameters: PaywallTrigger` as a route arg
 * — kotlinx-serialization handles the enum natively without an ordinal
 * conversion.
 */
@Serializable
data class Paywall(
    val trigger: PaywallTrigger,
)

/**
 * Phase 41.4 (ADR-016 §5.2 §6 §41.4) — pack management screen reachable
 * from Settings → "Manage Symbol Packs". Always-on, NOT beta-gated
 * (matching the Phase 41.3b "Subscribe to Pro" entry).
 */
@Serializable
data object PackManagement

/**
 * Phase 26.5 (ADR-022 §6.4) — TOTP enrollment entry. Reached from
 * Settings → Security → "Enable two-factor authentication".
 */
@Serializable
data object MfaEnrollment

/**
 * Phase 26.5 (ADR-022 §6.4) — TOTP challenge gate. Routed-to
 * automatically when [AuthState.MfaChallengeRequired] is observed
 * (post-password sign-in with a verified factor). User cannot back
 * out of this screen via the back stack — the only exit is a
 * successful TOTP / recovery-code submission OR a sign-out (which
 * flips to AuthState.Unauthenticated → routes to Login).
 */
@Serializable
data object MfaChallenge

/**
 * Phase 26.6 (ADR-022 §6.5) — biometric authentication settings.
 * Reached from Settings → Security → "Biometric authentication".
 * Houses the opt-in toggle + re-auth threshold picker.
 */
@Serializable
data object BiometricSettings

/**
 * Phase 25.3 (ADR-024 §(e)) — Settings → Privacy → Connections screen.
 * Three-tab layout (Friends / Pending / Invite) for managing the
 * mutual-friendship graph + invite generation. Reached from
 * Settings → Privacy section.
 */
@Serializable
data object Connections

/**
 * Phase 27.2 (ADR-023 §UX) — data-wipe confirmation flow.
 * Reached from Settings → Danger Zone → "Delete all my data".
 *
 * The screen mounts a [io.github.b150005.skeinly.ui.settings.WipeDataViewModel]
 * with the locale-active required phrase (resolved at first composition
 * via `stringResource(Res.string.phrase_wipe_data_confirm)`). Two
 * phases inside this single route per ADR §UX:
 * 1. Preservation-matrix modal (`AlertDialog`)
 * 2. Phrase-typing screen — submit fires `wipe_own_data()` RPC,
 *    then pops back + emits via `WipeCompletionNotifier` so
 *    Pattern Library surfaces the success banner for 8 s.
 */
@Serializable
data object WipeDataConfirmPhrase

/**
 * Phase 26.6 (ADR-022 §6.6) — post-OAuth profile setup gate. Routed
 * to once per install on the FIRST Authenticated transition when the
 * AuthRepository surfaces a non-empty
 * [io.github.b150005.skeinly.domain.model.OAuthOnboardingMetadata.displayName]
 * AND the
 * [io.github.b150005.skeinly.data.preferences.OAuthProfileSetupPreferences.isCompleted]
 * flag is false. Two payload fields (display name + nullable picture
 * URL) are passed through the route so the screen does NOT have to
 * re-query AuthRepository at mount time — keeps the gate
 * deterministic on a single Authenticated emission.
 *
 * The route gets popped on Save / Skip via `popUpTo(OAuthProfileSetup) { inclusive = true }`.
 */
@Serializable
data class OAuthProfileSetup(
    val displayName: String?,
    val pictureUrl: String?,
)

@Composable
fun SkeinlyNavHost(
    navController: NavHostController,
    /**
     * Phase 39 (W3 / 2026-05-11) — full Universal Link / App Link URL
     * captured by the host (Android `MainActivity` via Intent.data;
     * iOS `AppRouter.swift` via `.onOpenURL`). Parsed via
     * [parseExternalRoute] into a typed Compose Navigation route at
     * mount time and replayed through the auth flow so a deep-link
     * launch on a logged-out client routes through Login → target
     * destination correctly. Renamed from the Phase 24.5 era
     * `deepLinkToken: String?` (which carried only the bare share-token
     * UUID) — the URL form covers both share tokens and pull-request
     * URLs and any post-alpha public URL surfaces under one parameter.
     */
    deepLinkUrl: String? = null,
    /**
     * Phase 24.5 (ADR-017 §3.8) — push-tap deep-link route. Format:
     * host-relative `pull-request/<prId>` (Phase 24 only emits PR
     * routes; future event sources extend the prefix table). Passed
     * through from the host platform (Android `MainActivity` via
     * intent extras; iOS `AppRootView` via `NotificationCenter`
     * publisher → onReceive). The composable consumes the value once
     * via `LaunchedEffect` and calls [onPushRouteConsumed] to clear
     * the host-side state, preventing re-fire on recomposition.
     *
     * Distinct from [deepLinkUrl] above: push payloads come from the
     * Edge Function via FCM/APNs and use a host-relative path format,
     * while external links arrive via OS Universal Link / App Link
     * dispatch and use full https URLs. Two formats, two parsers,
     * shared route output.
     */
    pushRoute: String? = null,
    onPushRouteConsumed: () -> Unit = {},
) {
    // Phase 39 (W4 / 2026-05-11) — force-update gate wraps the entire
    // NavHost. When `app_config.min_required_version_*` exceeds the
    // installed BuildFlags.versionName, the gate replaces the nav
    // surface with a blocking ForceUpdateScreen — no NavHost is
    // rendered, no destinations reachable, system back stays on the
    // force-update screen because there is no nav stack to pop.
    // Fail-open on no-cache + offline (offline-first contract).
    ForceUpdateGate {
        SkeinlyNavHostContent(
            navController = navController,
            deepLinkUrl = deepLinkUrl,
            pushRoute = pushRoute,
            onPushRouteConsumed = onPushRouteConsumed,
        )
    }
}

@Composable
private fun SkeinlyNavHostContent(
    navController: NavHostController,
    deepLinkUrl: String?,
    pushRoute: String?,
    onPushRouteConsumed: () -> Unit,
) {
    // Check onboarding state
    val getOnboardingCompleted: GetOnboardingCompletedUseCase = koinInject()
    val hasSeenOnboarding = remember { getOnboardingCompleted() }

    // If Supabase is not configured, skip auth entirely (local-only mode)
    val requiresAuth = SupabaseConfig.isConfigured

    // Parse the deep-link URL once and stash the typed Route so the
    // post-auth replay below can navigate without re-parsing. `null`
    // covers both "no deep link present" and "deep link present but
    // unrecognized URL family" — silent drop in the latter case (cf.
    // parseExternalRoute KDoc).
    var pendingDeepLinkRoute by remember {
        mutableStateOf(deepLinkUrl?.let { parseExternalRoute(it) })
    }

    // Sync parameter changes (e.g., from onNewIntent) into local state.
    // Re-parsing on each new URL keeps the typed route fresh.
    LaunchedEffect(deepLinkUrl) {
        if (deepLinkUrl != null) {
            pendingDeepLinkRoute = parseExternalRoute(deepLinkUrl)
        }
    }

    // Phase 24.5 — consume the push route once. Keying the
    // LaunchedEffect on the route string means a fresh tap with the
    // same route while the listener is still mounted does NOT re-fire
    // (the key is unchanged so the effect skips). This is acceptable
    // because the existing nav stack already has the destination on
    // top — re-pushing would just deepen the back stack pointlessly.
    // A different route value triggers the effect normally.
    LaunchedEffect(pushRoute) {
        val route = pushRoute ?: return@LaunchedEffect
        val target = parsePushRoute(route)
        if (target != null) {
            navController.navigate(target)
        }
        // Clear host state regardless of parse outcome — a malformed
        // route should not stick around for the next foreground.
        onPushRouteConsumed()
    }

    if (requiresAuth) {
        val authViewModel: AuthViewModel = koinViewModel()
        val authUiState by authViewModel.state.collectAsState()

        // Phase 26.6 (ADR-022 §6.6) — post-OAuth onboarding gate
        // dependencies. Resolved at composition time so the
        // LaunchedEffect can suspend on getOAuthOnboardingMetadata
        // (network-light: reads cached `auth.currentUserOrNull()` +
        // its `userMetadata`).
        val authRepositoryForOAuthGate: io.github.b150005.skeinly.domain.repository.AuthRepository =
            koinInject()
        val oauthGatePreferences: io.github.b150005.skeinly.data.preferences.OAuthProfileSetupPreferences =
            koinInject()

        LaunchedEffect(authUiState.authState) {
            when (authUiState.authState) {
                is AuthState.Authenticated -> {
                    // Phase 26.6 — short-circuit through the OAuth
                    // profile setup screen on the first Authenticated
                    // transition where the user has not yet completed
                    // the gate AND the OAuth provider supplied a
                    // displayName seed. Email / password sign-ups pass
                    // through silently (metadata null OR displayName null).
                    val gateTarget: OAuthProfileSetup? =
                        if (oauthGatePreferences.isCompleted) {
                            null
                        } else {
                            val metadata =
                                try {
                                    authRepositoryForOAuthGate.getOAuthOnboardingMetadata()
                                } catch (_: Throwable) {
                                    null
                                }
                            if (metadata?.displayName?.isNotBlank() == true) {
                                OAuthProfileSetup(
                                    displayName = metadata.displayName,
                                    pictureUrl = metadata.pictureUrl,
                                )
                            } else {
                                null
                            }
                        }

                    val firstTarget: Any = gateTarget ?: ProjectList
                    navController.navigate(firstTarget) {
                        popUpTo(Login) { inclusive = true }
                        // Phase 26.5: also clear MfaChallenge from the stack
                        // so verify-success pops the gate screen cleanly.
                        popUpTo(MfaChallenge) { inclusive = true }
                    }
                    // Navigate to deep link after authentication. Phase 39 W3:
                    // pendingDeepLinkRoute is already parsed (URL → typed Route)
                    // so this branch is route-shape-agnostic — extends to any
                    // future URL family without modifying the auth flow.
                    // Phase 26.6: defer deep-link replay until AFTER the
                    // OAuth setup gate completes (otherwise the gate
                    // would be obscured by the deep-link destination).
                    if (gateTarget == null) {
                        pendingDeepLinkRoute?.let { route ->
                            navController.navigate(route)
                            pendingDeepLinkRoute = null
                        }
                    }
                }
                is AuthState.MfaChallengeRequired -> {
                    // Phase 26.5 (ADR-022 §6.4) — post-AAL1 session with a
                    // verified factor. Surface the TOTP challenge as a
                    // full-screen gate. popUpTo Login so a back-tap from
                    // the challenge falls through to Unauthenticated /
                    // signs out cleanly (the only legitimate exit besides
                    // verify-success).
                    navController.navigate(MfaChallenge) {
                        popUpTo(Login) { inclusive = true }
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
        // No auth required — handle deep link immediately.
        LaunchedEffect(pendingDeepLinkRoute) {
            pendingDeepLinkRoute?.let { route ->
                navController.navigate(route)
                pendingDeepLinkRoute = null
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
            LoginScreen(
                onForgotPassword = { navController.navigate(ForgotPassword) },
            )
        }
        composable<ForgotPassword> {
            ForgotPasswordScreen(
                onBack = { navController.popBackStack() },
            )
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
                    navController.navigate(SuggestionList(defaultFilter = SuggestionFilter.INCOMING))
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
                // Phase 39.5 (ADR-015 §6) — Send Feedback routes to the
                // bug-report preview screen.
                onSendFeedback = { navController.navigate(BugReportPreview) },
                // Phase 41.3b (ADR-016 §5.1) — Subscribe-to-Pro routes to
                // the paywall sheet. Always-on, NOT beta-gated.
                onSubscribeToProClick = {
                    navController.navigate(
                        Paywall(trigger = PaywallTrigger.Settings),
                    )
                },
                // Phase 41.4 (ADR-016 §5.2) — Manage Symbol Packs routes
                // to the pack-management screen. Always-on, NOT beta-gated.
                onManagePacksClick = { navController.navigate(PackManagement) },
                // Phase 26.5 (ADR-022 §6.4) — Security → 2-factor entry.
                onEnableMfaClick = { navController.navigate(MfaEnrollment) },
                // Phase 26.6 (ADR-022 §6.5) — Security → biometric entry.
                onBiometricSettingsClick = { navController.navigate(BiometricSettings) },
                // Phase 27.2 (ADR-023 §UX) — Danger Zone → Delete all
                // my data entry.
                onWipeDataClick = { navController.navigate(WipeDataConfirmPhrase) },
                // Phase 25.3 (ADR-024 §(e)) — Privacy → Connections.
                onConnectionsClick = { navController.navigate(Connections) },
            )
        }
        composable<Connections> {
            ConnectionsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<WipeDataConfirmPhrase> {
            WipeDataConfirmPhraseScreen(
                // "Keep my data" / system-back on Modal / Settings
                // back button → pop directly to Settings.
                onCancel = { navController.popBackStack() },
                // On successful wipe: pop back to Pattern Library. The
                // WipeCompletionNotifier emission inside the screen
                // already flipped the banner; we just need to navigate
                // there. `popUpTo(PatternLibrary)` lands the user on
                // Pattern Library; if Pattern Library wasn't on the
                // stack, `navigate(PatternLibrary)` (saveState=true)
                // recreates it.
                onWipeCompleted = {
                    navController.navigate(PatternLibrary) {
                        popUpTo(WipeDataConfirmPhrase) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<BiometricSettings> {
            io.github.b150005.skeinly.ui.biometric.BiometricSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<OAuthProfileSetup> { entry ->
            val route: OAuthProfileSetup = entry.toRoute()
            val metadata =
                io.github.b150005.skeinly.domain.model.OAuthOnboardingMetadata(
                    displayName = route.displayName,
                    pictureUrl = route.pictureUrl,
                    // The gate doesn't need the provider discriminator
                    // post-mount (the discriminator was only used by
                    // NavGraph to detect "OAuth, not email/password");
                    // default to Email so the data class invariant is
                    // satisfied. The screen never reads this field.
                    primaryProvider = io.github.b150005.skeinly.domain.model.AuthProviderKind.Email,
                )
            OAuthProfileSetupScreen(
                metadata = metadata,
                onCompleted = {
                    navController.navigate(ProjectList) {
                        popUpTo(route) { inclusive = true }
                    }
                    // Replay deferred deep link on gate completion.
                    pendingDeepLinkRoute?.let { dl ->
                        navController.navigate(dl)
                        pendingDeepLinkRoute = null
                    }
                },
            )
        }
        composable<MfaEnrollment> {
            MfaEnrollmentScreen(
                onBack = { navController.popBackStack() },
                onCompleted = {
                    // Pop back to Settings; the observeMfaStatus flow
                    // already updated the Settings row to "Enabled".
                    navController.popBackStack()
                },
            )
        }
        composable<MfaChallenge> {
            MfaChallengeScreen(
                // No-op forwarding: NavGraph's authState observer routes
                // off this screen on verify-success (AuthState transitions
                // to Authenticated → popUpTo(MfaChallenge) inclusive).
                // The ViewModel surface keeps the callback for tests that
                // mount the screen without NavGraph.
                onCompleted = { /* handled by authState observer */ },
            )
        }
        composable<PackManagement> {
            PackManagementScreen(
                onBack = { navController.popBackStack() },
                // Phase 41.4 (ADR-016 §5.2) — locked-pack tap routes to
                // the paywall with a Settings-equivalent entry trigger.
                // Reusing PaywallTrigger.Settings keeps the funnel
                // analytics simple; if pack-management conversions need
                // their own discriminator, add a new PaywallTrigger
                // variant in a follow-up.
                onUnlockWithPro = {
                    navController.navigate(
                        Paywall(trigger = PaywallTrigger.Settings),
                    )
                },
            )
        }
        composable<BugReportPreview> {
            BugReportPreviewScreen(
                onCancel = { navController.popBackStack() },
            )
        }
        composable<Paywall> { backStackEntry ->
            val route = backStackEntry.toRoute<Paywall>()
            // Phase 41.3b (ADR-016 §5.1) — paywall sheet rendered as a
            // ModalBottomSheet. Result handling: the host pops the route
            // when the sheet dismisses; the success message is surfaced
            // by the destination the user lands on. For Settings entry
            // we just pop — no toast on the Settings screen yet (the
            // user knows what they did; the paywall already showed a
            // success state before dismissing). Future entries (chart
            // editor lock badge, ?-cell tap) may attach a toast on their
            // host screen via shared snackbar infrastructure.
            PaywallScreen(
                trigger = route.trigger,
                onDismiss = { navController.popBackStack() },
                onPaywallResult = { _: PaywallResult ->
                    // No-op for the Settings entry. Phase 41.4 wires the
                    // chart-editor entry to surface a contextual toast
                    // here.
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
                            SuggestionFilter.OUTGOING
                        } else {
                            SuggestionFilter.INCOMING
                        }
                    navController.navigate(SuggestionList(defaultFilter = filter))
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
                onOpenSuggestionNavigate = { prId ->
                    navController.navigate(SuggestionDetail(prId = prId))
                },
            )
        }
        composable<ChartEditor> { backStackEntry ->
            val route = backStackEntry.toRoute<ChartEditor>()
            ChartEditorScreen(
                patternId = route.patternId,
                onBack = { navController.popBackStack() },
                // Phase 41.3b (ADR-016 §5.1) — Pro symbol selection without
                // entitlement routes to the paywall sheet.
                onPaywallRequested = {
                    navController.navigate(
                        Paywall(trigger = PaywallTrigger.AutoLockInEditor),
                    )
                },
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
                // view in `ChartComparisonScreen`.
                onRevisionClick = { baseRevisionId, targetRevisionId ->
                    navController.navigate(
                        ChartComparison(baseRevisionId = baseRevisionId, targetRevisionId = targetRevisionId),
                    )
                },
            )
        }
        composable<ChartComparison> { backStackEntry ->
            val route = backStackEntry.toRoute<ChartComparison>()
            ChartComparisonScreen(
                baseRevisionId = route.baseRevisionId,
                targetRevisionId = route.targetRevisionId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<SuggestionList> { backStackEntry ->
            val route = backStackEntry.toRoute<SuggestionList>()
            SuggestionListScreen(
                defaultFilter = route.defaultFilter,
                onBack = { navController.popBackStack() },
                // Phase 38.3 routes a row tap to the PR detail surface.
                onSuggestionClick = { prId ->
                    navController.navigate(SuggestionDetail(prId = prId))
                },
            )
        }
        composable<SuggestionDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<SuggestionDetail>()
            SuggestionDetailScreen(
                prId = route.prId,
                onBack = { navController.popBackStack() },
                // Phase 38.3 (ADR-014 §6): PR diff preview taps route to the
                // existing Phase 37.3 ChartComparisonScreen between the snapshot
                // ancestor and the source tip captured at PR-open time.
                onOpenDiff = { baseRevisionId, targetRevisionId ->
                    navController.navigate(
                        ChartComparison(
                            baseRevisionId = baseRevisionId,
                            targetRevisionId = targetRevisionId,
                        ),
                    )
                },
                // Phase 38.4: ConflictDetector found conflicts on confirm-merge;
                // push the resolution screen. Auto-clean merges land directly
                // via the RPC and surface the success Snackbar in-place
                // (PrMerged nav event handled by the screen's own collector).
                onResolveConflicts = { prId ->
                    navController.navigate(ChartConflictResolution(prId = prId))
                },
            )
        }
        composable<ChartConflictResolution> { backStackEntry ->
            val route = backStackEntry.toRoute<ChartConflictResolution>()
            ChartConflictResolutionScreen(
                prId = route.prId,
                onBack = { navController.popBackStack() },
                // On successful merge, pop back to the PR detail screen so
                // the user lands on the now-merged PR (status flips through
                // Realtime). The success Snackbar shows on the resolution
                // screen briefly before the pop.
                onMerged = { navController.popBackStack() },
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

/**
 * Phase 24.5 (ADR-017 §3.8) — parse a host-relative push-route string
 * into a typed Compose Navigation route object.
 *
 * The Phase 24 wave only emits `pull-request/<prId>`; future event
 * sources extend the prefix table. Returns `null` for unknown /
 * malformed routes so [SkeinlyNavHost]'s [LaunchedEffect] silently
 * drops a hostile or stale push without any user-visible navigation
 * glitch. (Hostile = a Phase 24+ push arriving on an older client
 * that doesn't recognize the route shape; stale = the OS delivered
 * a cached push after the route scheme changed.)
 *
 * Internal visibility so unit tests in `commonTest` can reach it
 * without exposing the helper to consumers of the navigation surface.
 */
internal fun parsePushRoute(raw: String): Any? {
    val suggestionPrefix = "pull-request/"
    if (raw.startsWith(suggestionPrefix)) {
        val prId = raw.substring(suggestionPrefix.length)
        if (prId.isEmpty()) return null
        return SuggestionDetail(prId = prId)
    }
    return null
}

/**
 * Phase 39 (W3 / 2026-05-11) — parse an external Universal Link (iOS)
 * or Android App Link URL into a typed Compose Navigation route.
 *
 * Recognized URL family (alpha scope; expand post-alpha as new public
 * URL surfaces are added):
 *
 *   https://b150005.github.io/skeinly/patterns/shared/<token>
 *     → [SharedContent](token = `<token>`, shareId = null)
 *     where `<token>` is a UUID v4 share token.
 *
 *   https://b150005.github.io/skeinly/pull-requests/<prId>
 *     → [SuggestionDetail](prId = `<prId>`)
 *
 * Returns `null` for any URL that:
 *   - Is not under https://b150005.github.io/skeinly/ — defensive even
 *     though Manifest intent-filter + AASA `components` already restrict
 *     the surface; an attacker who tricks the OS into sending an
 *     out-of-spec URL still cannot navigate the app.
 *   - Has an unknown route shape (forward-compat for new URL families
 *     added in later phases).
 *   - Has an empty identifier segment.
 *   - Has an invalid share-token shape (UUID v4 only — guards against
 *     hand-crafted URLs reaching the SharedContent fetch with garbage
 *     tokens).
 *
 * Query string and URL fragment are stripped before route matching so
 * adversarial trailing data (`?utm_source=...`, `#anchor`) does not
 * corrupt the identifier extraction.
 *
 * Internal visibility so unit tests in `commonTest` can reach it
 * without exposing the helper to consumers of the navigation surface.
 */
internal fun parseExternalRoute(url: String): Any? {
    val expectedPrefix = "https://b150005.github.io/skeinly/"
    if (!url.startsWith(expectedPrefix)) return null
    // Strip query and fragment so adversarial appended data cannot
    // corrupt the identifier extraction below. `substringBefore` returns
    // the full string when the delimiter is absent, so this is a no-op
    // for clean URLs.
    val pathPart =
        url
            .substring(expectedPrefix.length)
            .substringBefore('?')
            .substringBefore('#')

    val sharedPatternPrefix = "patterns/shared/"
    if (pathPart.startsWith(sharedPatternPrefix)) {
        val token = pathPart.substring(sharedPatternPrefix.length).substringBefore('/')
        if (token.isEmpty()) return null
        if (!isShareTokenShape(token)) return null
        return SharedContent(token = token, shareId = null)
    }

    val pullRequestPrefix = "pull-requests/"
    if (pathPart.startsWith(pullRequestPrefix)) {
        val prId = pathPart.substring(pullRequestPrefix.length).substringBefore('/')
        if (prId.isEmpty()) return null
        return SuggestionDetail(prId = prId)
    }

    return null
}

/**
 * UUID v4 lowercase-hex shape check, mirroring the regex used in
 * `iosApp/iosApp/Core/DeepLinkValidator.swift` (iOS). Single source of
 * truth for share-token format on the Kotlin side — if the format
 * ever changes (e.g. to base62 or hyphen-less), update both in sync.
 */
private val SHARE_TOKEN_REGEX =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private fun isShareTokenShape(token: String): Boolean = SHARE_TOKEN_REGEX.matches(token)
