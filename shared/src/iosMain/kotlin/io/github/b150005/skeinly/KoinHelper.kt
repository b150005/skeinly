package io.github.b150005.skeinly

import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.EventRingBuffer
import io.github.b150005.skeinly.data.analytics.PaywallTrigger
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferences
import io.github.b150005.skeinly.data.remote.SupabaseConfig
import io.github.b150005.skeinly.data.remote.isConfigured
import io.github.b150005.skeinly.di.applicationScopeQualifier
import io.github.b150005.skeinly.di.platformModule
import io.github.b150005.skeinly.di.sharedModules
import io.github.b150005.skeinly.domain.model.CommentTargetType
import io.github.b150005.skeinly.domain.model.UgcTargetType
import io.github.b150005.skeinly.domain.symbol.SymbolCatalog
import io.github.b150005.skeinly.domain.usecase.GetOnboardingCompletedUseCase
import io.github.b150005.skeinly.notifications.PushTokenRegistrar
import io.github.b150005.skeinly.ui.activityfeed.ActivityFeedViewModel
import io.github.b150005.skeinly.ui.auth.AuthViewModel
import io.github.b150005.skeinly.ui.bugreport.BugReportPreviewViewModel
import io.github.b150005.skeinly.ui.chart.ChartComparisonViewModel
import io.github.b150005.skeinly.ui.chart.ChartEditorViewModel
import io.github.b150005.skeinly.ui.chart.ChartHistoryViewModel
import io.github.b150005.skeinly.ui.chart.ChartVariationPickerViewModel
import io.github.b150005.skeinly.ui.chart.ChartViewerViewModel
import io.github.b150005.skeinly.ui.comments.CommentSectionViewModel
import io.github.b150005.skeinly.ui.moderation.BlockUserNavEvent
import io.github.b150005.skeinly.ui.moderation.BlockUserState
import io.github.b150005.skeinly.ui.moderation.BlockUserViewModel
import io.github.b150005.skeinly.ui.moderation.BlockedUsersState
import io.github.b150005.skeinly.ui.moderation.BlockedUsersViewModel
import io.github.b150005.skeinly.ui.moderation.UgcReportNavEvent
import io.github.b150005.skeinly.ui.moderation.UgcReportState
import io.github.b150005.skeinly.ui.moderation.UgcReportViewModel
import io.github.b150005.skeinly.ui.notifications.NotificationPermissionState
import io.github.b150005.skeinly.ui.notifications.NotificationPermissionViewModel
import io.github.b150005.skeinly.ui.onboarding.OnboardingViewModel
import io.github.b150005.skeinly.ui.packmanagement.PackManagementViewModel
import io.github.b150005.skeinly.ui.patternedit.PatternEditViewModel
import io.github.b150005.skeinly.ui.patternlibrary.PatternLibraryViewModel
import io.github.b150005.skeinly.ui.paywall.PaywallNavEvent
import io.github.b150005.skeinly.ui.paywall.PaywallState
import io.github.b150005.skeinly.ui.paywall.PaywallViewModel
import io.github.b150005.skeinly.ui.profile.ProfileViewModel
import io.github.b150005.skeinly.ui.projectdetail.ProjectDetailViewModel
import io.github.b150005.skeinly.ui.projectlist.ProjectListViewModel
import io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionViewModel
import io.github.b150005.skeinly.ui.pullrequest.SuggestionDetailViewModel
import io.github.b150005.skeinly.ui.pullrequest.SuggestionFilter
import io.github.b150005.skeinly.ui.pullrequest.SuggestionListViewModel
import io.github.b150005.skeinly.ui.settings.DataExportState
import io.github.b150005.skeinly.ui.settings.DataExportViewModel
import io.github.b150005.skeinly.ui.settings.SettingsViewModel
import io.github.b150005.skeinly.ui.settings.WipeDataNavEvent
import io.github.b150005.skeinly.ui.settings.WipeDataState
import io.github.b150005.skeinly.ui.settings.WipeDataViewModel
import io.github.b150005.skeinly.ui.sharedcontent.SharedContentViewModel
import io.github.b150005.skeinly.ui.sharedwithme.SharedWithMeViewModel
import io.github.b150005.skeinly.ui.symbol.SymbolGalleryViewModel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform
import platform.Foundation.NSLog

fun initKoin() {
    startKoin {
        modules(listOf(platformModule) + sharedModules)
    }
    NSLog("[Skeinly] Koin initialized — Supabase configured: %d", if (SupabaseConfig.isConfigured) 1 else 0)
}

// ViewModel accessors for Swift interop

fun getOnboardingViewModel(): OnboardingViewModel = KoinPlatform.getKoin().get()

fun isOnboardingCompleted(): Boolean {
    val useCase: GetOnboardingCompletedUseCase = KoinPlatform.getKoin().get()
    return useCase()
}

fun getAuthViewModel(): AuthViewModel = KoinPlatform.getKoin().get()

