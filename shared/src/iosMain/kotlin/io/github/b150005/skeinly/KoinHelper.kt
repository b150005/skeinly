package io.github.b150005.skeinly

import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.EventRingBuffer
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferences
import io.github.b150005.skeinly.data.remote.SupabaseConfig
import io.github.b150005.skeinly.data.remote.isConfigured
import io.github.b150005.skeinly.di.applicationScopeQualifier
import io.github.b150005.skeinly.di.platformModule
import io.github.b150005.skeinly.di.sharedModules
import io.github.b150005.skeinly.domain.model.CommentTargetType
import io.github.b150005.skeinly.domain.symbol.SymbolCatalog
import io.github.b150005.skeinly.domain.usecase.GetOnboardingCompletedUseCase
import io.github.b150005.skeinly.ui.activityfeed.ActivityFeedViewModel
import io.github.b150005.skeinly.ui.auth.AuthViewModel
import io.github.b150005.skeinly.ui.bugreport.BugReportPreviewViewModel
import io.github.b150005.skeinly.ui.chart.ChartBranchPickerViewModel
import io.github.b150005.skeinly.ui.chart.ChartDiffViewModel
import io.github.b150005.skeinly.ui.chart.ChartEditorViewModel
import io.github.b150005.skeinly.ui.chart.ChartHistoryViewModel
import io.github.b150005.skeinly.ui.chart.ChartViewerViewModel
import io.github.b150005.skeinly.ui.comments.CommentSectionViewModel
import io.github.b150005.skeinly.ui.onboarding.OnboardingViewModel
import io.github.b150005.skeinly.ui.patternedit.PatternEditViewModel
import io.github.b150005.skeinly.ui.patternlibrary.PatternLibraryViewModel
import io.github.b150005.skeinly.ui.profile.ProfileViewModel
import io.github.b150005.skeinly.ui.projectdetail.ProjectDetailViewModel
import io.github.b150005.skeinly.ui.projectlist.ProjectListViewModel
import io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionViewModel
import io.github.b150005.skeinly.ui.pullrequest.PullRequestDetailViewModel
import io.github.b150005.skeinly.ui.pullrequest.PullRequestFilter
import io.github.b150005.skeinly.ui.pullrequest.PullRequestListViewModel
import io.github.b150005.skeinly.ui.settings.SettingsViewModel
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

fun getChartDiffViewModel(
    baseRevisionId: String?,
    targetRevisionId: String,
): ChartDiffViewModel = KoinPlatform.getKoin().get { parametersOf(baseRevisionId, targetRevisionId) }

fun getChartBranchPickerViewModel(patternId: String): ChartBranchPickerViewModel = KoinPlatform.getKoin().get { parametersOf(patternId) }

fun getPullRequestListViewModel(defaultFilter: PullRequestFilter): PullRequestListViewModel =
    KoinPlatform.getKoin().get { parametersOf(defaultFilter) }

fun getPullRequestDetailViewModel(prId: String): PullRequestDetailViewModel = KoinPlatform.getKoin().get { parametersOf(prId) }

fun getChartConflictResolutionViewModel(prId: String): ChartConflictResolutionViewModel = KoinPlatform.getKoin().get { parametersOf(prId) }

fun getSymbolCatalog(): SymbolCatalog = KoinPlatform.getKoin().get()

// Phase 36.4.1b: exposes the chart repository directly so the iOS Discovery
// thumbnail can fetch a chart on demand without a dedicated ViewModel layer
// (the fetch is a one-shot read with no observable state — wrapping it in a
// Kotlin ViewModel would be ceremony).
fun getStructuredChartRepository(): io.github.b150005.skeinly.domain.repository.StructuredChartRepository = KoinPlatform.getKoin().get()

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

fun wrapChartHistoryState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.chart.ChartHistoryState>,
): FlowWrapper<io.github.b150005.skeinly.ui.chart.ChartHistoryState> = FlowWrapper(flow)

fun wrapChartHistoryRevisionTaps(
    flow: kotlinx.coroutines.flow.Flow<io.github.b150005.skeinly.ui.chart.RevisionTapTarget>,
): EventFlowWrapper<io.github.b150005.skeinly.ui.chart.RevisionTapTarget> = EventFlowWrapper(flow)

fun wrapChartDiffState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.chart.ChartDiffState>,
): FlowWrapper<io.github.b150005.skeinly.ui.chart.ChartDiffState> = FlowWrapper(flow)

fun wrapChartBranchPickerState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.chart.ChartBranchPickerState>,
): FlowWrapper<io.github.b150005.skeinly.ui.chart.ChartBranchPickerState> = FlowWrapper(flow)

fun wrapChartBranchSwitchedFlow(
    flow: kotlinx.coroutines.flow.Flow<io.github.b150005.skeinly.ui.chart.BranchSwitchedEvent>,
): EventFlowWrapper<io.github.b150005.skeinly.ui.chart.BranchSwitchedEvent> = EventFlowWrapper(flow)

fun wrapPullRequestListState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.pullrequest.PullRequestListState>,
): FlowWrapper<io.github.b150005.skeinly.ui.pullrequest.PullRequestListState> = FlowWrapper(flow)

fun wrapPullRequestDetailState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.pullrequest.PullRequestDetailState>,
): FlowWrapper<io.github.b150005.skeinly.ui.pullrequest.PullRequestDetailState> = FlowWrapper(flow)

fun wrapPullRequestDetailNavEvents(
    flow: kotlinx.coroutines.flow.Flow<io.github.b150005.skeinly.ui.pullrequest.PullRequestDetailNavEvent>,
): EventFlowWrapper<io.github.b150005.skeinly.ui.pullrequest.PullRequestDetailNavEvent> = EventFlowWrapper(flow)

fun wrapChartConflictResolutionState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionState>,
): FlowWrapper<io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionState> = FlowWrapper(flow)

fun wrapChartConflictResolutionNavEvents(
    flow: kotlinx.coroutines.flow.Flow<io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionNavEvent>,
): EventFlowWrapper<io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionNavEvent> = EventFlowWrapper(flow)

fun getSymbolGalleryViewModel(): SymbolGalleryViewModel = KoinPlatform.getKoin().get()

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

// Generic wrappers (deprecated — prefer typed variants above)

@Deprecated(
    "Use a typed wrapper function instead (wrapAuthState, wrapProjectListState, etc.)",
    level = DeprecationLevel.WARNING,
)
fun <T : Any> wrapStateFlow(flow: kotlinx.coroutines.flow.StateFlow<T>): FlowWrapper<T> = FlowWrapper(flow)

@Deprecated(
    "Use a typed wrapper function instead (wrapForkedProjectIdFlow, etc.)",
    level = DeprecationLevel.WARNING,
)
fun <T : Any> wrapEventFlow(flow: kotlinx.coroutines.flow.Flow<T>): EventFlowWrapper<T> = EventFlowWrapper(flow)
