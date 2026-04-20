package io.github.b150005.knitnote

import io.github.b150005.knitnote.data.remote.SupabaseConfig
import io.github.b150005.knitnote.data.remote.isConfigured
import io.github.b150005.knitnote.di.platformModule
import io.github.b150005.knitnote.di.sharedModules
import io.github.b150005.knitnote.domain.model.CommentTargetType
import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import io.github.b150005.knitnote.domain.usecase.GetOnboardingCompletedUseCase
import io.github.b150005.knitnote.ui.activityfeed.ActivityFeedViewModel
import io.github.b150005.knitnote.ui.auth.AuthViewModel
import io.github.b150005.knitnote.ui.chart.ChartEditorViewModel
import io.github.b150005.knitnote.ui.chart.ChartViewerViewModel
import io.github.b150005.knitnote.ui.comments.CommentSectionViewModel
import io.github.b150005.knitnote.ui.onboarding.OnboardingViewModel
import io.github.b150005.knitnote.ui.patternedit.PatternEditViewModel
import io.github.b150005.knitnote.ui.patternlibrary.PatternLibraryViewModel
import io.github.b150005.knitnote.ui.profile.ProfileViewModel
import io.github.b150005.knitnote.ui.projectdetail.ProjectDetailViewModel
import io.github.b150005.knitnote.ui.projectlist.ProjectListViewModel
import io.github.b150005.knitnote.ui.settings.SettingsViewModel
import io.github.b150005.knitnote.ui.sharedcontent.SharedContentViewModel
import io.github.b150005.knitnote.ui.sharedwithme.SharedWithMeViewModel
import io.github.b150005.knitnote.ui.symbol.SymbolGalleryViewModel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform
import platform.Foundation.NSLog

fun initKoin() {
    startKoin {
        modules(listOf(platformModule) + sharedModules)
    }
    NSLog("[KnitNote] Koin initialized — Supabase configured: %d", if (SupabaseConfig.isConfigured) 1 else 0)
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

fun getActivityFeedViewModel(): ActivityFeedViewModel = KoinPlatform.getKoin().get()

fun getSharedWithMeViewModel(): SharedWithMeViewModel = KoinPlatform.getKoin().get()

fun getCommentSectionViewModel(
    targetType: CommentTargetType,
    targetId: String,
): CommentSectionViewModel = KoinPlatform.getKoin().get { parametersOf(targetType, targetId) }

fun getDiscoveryViewModel(): io.github.b150005.knitnote.ui.discovery.DiscoveryViewModel = KoinPlatform.getKoin().get()

fun getPatternLibraryViewModel(): PatternLibraryViewModel = KoinPlatform.getKoin().get()

fun getPatternEditViewModel(patternId: String?): PatternEditViewModel = KoinPlatform.getKoin().get { parametersOf(patternId) }

fun getSharedContentViewModel(
    token: String?,
    shareId: String?,
): SharedContentViewModel = KoinPlatform.getKoin().get { parametersOf(token, shareId) }

fun getChartViewerViewModel(patternId: String): ChartViewerViewModel = KoinPlatform.getKoin().get { parametersOf(patternId) }

fun getChartEditorViewModel(patternId: String): ChartEditorViewModel = KoinPlatform.getKoin().get { parametersOf(patternId) }

fun getSymbolCatalog(): SymbolCatalog = KoinPlatform.getKoin().get()

// Type-safe FlowWrapper factories for Swift (eliminates as! force-casts)

fun wrapOnboardingState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.onboarding.OnboardingState>,
): FlowWrapper<io.github.b150005.knitnote.ui.onboarding.OnboardingState> = FlowWrapper(flow)

fun wrapAuthState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.auth.AuthUiState>,
): FlowWrapper<io.github.b150005.knitnote.ui.auth.AuthUiState> = FlowWrapper(flow)

fun wrapProjectListState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.projectlist.ProjectListState>,
): FlowWrapper<io.github.b150005.knitnote.ui.projectlist.ProjectListState> = FlowWrapper(flow)

