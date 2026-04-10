package com.knitnote.di

import com.knitnote.ui.activityfeed.ActivityFeedViewModel
import com.knitnote.ui.auth.AuthViewModel
import com.knitnote.ui.comments.CommentSectionViewModel
import com.knitnote.ui.profile.ProfileViewModel
import com.knitnote.ui.projectdetail.ProjectDetailViewModel
import com.knitnote.ui.projectlist.ProjectListViewModel
import com.knitnote.ui.sharedcontent.SharedContentViewModel
import com.knitnote.ui.sharedwithme.SharedWithMeViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModelOf(::AuthViewModel)
    viewModelOf(::ProfileViewModel)
    viewModel { ActivityFeedViewModel(get(), get(), get()) }
    viewModelOf(::ProjectListViewModel)
    viewModel { params ->
        ProjectDetailViewModel(
            projectId = params.get(),
            projectRepository = get(),
            incrementRow = get(),
            decrementRow = get(),
            addProgressNote = get(),
            getProgressNotes = get(),
            deleteProgressNote = get(),
            updateProject = get(),
            completeProject = get(),
            reopenProject = get(),
            shareProject = get(),
        )
    }
    viewModel { SharedWithMeViewModel(get(), get(), get(), get()) }
    viewModel { params ->
        CommentSectionViewModel(
            targetType = params.get(),
            targetId = params.get(),
            getComments = get(),
            createComment = get(),
            deleteCommentUseCase = get(),
            userRepository = get(),
        )
    }
    viewModel { params ->
        SharedContentViewModel(
            token = params.get<String?>(0),
            shareId = params.get<String?>(1),
            resolveShareToken = get(),
            forkSharedPattern = get(),
        )
    }
}
