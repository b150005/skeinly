package com.knitnote

import com.knitnote.di.platformModule
import com.knitnote.di.sharedModules
import com.knitnote.domain.model.CommentTargetType
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

fun initKoin() {
    startKoin {
        modules(listOf(platformModule) + sharedModules)
    }
}

// ViewModel accessors for Swift interop

fun getAuthViewModel(): AuthViewModel =
    KoinPlatform.getKoin().get()

fun getProjectListViewModel(): ProjectListViewModel =
    KoinPlatform.getKoin().get()

fun getProjectDetailViewModel(projectId: String): ProjectDetailViewModel =
    KoinPlatform.getKoin().get { parametersOf(projectId) }

fun getProfileViewModel(): ProfileViewModel =
    KoinPlatform.getKoin().get()

fun getActivityFeedViewModel(): ActivityFeedViewModel =
    KoinPlatform.getKoin().get()

fun getSharedWithMeViewModel(): SharedWithMeViewModel =
    KoinPlatform.getKoin().get()

fun getCommentSectionViewModel(
    targetType: CommentTargetType,
    targetId: String,
): CommentSectionViewModel =
    KoinPlatform.getKoin().get { parametersOf(targetType, targetId) }

fun getSharedContentViewModel(
    token: String?,
    shareId: String?,
): SharedContentViewModel =
    KoinPlatform.getKoin().get { parametersOf(token, shareId) }

// FlowWrapper helpers for Swift

fun <T : Any> wrapStateFlow(flow: kotlinx.coroutines.flow.StateFlow<T>): FlowWrapper<T> =
    FlowWrapper(flow)

fun <T : Any> wrapEventFlow(flow: kotlinx.coroutines.flow.Flow<T>): EventFlowWrapper<T> =
    EventFlowWrapper(flow)