/**
 * Phase 26.1 (ADR-022 §6.1) — bridges the SwiftUI `SignInWithAppleButton`
 * completion handler into the shared [AuthViewModel].
 *
 * SwiftUI call sites:
 *
 * ```swift
 * SignInWithAppleButton(.signIn,
 *     onRequest: { request in
 *         request.requestedScopes = [.fullName, .email]
 *         request.nonce = AppleSignInBridge.shared.beginRequest().sha256
 *     },
 *     onCompletion: { result in
 *         AppleSignInBridge.shared.handleCompletion(result) { idToken, nonce in
 *             KoinHelperKt.signInWithAppleIdToken(idToken: idToken, nonce: nonce)
 *         }
 *     }
 * )
 * ```
 *
 * The [nonce] argument MUST be the plaintext value (NOT the SHA-256
 * digest sent in the request) — Apple includes the SHA-256 of the
 * plaintext in the ID token's `nonce` claim, and Supabase verifies the
 * match server-side. Sending the digest here would cause Supabase to
 * reject with `nonce_mismatch`.
 *
 * Idempotent / safe to call from the main thread — the shared
 * AuthViewModel runs its work on `viewModelScope`, so this just
 * publishes an event.
 */
fun signInWithAppleIdToken(
    idToken: String,
    nonce: String,
) {
    val viewModel: AuthViewModel = KoinPlatform.getKoin().get()
    viewModel.onEvent(
        io.github.b150005.skeinly.ui.auth.AuthEvent.SignInWithAppleIdToken(
            idToken = idToken,
            nonce = nonce,
        ),
    )
}

/**
 * Phase 26.3 (ADR-022 §6.2) — bridges the SwiftUI `GoogleSignInBridge`
 * completion handler into the shared [AuthViewModel].
 *
 * SwiftUI call sites:
 *
 * ```swift
 * Button(action: {
 *     GoogleSignInBridge.shared.signIn { idToken, nonce in
 *         KoinHelperKt.signInWithGoogleIdToken(idToken: idToken, nonce: nonce)
 *     }
 * }) { ... }
 * ```
 *
 * The [nonce] argument is intentionally nullable. The iOS GIDSignIn SDK
 * does not stamp a nonce by default (parity with Android's
 * `GetGoogleIdOption.Builder()` which Phase 26.2 also wires without
 * `setNonce(...)`). Supabase accepts nonceless Google ID tokens; if a
 * future replay-risk analysis surfaces, both platforms can wire a
 * matched plaintext+digest nonce at the same time.
 *
 * Idempotent / safe to call from the main thread — the shared
 * AuthViewModel runs its work on `viewModelScope`, so this just
 * publishes an event.
 */
fun signInWithGoogleIdToken(
    idToken: String,
    nonce: String?,
) {
    val viewModel: AuthViewModel = KoinPlatform.getKoin().get()
    viewModel.onEvent(
        io.github.b150005.skeinly.ui.auth.AuthEvent.SignInWithGoogleIdToken(
            idToken = idToken,
            nonce = nonce,
        ),
    )
}

fun getProjectListViewModel(): ProjectListViewModel = KoinPlatform.getKoin().get()

fun getProjectDetailViewModel(projectId: String): ProjectDetailViewModel = KoinPlatform.getKoin().get { parametersOf(projectId) }

fun getProfileViewModel(): ProfileViewModel = KoinPlatform.getKoin().get()

fun getSettingsViewModel(): SettingsViewModel = KoinPlatform.getKoin().get()

// Phase F2 — analytics preference accessor for the iOS PostHog init path.
// iOSApp.swift reads `analyticsOptIn.value` synchronously at startup and
// observes the StateFlow via `wrapAnalyticsOptInFlow` to react to runtime
// toggles.
fun getAnalyticsPreferences(): AnalyticsPreferences = KoinPlatform.getKoin().get()

/**
 * Phase 39.5 (ADR-015 §1) — synchronous accessor for the current opt-in
 * value, read by the iOS shake-gesture handler in `AppRootView` to gate
 * `dispatchTouchEvent`-equivalent navigation. Avoids the Kotlin/Native
 * `Boolean` → `KotlinBoolean` bridging surface that would otherwise force
 * Swift call sites through `.boolValue`.
 */
fun analyticsOptInValue(): Boolean {
    val prefs: AnalyticsPreferences = KoinPlatform.getKoin().get()
    return prefs.analyticsOptIn.value
}

fun wrapAnalyticsOptInFlow(flow: kotlinx.coroutines.flow.StateFlow<Boolean>): FlowWrapper<kotlin.Boolean> = FlowWrapper(flow)

// Phase F.3 — analytics event bridge. Application layer (iOSApp.swift)
// collects this Flow and forwards each AnalyticsEvent to
// PostHogSDK.shared.capture(...). The tracker itself gates emissions on
// the opt-in preference; this wrapper just adapts the Kotlin SharedFlow
// to a Swift-consumable EventFlowWrapper.
fun getAnalyticsTracker(): AnalyticsTracker = KoinPlatform.getKoin().get()

fun wrapAnalyticsEventsFlow(flow: kotlinx.coroutines.flow.SharedFlow<AnalyticsEvent>): EventFlowWrapper<AnalyticsEvent> =
    EventFlowWrapper(flow)

// Phase 39.3 (ADR-015 §6) — bug-report event trail accessor. iOSApp.swift
// resolves this once at init and stores the reference; Phase 39.5 calls
// `snapshot()` from the bug-report submission flow to attach the last
// 10 events to the GitHub Issue body.
fun getEventRingBuffer(): EventRingBuffer = KoinPlatform.getKoin().get()

