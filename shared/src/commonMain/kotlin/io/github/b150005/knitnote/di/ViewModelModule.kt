package io.github.b150005.knitnote.di

import io.github.b150005.knitnote.ui.activityfeed.ActivityFeedViewModel
import io.github.b150005.knitnote.ui.auth.AuthViewModel
import io.github.b150005.knitnote.ui.auth.ForgotPasswordViewModel
import io.github.b150005.knitnote.ui.chart.ChartBranchPickerViewModel
import io.github.b150005.knitnote.ui.chart.ChartDiffViewModel
import io.github.b150005.knitnote.ui.chart.ChartEditorViewModel
import io.github.b150005.knitnote.ui.chart.ChartHistoryViewModel
import io.github.b150005.knitnote.ui.chart.ChartViewerViewModel
import io.github.b150005.knitnote.ui.comments.CommentSectionViewModel
import io.github.b150005.knitnote.ui.discovery.DiscoveryViewModel
import io.github.b150005.knitnote.ui.onboarding.OnboardingViewModel
import io.github.b150005.knitnote.ui.patternedit.PatternEditViewModel
import io.github.b150005.knitnote.ui.patternlibrary.PatternLibraryViewModel
import io.github.b150005.knitnote.ui.profile.ProfileViewModel
import io.github.b150005.knitnote.ui.projectdetail.ProjectDetailViewModel
import io.github.b150005.knitnote.ui.projectlist.ProjectListViewModel
import io.github.b150005.knitnote.ui.pullrequest.ChartConflictResolutionViewModel
import io.github.b150005.knitnote.ui.pullrequest.PullRequestDetailViewModel
import io.github.b150005.knitnote.ui.pullrequest.PullRequestFilter
import io.github.b150005.knitnote.ui.pullrequest.PullRequestListViewModel
import io.github.b150005.knitnote.ui.settings.SettingsViewModel
import io.github.b150005.knitnote.ui.sharedcontent.SharedContentViewModel
import io.github.b150005.knitnote.ui.sharedwithme.SharedWithMeViewModel
import io.github.b150005.knitnote.ui.symbol.SymbolGalleryViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule =
    module {
        // Phase F.3 — `OnboardingViewModel` and `ProjectListViewModel` are
        // pivoted from `viewModelOf(::ClassName)` to explicit `viewModel { }`
        // blocks so the optional `analyticsTracker` ctor param is visible at
        // the DI site. `viewModelOf` would also resolve the default-null
        // param via reflection, but the explicit form makes the analytics
        // wiring greppable. The `ActivityFeedViewModel` line just below is
        // unrelated to Phase F.3 (it was already explicit pre-Phase F.3).
        viewModel { OnboardingViewModel(get(), get(), get()) }
        viewModelOf(::AuthViewModel)
        viewModelOf(::ForgotPasswordViewModel)
        viewModelOf(::ProfileViewModel)
        viewModelOf(::SettingsViewModel)
        viewModel { ActivityFeedViewModel(get(), get(), get()) }
        viewModel { ProjectListViewModel(get(), get(), get(), get(), get(), get()) }
        viewModelOf(::PatternLibraryViewModel)
        // Phase F.4 — explicit form for analyticsTracker visibility. See
        // the Phase F.3 comment block above for rationale.
        viewModel { DiscoveryViewModel(get(), get(), get()) }
        viewModel { params ->
            PatternEditViewModel(
                patternId = params.getOrNull(),
                patternRepository = get(),
                createPattern = get(),
                updatePattern = get(),
                analyticsTracker = get(),
            )
        }
        viewModel { params ->
            ProjectDetailViewModel(
                projectId = params.get(),
                projectRepository = get(),
                patternRepository = get(),
                userRepository = get(),
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
                observeProjectSegments = get(),
                resetProjectProgress = get(),
                analyticsTracker = get(),
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
            // Use explicit positional indices — `params.getOrNull<String>()` without
            // an index uses the cursor-based read, but `params.get<T>(0)` above does
            // not advance the cursor, so `getOrNull<String>()` would re-return the
            // patternId and silently scope segment observation to the wrong id. See
            // the SharedContentViewModel binding below for the positional pattern.
            ChartViewerViewModel(
                patternId = params.get<String>(0),
                projectId = params.get<String?>(1),
                observeStructuredChart = get(),
                observeProjectSegments = get(),
                toggleSegmentState = get(),
                markSegmentDone = get(),
                markRowSegmentsDone = get(),
                // Phase 38.4.1 — Open PR deps. `getOrNull` so the gate
                // gracefully degrades to closed in offline-only / mis-wired
                // setups; production wiring always provides non-null.
                patternRepository = getOrNull(),
                chartBranchRepository = getOrNull(),
                authRepository = getOrNull(),
                openPullRequest = getOrNull(),
                analyticsTracker = get(),
            )
        }
        viewModel { params ->
            ChartHistoryViewModel(
                patternId = params.get(),
                getChartHistory = get(),
                restoreRevision = get(),
            )
        }
        viewModel { params ->
            ChartBranchPickerViewModel(
                patternId = params.get(),
                getBranches = get(),
                createBranch = get(),
                switchBranch = get(),
                chartRepository = get(),
                authRepository = get(),
            )
        }
        viewModel { params ->
            // Positional indices per the ChartViewerViewModel binding above —
            // `params.getOrNull<String>()` would re-read index 0 (the nullable
            // baseRevisionId) and silently bind both fields to the same id.
            ChartDiffViewModel(
                baseRevisionId = params.get<String?>(0),
                targetRevisionId = params.get<String>(1),
                getChartDiff = get(),
            )
        }
        viewModel { params ->
            ChartEditorViewModel(
                patternId = params.get(),
                getStructuredChart = get(),
                createStructuredChart = get(),
                updateStructuredChart = get(),
                symbolCatalog = get(),
                analyticsTracker = get(),
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
        viewModel { params ->
            PullRequestListViewModel(
                defaultFilter = params.get<PullRequestFilter>(),
                getIncoming = get(),
                getOutgoing = get(),
                authRepository = get(),
                userRepository = get(),
            )
        }
        viewModel { params ->
            PullRequestDetailViewModel(
                prId = params.get(),
                getPullRequest = get(),
                getComments = get(),
                postComment = get(),
                closePullRequest = get(),
                pullRequestRepository = get(),
                patternRepository = get(),
                userRepository = get(),
                authRepository = get(),
                mergePullRequest = get(),
                chartRevisionRepository = get(),
                structuredChartRepository = get(),
                analyticsTracker = get(),
            )
        }
        viewModel { params ->
            ChartConflictResolutionViewModel(
                prId = params.get(),
                getPullRequest = get(),
                chartRevisionRepository = get(),
                structuredChartRepository = get(),
                mergePullRequest = get(),
                // Phase F.5 — AnalyticsTracker is registered as a single in
                // PreferencesModule and always present in production. The
                // ctor param is nullable-with-default so test sites can
                // construct the ViewModel without loading the analytics
                // module; production wiring uses get() not getOrNull()
                // (matches the F.3/F.4 pattern across the other ViewModels).
                analyticsTracker = get(),
            )
        }
    }
