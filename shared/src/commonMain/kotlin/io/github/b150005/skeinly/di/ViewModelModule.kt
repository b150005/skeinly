package io.github.b150005.skeinly.di

import io.github.b150005.skeinly.config.BuildFlags
import io.github.b150005.skeinly.platform.BugSubmissionLauncher
import io.github.b150005.skeinly.platform.DeviceContextProvider
import io.github.b150005.skeinly.platform.snapshotContext
import io.github.b150005.skeinly.ui.activityfeed.ActivityFeedViewModel
import io.github.b150005.skeinly.ui.auth.AuthViewModel
import io.github.b150005.skeinly.ui.auth.ForgotPasswordViewModel
import io.github.b150005.skeinly.ui.bugreport.BugReportPreviewViewModel
import io.github.b150005.skeinly.ui.chart.ChartBranchPickerViewModel
import io.github.b150005.skeinly.ui.chart.ChartDiffViewModel
import io.github.b150005.skeinly.ui.chart.ChartEditorViewModel
import io.github.b150005.skeinly.ui.chart.ChartHistoryViewModel
import io.github.b150005.skeinly.ui.chart.ChartViewerViewModel
import io.github.b150005.skeinly.ui.comments.CommentSectionViewModel
import io.github.b150005.skeinly.ui.discovery.DiscoveryViewModel
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
        // Phase 39.4 (ADR-015 §6) — `analyticsPreferences` + the
        // `includeBetaConsent` flag are threaded so the 4th onboarding
        // consent page only appears on beta builds. `BuildFlags.isBeta`
        // resolves at build time on Android (codegen) and at runtime on
        // iOS (Info.plist). Production binaries get a 3-page carousel.
        viewModel {
            OnboardingViewModel(
                getOnboardingCompleted = get(),
                completeOnboarding = get(),
                analyticsTracker = get(),
                analyticsPreferences = get(),
                includeBetaConsent = BuildFlags.isBeta,
            )
        }
        viewModelOf(::AuthViewModel)
        viewModelOf(::ForgotPasswordViewModel)
        viewModelOf(::ProfileViewModel)
        // Phase 39.4 — `eventRingBuffer` ctor param threaded so a
        // toggle-OFF on diagnostic-data sharing clears the in-memory
        // event trail before any subsequent bug-report submission can
        // attach it.
        viewModel {
            SettingsViewModel(
                observeAuthState = get(),
                signOut = get(),
                deleteAccount = get(),
                updatePassword = get(),
                updateEmail = get(),
                analyticsPreferences = get(),
                eventRingBuffer = get(),
            )
        }
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
        // Phase 39.5 (ADR-015 §6) — bug-report preview surface. The
        // ViewModel takes a plain DTO + a lambda rather than the
        // `expect class` provider/launcher directly so commonTest can
        // stub them without running into the "expect class is final at
        // the actual" subclassing limitation. The DI boundary owns the
        // adaptation: snapshot the device-context provider into the DTO
        // and bind the launcher's `launch` method to the lambda.
        viewModel {
            val launcher: BugSubmissionLauncher = get()
            val provider: DeviceContextProvider = get()
            BugReportPreviewViewModel(
                ringBuffer = get(),
                deviceContext = provider.snapshotContext(),
                submit = { title, body -> launcher.launch(title, body) },
            )
        }
    }