/**
 * Phase 39.3 (ADR-015 §6) — kicks off the [EventRingBuffer] collector on
 * the shared [applicationScopeQualifier] CoroutineScope. iOS-only entry
 * point: Swift `iOSApp.init` calls this exactly once at app init time, so
 * the bug-report trail is populated for the entire process lifetime.
 *
 * Android has its own `applicationScope` field on `SkeinlyApplication`
 * (separate from Koin's `applicationScopeQualifier` for historical
 * reasons) and calls `EventRingBuffer.start(applicationScope)` directly
 * from `onCreate`.
 *
 * Idempotent — [EventRingBuffer.start] no-ops on a second call.
 */
fun startEventRingBuffer() {
    val buffer: EventRingBuffer = KoinPlatform.getKoin().get()
    val scope: CoroutineScope = KoinPlatform.getKoin().get(applicationScopeQualifier)
    buffer.start(scope)
}

/**
 * Phase 24.2e (ADR-017 §3.5) — bridges the Swift `AppDelegate`
 * APNs callback into the shared `PushTokenRegistrar`. Swift call sites:
 *
 * ```swift
 * // didRegisterForRemoteNotificationsWithDeviceToken
 * KoinHelperKt.handleApnsTokenReceived(token: hex)
 * // didFailToRegisterForRemoteNotificationsWithError
 * KoinHelperKt.handleApnsTokenReceived(token: nil)
 * ```
 *
 * Resolves the Koin-singleton `PushTokenRegistrar` and forwards the
 * APNs token (or null on failure) into its in-flight
 * `Channel<String?>`. Idempotent on a stale callback — a second call
 * after the consumer already received its value replaces the
 * CONFLATED slot with no observable effect.
 */
fun handleApnsTokenReceived(token: String?) {
    val registrar: PushTokenRegistrar = KoinPlatform.getKoin().get()
    registrar.handleApnsToken(token)
}

/**
 * Phase 41.3 (ADR-016 §6 §41.3) — bootstrap RevenueCat IAP SDK from
 * iOS Swift call site. iOSApp.swift invokes this once after
 * `KoinHelperKt.doInitKoin()` completes. Idempotent and no-ops when
 * the iOS Info.plist `REVENUECAT_API_KEY` is empty (local-dev / CI
 * without GitHub Secret REVENUECAT_API_KEY_IOS wired).
 *
 * `verbose` is exposed as a parameter so iOS init can gate on
 * `BuildFlags.isBeta` from Swift directly (avoiding a Kotlin-side
 * runtime read of an iOS-only Info.plist key from the bootstrap path).
 */
fun configureRevenueCat(verbose: Boolean) {
    io.github.b150005.skeinly.data.subscription.RevenueCatBootstrap
        .configure(verbose = verbose)
}

/**
 * Phase 39 closed beta prep — start the auth → RevenueCat identity bridge.
 *
 * Called from `iOSApp.init` AFTER [configureRevenueCat] and Koin init have
 * completed. The bridge subscribes to `AuthRepository.observeAuthState()`
 * via the application-scope coroutine `CoroutineScope` registered in
 * [io.github.b150005.skeinly.di.applicationScopeQualifier], reacting to
 * `Authenticated` / `Unauthenticated` transitions by calling
 * `Purchases.sharedInstance.logIn(userId)` / `logOut()`.
 *
 * Returns Unit (no Job exposed to Swift) — the bridge naturally lives for
 * the application lifetime; explicit cancellation has no use case on iOS.
 *
 * See `RevenueCatAuthBridge.kt` KDoc for the full rationale.
 */
fun startRevenueCatAuthBridge() {
    val koin = KoinPlatform.getKoin()
    val scope =
        koin.get<kotlinx.coroutines.CoroutineScope>(
            io.github.b150005.skeinly.di.applicationScopeQualifier,
        )
    val authRepository = koin.get<io.github.b150005.skeinly.domain.repository.AuthRepository>()
    val revenueCatService = koin.get<io.github.b150005.skeinly.domain.subscription.RevenueCatService>()
    io.github.b150005.skeinly.data.subscription.startRevenueCatAuthBridge(
        scope = scope,
        authRepository = authRepository,
        revenueCatService = revenueCatService,
    )
}

/**
 * Phase 26.6 (ADR-022 §6.5) — bridges process-level
 * `UIApplicationWillEnterForegroundNotification` +
 * `UIApplicationDidEnterBackgroundNotification` to the shared
 * [io.github.b150005.skeinly.biometric.BiometricGuardian]. Called once
 * from `iOSApp.init` after `initKoin` returns.
 *
 * On Failed/Cancelled outcome the supplied closure invokes the
 * SignOut use case (per ADR §3.8). Wiring the sign-out call into the
 * bridge — rather than returning a SharedFlow for the caller to
 * collect — closes the security loop by construction.
 *
 * The lifecycle bridge runs for the process lifetime; explicit
 * cancellation has no use case on iOS. Same shape as
 * [startRevenueCatAuthBridge] (Phase 39.0.1).
 */
