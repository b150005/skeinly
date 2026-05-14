package io.github.b150005.skeinly.di

import io.github.b150005.skeinly.config.BuildFlags
import io.github.b150005.skeinly.data.bug.BugReportProxyClient
import io.github.b150005.skeinly.notifications.OsSettingsLauncher
import io.github.b150005.skeinly.notifications.PushTokenRegistrar
import io.github.b150005.skeinly.platform.DeviceContextProvider
import io.github.b150005.skeinly.platform.snapshotContext
import io.github.b150005.skeinly.ui.activityfeed.ActivityFeedViewModel
import io.github.b150005.skeinly.ui.auth.AuthViewModel
import io.github.b150005.skeinly.ui.auth.ForgotPasswordViewModel
import io.github.b150005.skeinly.ui.auth.MfaChallengeViewModel
import io.github.b150005.skeinly.ui.auth.MfaEnrollmentViewModel
import io.github.b150005.skeinly.ui.bugreport.BugReportPreviewViewModel
import io.github.b150005.skeinly.ui.chart.ChartComparisonViewModel
import io.github.b150005.skeinly.ui.chart.ChartEditorViewModel
import io.github.b150005.skeinly.ui.chart.ChartHistoryViewModel
import io.github.b150005.skeinly.ui.chart.ChartVariationPickerViewModel
import io.github.b150005.skeinly.ui.chart.ChartViewerViewModel
import io.github.b150005.skeinly.ui.comments.CommentSectionViewModel
import io.github.b150005.skeinly.ui.discovery.DiscoveryViewModel
import io.github.b150005.skeinly.ui.notifications.NotificationPermissionViewModel
import io.github.b150005.skeinly.ui.onboarding.OnboardingViewModel
import io.github.b150005.skeinly.ui.packmanagement.PackManagementViewModel
import io.github.b150005.skeinly.ui.patternedit.PatternEditViewModel
import io.github.b150005.skeinly.ui.patternlibrary.PatternLibraryViewModel
import io.github.b150005.skeinly.ui.profile.ProfileViewModel
import io.github.b150005.skeinly.ui.projectdetail.ProjectDetailViewModel
import io.github.b150005.skeinly.ui.projectlist.ProjectListViewModel
import io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionViewModel
import io.github.b150005.skeinly.ui.pullrequest.SuggestionDetailViewModel
import io.github.b150005.skeinly.ui.pullrequest.SuggestionFilter
import io.github.b150005.skeinly.ui.pullrequest.SuggestionListViewModel
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
        viewModel {
            // Phase 26.1 / 26.2 (ADR-022 §6.1, §6.2) — bind
            // AuthRepository.signInWithApple + signInWithGoogle +
            // OAuthClient.acquireGoogleIdToken as lambda-seams so
            // AuthViewModel stays testable without pulling the full
            // repository surface (mirrors the
            // BugReportPreviewViewModel.submit precedent from Phase 39.5).
            val authRepository: io.github.b150005.skeinly.domain.repository.AuthRepository = get()
            val oauthClient: io.github.b150005.skeinly.auth.OAuthClient = get()
            AuthViewModel(
                observeAuthState = get(),
                signIn = get(),
                signUp = get(),
                signInWithApple = authRepository::signInWithApple,
                signInWithGoogle = authRepository::signInWithGoogle,
                acquireGoogleIdToken = oauthClient::acquireGoogleIdToken,
                // Phase 26.x (ADR-022 §6.1) — Apple-on-Android
                // web-OAuth lambda-seam, mirroring the signInWithApple
                // / signInWithGoogle pattern above.
                signInWithAppleViaWebOAuth = authRepository::signInWithAppleViaWebOAuth,
                // Phase 26.4 (ADR-022 §6.3) — link-identity resolution
                // lambda-seam. Wired to the same authRepository so
                // production routes through Supabase's
                // `linkIdentityWithIdToken`; tests inject a stub.
                linkPendingIdentity = authRepository::linkPendingIdentity,
            )
        }
        viewModelOf(::ForgotPasswordViewModel)
        // Phase 26.5 (ADR-022 §6.4) — MFA enrollment + challenge ViewModels.
        // Lambda-seam DI mirrors AuthViewModel (Phase 26.1/26.4) — keeps the
        // VMs testable without pulling the supabase-kt surface into commonTest.
        viewModel {
            val authRepository: io.github.b150005.skeinly.domain.repository.AuthRepository = get()
            MfaEnrollmentViewModel(
                enrollMfaTotp = authRepository::enrollMfaTotp,
                verifyMfaEnrollment = authRepository::verifyMfaEnrollment,
            )
        }
        viewModel {
            val authRepository: io.github.b150005.skeinly.domain.repository.AuthRepository = get()
            MfaChallengeViewModel(
                submitMfaChallenge = authRepository::submitMfaChallenge,
                consumeRecoveryCode = authRepository::consumeRecoveryCode,
            )
        }
        viewModelOf(::ProfileViewModel)
        // Phase 39.4 — `eventRingBuffer` ctor param threaded so a
        // toggle-OFF on diagnostic-data sharing clears the in-memory
        // event trail before any subsequent bug-report submission can
        // attach it.
        viewModel {
            val authRepository: io.github.b150005.skeinly.domain.repository.AuthRepository = get()
            SettingsViewModel(
                observeAuthState = get(),
                signOut = get(),
                deleteAccount = get(),
                updatePassword = get(),
                updateEmail = get(),
                analyticsPreferences = get(),
                eventRingBuffer = get(),
                // Phase 41.3b — ClickAction analytics for the Pro entry tap.
                analyticsTracker = get(),
                // Phase 26.5 (ADR-022 §6.4) — MFA observation + disable
                // bound through lambda-seams (parallel to the AuthViewModel
                // OAuth pattern). Defaults to flowOf(NotEnrolled) for tests
                // that don't wire this; production binds to repo methods.
                observeMfaStatusFlow = { authRepository.observeMfaStatus() },
                disableMfa = authRepository::disableMfa,
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
                observeChart = get(),
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
                observeChart = get(),
                observeProjectSegments = get(),
                toggleSegmentState = get(),
                markSegmentDone = get(),
                markRowSegmentsDone = get(),
                // Phase 38.4.1 — Open PR deps. `getOrNull` so the gate
                // gracefully degrades to closed in offline-only / mis-wired
                // setups; production wiring always provides non-null.
                patternRepository = getOrNull(),
                chartVariationRepository = getOrNull(),
                authRepository = getOrNull(),
                openSuggestion = getOrNull(),
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
            ChartVariationPickerViewModel(
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
            ChartComparisonViewModel(
                baseRevisionId = params.get<String?>(0),
                targetRevisionId = params.get<String>(1),
                getChartComparison = get(),
            )
        }
        viewModel { params ->
            ChartEditorViewModel(
                patternId = params.get(),
                getChart = get(),
                createChart = get(),
                updateChart = get(),
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
                saveSharedPatternToLibrary = get(),
            )
        }
        viewModel { params ->
            SuggestionListViewModel(
                defaultFilter = params.get<SuggestionFilter>(),
                getIncoming = get(),
                getOutgoing = get(),
                authRepository = get(),
                userRepository = get(),
            )
        }
        viewModel { params ->
            SuggestionDetailViewModel(
                prId = params.get(),
                getSuggestion = get(),
                getComments = get(),
                postComment = get(),
                closeSuggestion = get(),
                suggestionRepository = get(),
                patternRepository = get(),
                userRepository = get(),
                authRepository = get(),
                applySuggestion = get(),
                chartVersionRepository = get(),
                chartRepository = get(),
                analyticsTracker = get(),
            )
        }
        viewModel { params ->
            ChartConflictResolutionViewModel(
                prId = params.get(),
                getSuggestion = get(),
                chartVersionRepository = get(),
                chartRepository = get(),
                applySuggestion = get(),
                // Phase F.5 — AnalyticsTracker is registered as a single in
                // PreferencesModule and always present in production. The
                // ctor param is nullable-with-default so test sites can
                // construct the ViewModel without loading the analytics
                // module; production wiring uses get() not getOrNull()
                // (matches the F.3/F.4 pattern across the other ViewModels).
                analyticsTracker = get(),
            )
        }
        // Phase 39 W5b (ADR-020) — bug-report preview surface. The
        // ViewModel takes a plain DTO + a suspend lambda rather than
        // the BugReportProxyClient directly so commonTest can stub
        // submission without standing up Ktor. The DI boundary owns
        // the adaptation: snapshot the device-context provider into
        // the DTO and bind the proxy client's `submit` method
        // reference to the lambda. (Phase 39.5 used the same pattern
        // for BugSubmissionLauncher; W5b deletes that expect/actual
        // and routes through the Edge Function instead.)
        viewModel {
            val proxyClient: BugReportProxyClient = get()
            val provider: DeviceContextProvider = get()
            BugReportPreviewViewModel(
                ringBuffer = get(),
                deviceContext = provider.snapshotContext(),
                submit = proxyClient::submit,
            )
        }
        // Phase 24.2 (ADR-017 §3.6) — push notification consent ViewModel.
        // Lambda seam pattern (mirrors BugReportPreviewViewModel above)
        // because PushTokenRegistrar / OsSettingsLauncher are expect/actual
        // classes that surface as `final class` to commonTest, blocking
        // direct subclassing for fakes. The DI boundary owns the
        // adaptation: bind each suspend method to a lambda the ViewModel
        // can swap with a closure in tests.
        viewModel {
            val registrar: PushTokenRegistrar = get()
            val osSettings: OsSettingsLauncher = get()
            NotificationPermissionViewModel(
                prompter = get(),
                queryPermissionStatus = { registrar.queryPermissionStatus() },
                requestPermission = { registrar.requestPermission() },
                registerForPushNotifications = { locale ->
                    registrar.registerForPushNotifications(locale)
                },
                openOsSettings = { osSettings.openAppNotificationSettings() },
            )
        }
        // Phase 41.3 (ADR-016 §6 §41.3) — paywall ViewModel. Parameterized
        // factory so the entry point can pass the [PaywallTrigger] discriminator
        // (Settings vs auto-lock-in-editor vs ?-cell tap) for funnel analytics.
        // SymbolPackSyncManager is registered conditionally (only when Supabase
        // is configured) so we use getOrNull to keep local-only dev builds working.
        viewModel { params ->
            // Safe-cast SymbolCatalog → CompositeSymbolCatalog so a future test
            // module / mock binding doesn't throw ClassCastException here. The
            // post-purchase catalog refresh is non-load-bearing — server-side
            // Realtime push to `subscriptions` re-warms the entitlement gate
            // even if the client-side refresh is skipped — so a no-op fallback
            // is safe when the cast misses.
            val refreshCatalogFn: suspend () -> Unit =
                (
                    get<io.github.b150005.skeinly.domain.symbol.SymbolCatalog>()
                        as? io.github.b150005.skeinly.domain.symbol.CompositeSymbolCatalog
                )?.let { catalog -> suspend { catalog.refresh() } } ?: { }
            val packSync: io.github.b150005.skeinly.data.sync.SymbolPackSyncManager? = getOrNull()
            io.github.b150005.skeinly.ui.paywall.PaywallViewModel(
                trigger = params.get(),
                revenueCatService = get(),
                subscriptionRepository = get(),
                authRepository = get(),
                refreshCatalog = refreshCatalogFn,
                syncPacks =
                    packSync?.let { mgr ->
                        suspend {
                            mgr.sync()
                            Unit
                        }
                    },
                analyticsTracker = get(),
            )
        }
        // Phase 41.4 (ADR-016 §5.2 §6 §41.4) — pack management screen.
        // SymbolPackSyncManager is registered conditionally (only when
        // Supabase is configured) so we use getOrNull. Local-only dev
        // builds skip the sync dispatch and just re-read the catalog on
        // Refresh.
        //
        // Phase 41.5 (ADR-016 §41.5.3): consumes [SymbolPackCatalog]
        // rather than injecting [EntitlementResolver] + the local data
        // source directly — gate decision lives at the catalog (gate
        // site), not the ViewModel (call site).
        viewModel {
            val syncManager: io.github.b150005.skeinly.data.sync.SymbolPackSyncManager? = getOrNull()
            PackManagementViewModel(
                symbolPackCatalog = get(),
                syncDispatch = syncManager?.let { mgr -> suspend { mgr.sync() } },
            )
        }
    }
