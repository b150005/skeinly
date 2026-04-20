package io.github.b150005.knitnote.di

import io.github.b150005.knitnote.ui.activityfeed.ActivityFeedViewModel
import io.github.b150005.knitnote.ui.auth.AuthViewModel
import io.github.b150005.knitnote.ui.chart.ChartEditorViewModel
import io.github.b150005.knitnote.ui.chart.ChartViewerViewModel
import io.github.b150005.knitnote.ui.comments.CommentSectionViewModel
import io.github.b150005.knitnote.ui.discovery.DiscoveryViewModel
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
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule =
    module {
        viewModelOf(::OnboardingViewModel)
        viewModelOf(::AuthViewModel)
        viewModelOf(::ProfileViewModel)
        viewModelOf(::SettingsViewModel)
        viewModel { ActivityFeedViewModel(get(), get(), get()) }
        viewModelOf(::ProjectListViewModel)
        viewModelOf(::PatternLibraryViewModel)
        viewModelOf(::DiscoveryViewModel)
        viewModel { params ->
            PatternEditViewModel(
                patternId = params.getOrNull(),
                patternRepository = get(),
                createPattern = get(),
                updatePattern = get(),
            )
        }
        viewModel { params ->
            ProjectDetailViewModel(
                projectId = params.get(),
                projectRepository = get(),
                patternRepository = get(),
                incrementRow = get(),
                decrementRow = get(),
                addProgressNote = get(),
                getProgressNotes = get(),
                deleteProgressNote = get(),
                updateProject = get(),
                completeProject = get(),
                reopenProject = get(),
                shareProject = get(),
                uploadChartImage = get(),
                deleteChartImage = get(),
                remoteStorage = getOrNull(chartImagesStorageQualifier),
                uploadProgressPhoto = get(),
                deleteProgressPhoto = get(),
                progressPhotoStorage = getOrNull(progressPhotosStorageQualifier),
                observeStructuredChart = get(),
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
            ChartViewerViewModel(
                patternId = params.get(),
                observeStructuredChart = get(),
            )
        }
        viewModel { params ->
            ChartEditorViewModel(
                patternId = params.get(),
                getStructuredChart = get(),
                createStructuredChart = get(),
                updateStructuredChart = get(),
                symbolCatalog = get(),
            )
        }
        viewModelOf(::SymbolGalleryViewModel)
        viewModel { params ->
            SharedContentViewModel(
                token = params.get<String?>(0),
                shareId = params.get<String?>(1),
                resolveShareToken = get(),
                forkSharedPattern = get(),
            )
        }
    }