fun startBiometricLifecycleBridge() {
    val koin = KoinPlatform.getKoin()
    val scope =
        koin.get<kotlinx.coroutines.CoroutineScope>(
            io.github.b150005.skeinly.di.applicationScopeQualifier,
        )
    val guardian = koin.get<io.github.b150005.skeinly.biometric.BiometricGuardian>()
    val lifecycleObserver = koin.get<io.github.b150005.skeinly.platform.AppLifecycleObserver>()
    val signOutUseCase =
        koin.get<io.github.b150005.skeinly.domain.usecase.SignOutUseCase>()
    io.github.b150005.skeinly.biometric.startBiometricLifecycleBridge(
        scope = scope,
        guardian = guardian,
        lifecycleObserver = lifecycleObserver,
        onResumeAuthInvalidated = {
            // Best-effort sign-out: a Failure inside SignOut still
            // emits a non-Authenticated AuthState through the
            // observer flow because the use case surfaces the local
            // session-clear regardless of remote success.
            signOutUseCase()
        },
    )
}

fun getActivityFeedViewModel(): ActivityFeedViewModel = KoinPlatform.getKoin().get()

fun getSharedWithMeViewModel(): SharedWithMeViewModel = KoinPlatform.getKoin().get()

fun getCommentSectionViewModel(
    targetType: CommentTargetType,
    targetId: String,
): CommentSectionViewModel = KoinPlatform.getKoin().get { parametersOf(targetType, targetId) }

fun getDiscoveryViewModel(): io.github.b150005.skeinly.ui.discovery.DiscoveryViewModel = KoinPlatform.getKoin().get()

fun getPatternLibraryViewModel(): PatternLibraryViewModel = KoinPlatform.getKoin().get()

fun getPatternEditViewModel(patternId: String?): PatternEditViewModel = KoinPlatform.getKoin().get { parametersOf(patternId) }

fun getSharedContentViewModel(
    token: String?,
    shareId: String?,
): SharedContentViewModel = KoinPlatform.getKoin().get { parametersOf(token, shareId) }

fun getChartViewerViewModel(
    patternId: String,
    projectId: String?,
): ChartViewerViewModel = KoinPlatform.getKoin().get { parametersOf(patternId, projectId) }

fun getChartEditorViewModel(patternId: String): ChartEditorViewModel = KoinPlatform.getKoin().get { parametersOf(patternId) }

fun getChartHistoryViewModel(patternId: String): ChartHistoryViewModel = KoinPlatform.getKoin().get { parametersOf(patternId) }

fun getChartComparisonViewModel(
    baseRevisionId: String?,
    targetRevisionId: String,
): ChartComparisonViewModel = KoinPlatform.getKoin().get { parametersOf(baseRevisionId, targetRevisionId) }

fun getChartVariationPickerViewModel(patternId: String): ChartVariationPickerViewModel =
    KoinPlatform.getKoin().get {
        parametersOf(patternId)
    }

fun getSuggestionListViewModel(defaultFilter: SuggestionFilter): SuggestionListViewModel =
    KoinPlatform.getKoin().get { parametersOf(defaultFilter) }

fun getSuggestionDetailViewModel(prId: String): SuggestionDetailViewModel = KoinPlatform.getKoin().get { parametersOf(prId) }

fun getChartConflictResolutionViewModel(prId: String): ChartConflictResolutionViewModel = KoinPlatform.getKoin().get { parametersOf(prId) }

fun getSymbolCatalog(): SymbolCatalog = KoinPlatform.getKoin().get()

// Phase 36.4.1b: exposes the chart repository directly so the iOS Discovery
// thumbnail can fetch a chart on demand without a dedicated ViewModel layer
// (the fetch is a one-shot read with no observable state — wrapping it in a
// Kotlin ViewModel would be ceremony).
fun getChartRepository(): io.github.b150005.skeinly.domain.repository.ChartRepository = KoinPlatform.getKoin().get()

// Type-safe FlowWrapper factories for Swift (eliminates as! force-casts)

fun wrapOnboardingState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.onboarding.OnboardingState>,
): FlowWrapper<io.github.b150005.skeinly.ui.onboarding.OnboardingState> = FlowWrapper(flow)

fun wrapAuthState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.auth.AuthUiState>,
): FlowWrapper<io.github.b150005.skeinly.ui.auth.AuthUiState> = FlowWrapper(flow)

fun wrapForgotPasswordState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.auth.ForgotPasswordState>,
): FlowWrapper<io.github.b150005.skeinly.ui.auth.ForgotPasswordState> = FlowWrapper(flow)

fun getForgotPasswordViewModel(): io.github.b150005.skeinly.ui.auth.ForgotPasswordViewModel =
    org.koin.mp.KoinPlatform
        .getKoin()
        .get()

fun wrapSettingsToastEvents(
    flow: kotlinx.coroutines.flow.Flow<io.github.b150005.skeinly.ui.settings.SettingsToastEvent>,
): EventFlowWrapper<io.github.b150005.skeinly.ui.settings.SettingsToastEvent> = EventFlowWrapper(flow)

fun wrapProjectListState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.projectlist.ProjectListState>,
): FlowWrapper<io.github.b150005.skeinly.ui.projectlist.ProjectListState> = FlowWrapper(flow)