fun wrapProjectDetailState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.projectdetail.ProjectDetailState>,
): FlowWrapper<io.github.b150005.knitnote.ui.projectdetail.ProjectDetailState> = FlowWrapper(flow)

fun wrapProgressNotesState(
    flow: kotlinx.coroutines.flow.StateFlow<List<io.github.b150005.knitnote.domain.model.Progress>>,
): FlowWrapper<List<io.github.b150005.knitnote.domain.model.Progress>> = FlowWrapper(flow)

fun wrapSettingsState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.settings.SettingsState>,
): FlowWrapper<io.github.b150005.knitnote.ui.settings.SettingsState> = FlowWrapper(flow)

fun wrapSettingsAccountDeletedFlow(flow: kotlinx.coroutines.flow.Flow<kotlin.Unit>): EventFlowWrapper<kotlin.Unit> = EventFlowWrapper(flow)

fun wrapProfileState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.profile.ProfileState>,
): FlowWrapper<io.github.b150005.knitnote.ui.profile.ProfileState> = FlowWrapper(flow)

fun wrapActivityFeedState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.activityfeed.ActivityFeedState>,
): FlowWrapper<io.github.b150005.knitnote.ui.activityfeed.ActivityFeedState> = FlowWrapper(flow)

fun wrapSharedWithMeState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.sharedwithme.SharedWithMeState>,
): FlowWrapper<io.github.b150005.knitnote.ui.sharedwithme.SharedWithMeState> = FlowWrapper(flow)

fun wrapDiscoveryState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.discovery.DiscoveryState>,
): FlowWrapper<io.github.b150005.knitnote.ui.discovery.DiscoveryState> = FlowWrapper(flow)

fun wrapDiscoveryForkedProjectIdFlow(flow: kotlinx.coroutines.flow.Flow<String>): EventFlowWrapper<String> = EventFlowWrapper(flow)

fun wrapPatternLibraryState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.patternlibrary.PatternLibraryState>,
): FlowWrapper<io.github.b150005.knitnote.ui.patternlibrary.PatternLibraryState> = FlowWrapper(flow)

fun wrapPatternEditState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.patternedit.PatternEditState>,
): FlowWrapper<io.github.b150005.knitnote.ui.patternedit.PatternEditState> = FlowWrapper(flow)

fun wrapPatternEditSaveSuccess(flow: kotlinx.coroutines.flow.Flow<kotlin.Unit>): EventFlowWrapper<kotlin.Unit> = EventFlowWrapper(flow)

fun wrapSharedContentState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.sharedcontent.SharedContentState>,
): FlowWrapper<io.github.b150005.knitnote.ui.sharedcontent.SharedContentState> = FlowWrapper(flow)

fun wrapCommentSectionState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.comments.CommentSectionState>,
): FlowWrapper<io.github.b150005.knitnote.ui.comments.CommentSectionState> = FlowWrapper(flow)

fun wrapForkedProjectIdFlow(flow: kotlinx.coroutines.flow.Flow<String>): EventFlowWrapper<String> = EventFlowWrapper(flow)

fun wrapChartViewerState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.chart.ChartViewerState>,
): FlowWrapper<io.github.b150005.knitnote.ui.chart.ChartViewerState> = FlowWrapper(flow)

fun wrapChartEditorState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.chart.ChartEditorState>,
): FlowWrapper<io.github.b150005.knitnote.ui.chart.ChartEditorState> = FlowWrapper(flow)

fun wrapChartEditorSavedFlow(flow: kotlinx.coroutines.flow.Flow<Unit>): EventFlowWrapper<Unit> = EventFlowWrapper(flow)

fun getSymbolGalleryViewModel(): SymbolGalleryViewModel = KoinPlatform.getKoin().get()

fun wrapSymbolGalleryState(
    flow: kotlinx.coroutines.flow.StateFlow<io.github.b150005.knitnote.ui.symbol.SymbolGalleryState>,
): FlowWrapper<io.github.b150005.knitnote.ui.symbol.SymbolGalleryState> = FlowWrapper(flow)

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
