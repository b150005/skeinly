package com.knitnote

import com.knitnote.data.remote.SupabaseConfig
import com.knitnote.data.remote.isConfigured
import com.knitnote.di.platformModule
import com.knitnote.di.sharedModules
import com.knitnote.domain.model.CommentTargetType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import com.knitnote.ui.activityfeed.ActivityFeedViewModel
import com.knitnote.ui.auth.AuthViewModel
import com.knitnote.ui.comments.CommentSectionViewModel
import com.knitnote.ui.profile.ProfileViewModel
import com.knitnote.ui.projectdetail.ProjectDetailViewModel
import com.knitnote.ui.projectlist.ProjectListViewModel
import com.knitnote.ui.sharedcontent.SharedContentViewModel
import com.knitnote.ui.sharedwithme.SharedWithMeViewModel
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

fun getAuthViewModel(): AuthViewModel = KoinPlatform.getKoin().get()

fun getProjectListViewModel(): ProjectListViewModel = KoinPlatform.getKoin().get()

fun getProjectDetailViewModel(projectId: String): ProjectDetailViewModel = KoinPlatform.getKoin().get { parametersOf(projectId) }

fun getProfileViewModel(): ProfileViewModel = KoinPlatform.getKoin().get()

fun getActivityFeedViewModel(): ActivityFeedViewModel = KoinPlatform.getKoin().get()

fun getSharedWithMeViewModel(): SharedWithMeViewModel = KoinPlatform.getKoin().get()

fun getCommentSectionViewModel(
    targetType: CommentTargetType,
    targetId: String,
): CommentSectionViewModel = KoinPlatform.getKoin().get { parametersOf(targetType, targetId) }

fun getSharedContentViewModel(
    token: String?,
    shareId: String?,
): SharedContentViewModel = KoinPlatform.getKoin().get { parametersOf(token, shareId) }

// Type-safe FlowWrapper factories for Swift (eliminates as! force-casts)

fun wrapAuthState(
    flow: kotlinx.coroutines.flow.StateFlow<com.knitnote.ui.auth.AuthUiState>,
): FlowWrapper<com.knitnote.ui.auth.AuthUiState> = FlowWrapper(flow)

fun wrapProjectListState(
    flow: kotlinx.coroutines.flow.StateFlow<com.knitnote.ui.projectlist.ProjectListState>,
): FlowWrapper<com.knitnote.ui.projectlist.ProjectListState> = FlowWrapper(flow)

fun wrapProjectDetailState(
    flow: kotlinx.coroutines.flow.StateFlow<com.knitnote.ui.projectdetail.ProjectDetailState>,
): FlowWrapper<com.knitnote.ui.projectdetail.ProjectDetailState> = FlowWrapper(flow)

fun wrapProgressNotesState(
    flow: kotlinx.coroutines.flow.StateFlow<List<com.knitnote.domain.model.Progress>>,
): FlowWrapper<List<com.knitnote.domain.model.Progress>> = FlowWrapper(flow)

fun wrapProfileState(
    flow: kotlinx.coroutines.flow.StateFlow<com.knitnote.ui.profile.ProfileState>,
): FlowWrapper<com.knitnote.ui.profile.ProfileState> = FlowWrapper(flow)

fun wrapActivityFeedState(
    flow: kotlinx.coroutines.flow.StateFlow<com.knitnote.ui.activityfeed.ActivityFeedState>,
): FlowWrapper<com.knitnote.ui.activityfeed.ActivityFeedState> = FlowWrapper(flow)

fun wrapSharedWithMeState(
    flow: kotlinx.coroutines.flow.StateFlow<com.knitnote.ui.sharedwithme.SharedWithMeState>,
): FlowWrapper<com.knitnote.ui.sharedwithme.SharedWithMeState> = FlowWrapper(flow)

fun wrapSharedContentState(
    flow: kotlinx.coroutines.flow.StateFlow<com.knitnote.ui.sharedcontent.SharedContentState>,
): FlowWrapper<com.knitnote.ui.sharedcontent.SharedContentState> = FlowWrapper(flow)

fun wrapCommentSectionState(
    flow: kotlinx.coroutines.flow.StateFlow<com.knitnote.ui.comments.CommentSectionState>,
): FlowWrapper<com.knitnote.ui.comments.CommentSectionState> = FlowWrapper(flow)

fun wrapForkedProjectIdFlow(flow: kotlinx.coroutines.flow.Flow<String>): EventFlowWrapper<String> = EventFlowWrapper(flow)

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