fun wrapProjectDetailState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.projectdetail.ProjectDetailState>,
): FlowWrapper<io.github.b150005.skeinly.ui.projectdetail.ProjectDetailState> = FlowWrapper(flow)

fun wrapProgressNotesState(
    flow: kotlinx.coroutines.flow.StateFlow<List<io.github.b150005.skeinly.domain.model.Progress>>,
): FlowWrapper<List<io.github.b150005.skeinly.domain.model.Progress>> = FlowWrapper(flow)

fun wrapSettingsState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.settings.SettingsState>,
): FlowWrapper<io.github.b150005.skeinly.ui.settings.SettingsState> = FlowWrapper(flow)

// Phase 39.5 (ADR-015 §6) — bug-report preview ViewModel + state wrapper.
fun getBugReportPreviewViewModel(): BugReportPreviewViewModel = KoinPlatform.getKoin().get()

fun wrapBugReportPreviewState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.bugreport.BugReportPreviewState>,
): FlowWrapper<io.github.b150005.skeinly.ui.bugreport.BugReportPreviewState> = FlowWrapper(flow)

// Phase 24.2c (ADR-017 §3.6) — push notification consent ViewModel +
// state wrapper. Drives the iOS Settings → Notifications row + the
// in-app pre-permission explainer sheet.
fun getNotificationPermissionViewModel(): NotificationPermissionViewModel = KoinPlatform.getKoin().get()

fun wrapNotificationPermissionState(
    flow: kotlinx.coroutines.flow.StateFlow<NotificationPermissionState>,
): FlowWrapper<NotificationPermissionState> = FlowWrapper(flow)

// Phase 41.3b (ADR-016 §5.1) — paywall ViewModel + state / nav-event
// wrappers. Parametric on `PaywallTrigger` so the entry point's
// discriminator threads through to PostHog funnel analytics.
fun getPaywallViewModel(trigger: PaywallTrigger): PaywallViewModel = KoinPlatform.getKoin().get { parametersOf(trigger) }

fun wrapPaywallState(flow: kotlinx.coroutines.flow.StateFlow<PaywallState>): FlowWrapper<PaywallState> = FlowWrapper(flow)

fun wrapPaywallNavEvents(flow: kotlinx.coroutines.flow.Flow<PaywallNavEvent>): EventFlowWrapper<PaywallNavEvent> = EventFlowWrapper(flow)

// Phase 41.4 (ADR-016 §5.2 §6 §41.4) — pack management ViewModel + state.
fun getPackManagementViewModel(): PackManagementViewModel = KoinPlatform.getKoin().get()

fun wrapPackManagementState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.packmanagement.PackManagementState>,
): FlowWrapper<io.github.b150005.skeinly.ui.packmanagement.PackManagementState> = FlowWrapper(flow)

// Phase 26.5 (ADR-022 §6.4) — MFA enrollment + challenge ViewModels +
// state wrappers. The SwiftUI side mirrors the Compose MfaEnrollmentScreen
// + MfaChallengeScreen and drives them through these helpers.
fun getMfaEnrollmentViewModel(): io.github.b150005.skeinly.ui.auth.MfaEnrollmentViewModel = KoinPlatform.getKoin().get()

fun wrapMfaEnrollmentState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.auth.MfaEnrollmentUiState>,
): FlowWrapper<io.github.b150005.skeinly.ui.auth.MfaEnrollmentUiState> = FlowWrapper(flow)

fun getMfaChallengeViewModel(): io.github.b150005.skeinly.ui.auth.MfaChallengeViewModel = KoinPlatform.getKoin().get()

fun wrapMfaChallengeState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.auth.MfaChallengeUiState>,
): FlowWrapper<io.github.b150005.skeinly.ui.auth.MfaChallengeUiState> = FlowWrapper(flow)

// Phase 26.6 (ADR-022 §6.5) — biometric settings ViewModel + state
// wrapper. The SwiftUI BiometricSettingsScreen mirrors the Compose
// surface and routes events through this VM.
fun getBiometricSettingsViewModel(): io.github.b150005.skeinly.ui.biometric.BiometricSettingsViewModel = KoinPlatform.getKoin().get()

fun wrapBiometricSettingsState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.biometric.BiometricSettingsState>,
): FlowWrapper<io.github.b150005.skeinly.ui.biometric.BiometricSettingsState> = FlowWrapper(flow)

// Phase 26.6 (ADR-022 §6.6) — post-OAuth profile setup ViewModel +
// state wrapper. SwiftUI mounts this screen after the first
// Authenticated transition for a user who has not yet completed the
// gate. The factory needs the [OAuthOnboardingMetadata] parameter, so
// the getter signature carries the seed values forward to Koin's
// parametric resolution.
fun getOAuthProfileSetupViewModel(
    displayName: String?,
    pictureUrl: String?,
): io.github.b150005.skeinly.ui.onboarding.OAuthProfileSetupViewModel {
    val metadata =
        io.github.b150005.skeinly.domain.model.OAuthOnboardingMetadata(
            displayName = displayName,
            pictureUrl = pictureUrl,
            // SwiftUI does not surface this discriminator; default to
            // Email so the data-class invariant is satisfied. The
            // screen never reads it.
            primaryProvider = io.github.b150005.skeinly.domain.model.AuthProviderKind.Email,
        )
    return KoinPlatform
        .getKoin()
        .get {
            org.koin.core.parameter
                .parametersOf(metadata)
        }
}

// Phase 27.2 (ADR-023 §UX) — data-wipe ViewModel accessor + state /
// nav-event wrappers. Mirrors the OAuthProfileSetup pattern: the
// `requiredPhrase` parameter threads through to Koin's parametric
// resolution. SwiftUI's WipeDataConfirmPhraseView resolves the
// locale-active phrase via `NSLocalizedString(...)` at view init time
// (matching the Compose `stringResource(Res.string.phrase_wipe_data_confirm)`
// snapshot semantics).
fun getWipeDataViewModel(requiredPhrase: String): WipeDataViewModel =
    KoinPlatform
        .getKoin()
        .get { parametersOf(requiredPhrase) }

fun wrapWipeDataState(flow: kotlinx.coroutines.flow.StateFlow<WipeDataState>): FlowWrapper<WipeDataState> = FlowWrapper(flow)

fun wrapWipeDataNavEvents(flow: kotlinx.coroutines.flow.Flow<WipeDataNavEvent>): EventFlowWrapper<WipeDataNavEvent> = EventFlowWrapper(flow)

// Pre-Phase-40 A20 Option B — in-app data-export VM bridge. No
// screen-time params (unlike WipeData's requiredPhrase) and no
// nav-event flow: the result (success summary / error) lives in
// DataExportState, and the share sheet is fired by the platform
// DataExportSaver from inside the VM, so the SwiftUI view only needs
// the state wrapper. Mirrors the BlockedUsers no-param bridge shape.
fun getDataExportViewModel(): DataExportViewModel = KoinPlatform.getKoin().get()

fun wrapDataExportState(flow: kotlinx.coroutines.flow.StateFlow<DataExportState>): FlowWrapper<DataExportState> = FlowWrapper(flow)

// Phase 39 (ADR-021 §D4) — UGC moderation VM bridges. Mirrors the
// WipeData/OAuthProfileSetup parametric-resolution pattern: screen-time
// params (targetType+targetId / blockedUserId) thread through Koin's
// `parametersOf`. SwiftUI presents the report sheet / block dialog
// keyed on the same ids the Compose side passes.
fun getUgcReportViewModel(
    targetType: UgcTargetType,
    targetId: String,
): UgcReportViewModel =
    KoinPlatform
        .getKoin()
        .get { parametersOf(targetType, targetId) }

fun wrapUgcReportState(flow: kotlinx.coroutines.flow.StateFlow<UgcReportState>): FlowWrapper<UgcReportState> = FlowWrapper(flow)

fun wrapUgcReportNavEvents(flow: kotlinx.coroutines.flow.Flow<UgcReportNavEvent>): EventFlowWrapper<UgcReportNavEvent> =
    EventFlowWrapper(flow)

fun getBlockUserViewModel(blockedUserId: String): BlockUserViewModel =
    KoinPlatform
        .getKoin()
        .get { parametersOf(blockedUserId) }

fun wrapBlockUserState(flow: kotlinx.coroutines.flow.StateFlow<BlockUserState>): FlowWrapper<BlockUserState> = FlowWrapper(flow)

fun wrapBlockUserNavEvents(flow: kotlinx.coroutines.flow.Flow<BlockUserNavEvent>): EventFlowWrapper<BlockUserNavEvent> =
    EventFlowWrapper(flow)

fun getBlockedUsersViewModel(): BlockedUsersViewModel = KoinPlatform.getKoin().get()

fun wrapBlockedUsersState(flow: kotlinx.coroutines.flow.StateFlow<BlockedUsersState>): FlowWrapper<BlockedUsersState> = FlowWrapper(flow)

// Phase 39 (ADR-021 §D4) — expose the report-category enum cases to
// Swift in a stable order for the picker (Kotlin enum `entries`
// ordinal == migration-031 CHECK list order). Swift iterates this
// instead of hardcoding the 7 cases so a future category addition is
// a single-source change.
fun ugcReportCategories(): List<io.github.b150005.skeinly.domain.model.UgcReportCategory> =
    io.github.b150005.skeinly.domain.model.UgcReportCategory.entries

// Phase 27.2 (ADR-023 §UX) — singleton notifier accessor for the iOS
// post-wipe nav handler. SwiftUI's WipeDataConfirmPhraseView calls
// `KoinHelperKt.notifyWipeCompleted()` after a successful submit so the
// PatternLibrary banner state flips. This mirrors the Compose-side
// `notifier.notify()` call in `WipeDataConfirmPhraseScreen`.
suspend fun notifyWipeCompleted() {
    val notifier: io.github.b150005.skeinly.data.wipe.WipeCompletionNotifier =
        KoinPlatform.getKoin().get()
    notifier.notify()
}

// Phase 25.3 (ADR-024 §(e)) — Connections (friends / pending / invite)
// ViewModel + state wrapper. SwiftUI's ConnectionsView mirrors the
// Compose ConnectionsScreen surface and routes events through this VM.
fun getConnectionsViewModel(): io.github.b150005.skeinly.ui.connections.ConnectionsViewModel =
    KoinPlatform
        .getKoin()
        .get()

fun wrapConnectionsState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.connections.ConnectionsState>,
): FlowWrapper<io.github.b150005.skeinly.ui.connections.ConnectionsState> = FlowWrapper(flow)

// Phase 25.4 (ADR-024 §Phase 25.4) — friend-invite redemption
// ViewModel + state wrapper. The `token` param threads through Koin's
// parametric resolution (null ⇒ code mode, non-null ⇒ token mode from
// a Universal Link tap). Mirrors the WipeData/OAuthProfileSetup
// parametric-accessor precedent.
fun getFriendInviteConfirmViewModel(token: String?): io.github.b150005.skeinly.ui.connections.FriendInviteConfirmViewModel =
    KoinPlatform
        .getKoin()
        .get { parametersOf(token) }

fun wrapFriendInviteConfirmState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.connections.FriendInviteConfirmState>,
): FlowWrapper<io.github.b150005.skeinly.ui.connections.FriendInviteConfirmState> = FlowWrapper(flow)

fun wrapOAuthProfileSetupState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.onboarding.OAuthProfileSetupState>,
): FlowWrapper<io.github.b150005.skeinly.ui.onboarding.OAuthProfileSetupState> = FlowWrapper(flow)

fun wrapOAuthProfileSetupNavEvents(
    flow: kotlinx.coroutines.flow.Flow<io.github.b150005.skeinly.ui.onboarding.OAuthProfileSetupNavEvent>,
): EventFlowWrapper<io.github.b150005.skeinly.ui.onboarding.OAuthProfileSetupNavEvent> = EventFlowWrapper(flow)

// Phase 26.6 (ADR-022 §6.6) — AuthRepository surface for the iOS
// AppRouter gate-decision logic. Returns null when no session is
// present OR the supabase client is unconfigured (local-only dev).
suspend fun fetchOAuthOnboardingMetadata(): io.github.b150005.skeinly.domain.model.OAuthOnboardingMetadata? {
    val repo: io.github.b150005.skeinly.domain.repository.AuthRepository =
        KoinPlatform.getKoin().get()
    return try {
        repo.getOAuthOnboardingMetadata()
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}

fun isOAuthProfileSetupGateCompleted(): Boolean {
    val prefs: io.github.b150005.skeinly.data.preferences.OAuthProfileSetupPreferences =
        KoinPlatform.getKoin().get()
    return prefs.isCompleted
}

fun wrapSettingsAccountDeletedFlow(flow: kotlinx.coroutines.flow.Flow<kotlin.Unit>): EventFlowWrapper<kotlin.Unit> = EventFlowWrapper(flow)

fun wrapProfileState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.profile.ProfileState>,
): FlowWrapper<io.github.b150005.skeinly.ui.profile.ProfileState> = FlowWrapper(flow)

fun wrapActivityFeedState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.activityfeed.ActivityFeedState>,
): FlowWrapper<io.github.b150005.skeinly.ui.activityfeed.ActivityFeedState> = FlowWrapper(flow)

fun wrapSharedWithMeState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.sharedwithme.SharedWithMeState>,
): FlowWrapper<io.github.b150005.skeinly.ui.sharedwithme.SharedWithMeState> = FlowWrapper(flow)

fun wrapDiscoveryState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.discovery.DiscoveryState>,
): FlowWrapper<io.github.b150005.skeinly.ui.discovery.DiscoveryState> = FlowWrapper(flow)

fun wrapDiscoveryForkedProjectFlow(
    flow: kotlinx.coroutines.flow.Flow<io.github.b150005.skeinly.ui.discovery.DiscoveryForkResult>,
): EventFlowWrapper<io.github.b150005.skeinly.ui.discovery.DiscoveryForkResult> = EventFlowWrapper(flow)

fun wrapPatternLibraryState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.patternlibrary.PatternLibraryState>,
): FlowWrapper<io.github.b150005.skeinly.ui.patternlibrary.PatternLibraryState> = FlowWrapper(flow)

fun wrapPatternEditState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.patternedit.PatternEditState>,
): FlowWrapper<io.github.b150005.skeinly.ui.patternedit.PatternEditState> = FlowWrapper(flow)

fun wrapPatternEditSaveSuccess(flow: kotlinx.coroutines.flow.Flow<kotlin.Unit>): EventFlowWrapper<kotlin.Unit> = EventFlowWrapper(flow)

fun wrapSharedContentState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.sharedcontent.SharedContentState>,
): FlowWrapper<io.github.b150005.skeinly.ui.sharedcontent.SharedContentState> = FlowWrapper(flow)

fun wrapCommentSectionState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.comments.CommentSectionState>,
): FlowWrapper<io.github.b150005.skeinly.ui.comments.CommentSectionState> = FlowWrapper(flow)

fun wrapForkedProjectIdFlow(flow: kotlinx.coroutines.flow.Flow<String>): EventFlowWrapper<String> = EventFlowWrapper(flow)

fun wrapChartViewerState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.chart.ChartViewerState>,
): FlowWrapper<io.github.b150005.skeinly.ui.chart.ChartViewerState> = FlowWrapper(flow)

fun wrapChartViewerNavEvents(
    flow: kotlinx.coroutines.flow.Flow<io.github.b150005.skeinly.ui.chart.ChartViewerNavEvent>,
): EventFlowWrapper<io.github.b150005.skeinly.ui.chart.ChartViewerNavEvent> = EventFlowWrapper(flow)

fun wrapChartEditorState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.chart.ChartEditorState>,
): FlowWrapper<io.github.b150005.skeinly.ui.chart.ChartEditorState> = FlowWrapper(flow)

fun wrapChartEditorSavedFlow(flow: kotlinx.coroutines.flow.Flow<Unit>): EventFlowWrapper<Unit> = EventFlowWrapper(flow)

// Phase 41.3b (ADR-016 §5.1) — paywall trigger flow from the chart
// editor. Surfaces when the user taps a Pro symbol they lack the
// entitlement for.
fun wrapChartEditorPaywallRequests(flow: kotlinx.coroutines.flow.Flow<Unit>): EventFlowWrapper<Unit> = EventFlowWrapper(flow)

fun wrapChartHistoryState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.chart.ChartHistoryState>,
): FlowWrapper<io.github.b150005.skeinly.ui.chart.ChartHistoryState> = FlowWrapper(flow)

fun wrapChartHistoryRevisionTaps(
    flow: kotlinx.coroutines.flow.Flow<io.github.b150005.skeinly.ui.chart.RevisionTapTarget>,
): EventFlowWrapper<io.github.b150005.skeinly.ui.chart.RevisionTapTarget> = EventFlowWrapper(flow)

fun wrapChartComparisonState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.chart.ChartComparisonState>,
): FlowWrapper<io.github.b150005.skeinly.ui.chart.ChartComparisonState> = FlowWrapper(flow)

fun wrapChartVariationPickerState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.chart.ChartVariationPickerState>,
): FlowWrapper<io.github.b150005.skeinly.ui.chart.ChartVariationPickerState> = FlowWrapper(flow)

fun wrapChartVariationSwitchedFlow(
    flow: kotlinx.coroutines.flow.Flow<io.github.b150005.skeinly.ui.chart.BranchSwitchedEvent>,
): EventFlowWrapper<io.github.b150005.skeinly.ui.chart.BranchSwitchedEvent> = EventFlowWrapper(flow)

fun wrapSuggestionListState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.pullrequest.SuggestionListState>,
): FlowWrapper<io.github.b150005.skeinly.ui.pullrequest.SuggestionListState> = FlowWrapper(flow)

fun wrapSuggestionDetailState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.pullrequest.SuggestionDetailState>,
): FlowWrapper<io.github.b150005.skeinly.ui.pullrequest.SuggestionDetailState> = FlowWrapper(flow)

fun wrapSuggestionDetailNavEvents(
    flow: kotlinx.coroutines.flow.Flow<io.github.b150005.skeinly.ui.pullrequest.SuggestionDetailNavEvent>,
): EventFlowWrapper<io.github.b150005.skeinly.ui.pullrequest.SuggestionDetailNavEvent> = EventFlowWrapper(flow)

fun wrapChartConflictResolutionState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionState>,
): FlowWrapper<io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionState> = FlowWrapper(flow)

fun wrapChartConflictResolutionNavEvents(
    flow: kotlinx.coroutines.flow.Flow<io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionNavEvent>,
): EventFlowWrapper<io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionNavEvent> = EventFlowWrapper(flow)

fun getSymbolGalleryViewModel(): SymbolGalleryViewModel = KoinPlatform.getKoin().get()

/**
 * Pre-alpha A30 — invokes the platform's subscription-management deep link
 * from SwiftUI. Bridges through Koin instead of receiving a Swift-side
 * dependency injection so the SwiftUI call site stays a one-liner.
 * Fire-and-forget: no error handling required — failure on the Kotlin
 * side is already swallowed in
 * [io.github.b150005.skeinly.platform.SubscriptionManagementLauncher].
 */
fun openSubscriptionManagement() {
    val launcher: io.github.b150005.skeinly.platform.SubscriptionManagementLauncher =
        KoinPlatform.getKoin().get()
    launcher.open()
}

/**
 * Pre-alpha A34 — opens the mailto: support composer from SwiftUI.
 * Bridges through Koin so the SwiftUI call site stays a one-liner.
 * The DeviceContextProvider for the pre-fill is also resolved through
 * Koin to keep the diagnostic context current at invocation time.
 */
fun openSupportEmail() {
    val launcher: io.github.b150005.skeinly.platform.SupportContactLauncher =
        KoinPlatform.getKoin().get()
    val deviceContext: io.github.b150005.skeinly.platform.DeviceContextProvider =
        KoinPlatform.getKoin().get()
    launcher.openSupportEmail(deviceContext)
}

fun wrapSymbolGalleryState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.symbol.SymbolGalleryState>,
): FlowWrapper<io.github.b150005.skeinly.ui.symbol.SymbolGalleryState> = FlowWrapper(flow)

@OptIn(ExperimentalForeignApi::class)
fun nsDataToByteArray(data: platform.Foundation.NSData): ByteArray {
    val size = data.length.toInt()
    val byteArray = ByteArray(size)
    if (size > 0) {
        byteArray.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
    }
    return byteArray
}
