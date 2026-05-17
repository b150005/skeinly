import Shared

/// Typed accessors for Kotlin ViewModels resolved from Koin.
/// Each function maps to a corresponding `getXxxViewModel()` in KoinHelper.kt.
enum ViewModelFactory {

    static func onboardingViewModel() -> OnboardingViewModel {
        KoinHelperKt.getOnboardingViewModel()
    }

    static func authViewModel() -> AuthViewModel {
        KoinHelperKt.getAuthViewModel()
    }

    static func forgotPasswordViewModel() -> ForgotPasswordViewModel {
        KoinHelperKt.getForgotPasswordViewModel()
    }

    static func projectListViewModel() -> ProjectListViewModel {
        KoinHelperKt.getProjectListViewModel()
    }

    static func projectDetailViewModel(projectId: String) -> ProjectDetailViewModel {
        KoinHelperKt.getProjectDetailViewModel(projectId: projectId)
    }

    static func profileViewModel() -> ProfileViewModel {
        KoinHelperKt.getProfileViewModel()
    }

    static func settingsViewModel() -> SettingsViewModel {
        KoinHelperKt.getSettingsViewModel()
    }

    static func activityFeedViewModel() -> ActivityFeedViewModel {
        KoinHelperKt.getActivityFeedViewModel()
    }

    static func sharedWithMeViewModel() -> SharedWithMeViewModel {
        KoinHelperKt.getSharedWithMeViewModel()
    }

    static func commentSectionViewModel(
        targetType: CommentTargetType,
        targetId: String
    ) -> CommentSectionViewModel {
        KoinHelperKt.getCommentSectionViewModel(
            targetType: targetType,
            targetId: targetId
        )
    }

    static func discoveryViewModel() -> DiscoveryViewModel {
        KoinHelperKt.getDiscoveryViewModel()
    }

    static func patternLibraryViewModel() -> PatternLibraryViewModel {
        KoinHelperKt.getPatternLibraryViewModel()
    }

    static func patternEditViewModel(patternId: String?) -> PatternEditViewModel {
        KoinHelperKt.getPatternEditViewModel(patternId: patternId)
    }

    static func sharedContentViewModel(
        token: String?,
        shareId: String?
    ) -> SharedContentViewModel {
        KoinHelperKt.getSharedContentViewModel(
            token: token,
            shareId: shareId
        )
    }

    static func chartViewerViewModel(
        patternId: String,
        projectId: String?
    ) -> ChartViewerViewModel {
        KoinHelperKt.getChartViewerViewModel(patternId: patternId, projectId: projectId)
    }

    static func chartEditorViewModel(patternId: String) -> ChartEditorViewModel {
        KoinHelperKt.getChartEditorViewModel(patternId: patternId)
    }

    static func chartHistoryViewModel(patternId: String) -> ChartHistoryViewModel {
        KoinHelperKt.getChartHistoryViewModel(patternId: patternId)
    }

    static func chartComparisonViewModel(
        baseRevisionId: String?,
        targetRevisionId: String
    ) -> ChartComparisonViewModel {
        KoinHelperKt.getChartComparisonViewModel(
            baseRevisionId: baseRevisionId,
            targetRevisionId: targetRevisionId
        )
    }

    static func chartVariationPickerViewModel(patternId: String) -> ChartVariationPickerViewModel {
        KoinHelperKt.getChartVariationPickerViewModel(patternId: patternId)
    }

    static func suggestionListViewModel(defaultFilter: SuggestionFilter) -> SuggestionListViewModel {
        KoinHelperKt.getSuggestionListViewModel(defaultFilter: defaultFilter)
    }

    static func suggestionDetailViewModel(prId: String) -> SuggestionDetailViewModel {
        KoinHelperKt.getSuggestionDetailViewModel(prId: prId)
    }

    static func chartConflictResolutionViewModel(prId: String) -> ChartConflictResolutionViewModel {
        KoinHelperKt.getChartConflictResolutionViewModel(prId: prId)
    }

    static func symbolCatalog() -> SymbolCatalog {
        KoinHelperKt.getSymbolCatalog()
    }

    /// Phase 36.4.1b: chart repository accessor for ad-hoc reads (currently
    /// only consumed by `ChartThumbnailView` for one-shot fetches; everything
    /// else routes through ViewModels).
    static func chartRepository() -> ChartRepository {
        KoinHelperKt.getChartRepository()
    }

    static func symbolGalleryViewModel() -> SymbolGalleryViewModel {
        KoinHelperKt.getSymbolGalleryViewModel()
    }

    /// Phase 39.5 (ADR-015 §6) — bug-report preview ViewModel.
    static func bugReportPreviewViewModel() -> BugReportPreviewViewModel {
        KoinHelperKt.getBugReportPreviewViewModel()
    }

    /// Phase 41.3b (ADR-016 §5.1) — paywall ViewModel parametric on the
    /// entry-point trigger.
    static func paywallViewModel(trigger: PaywallTrigger) -> PaywallViewModel {
        KoinHelperKt.getPaywallViewModel(trigger: trigger)
    }

    /// Phase 41.4 (ADR-016 §5.2) — pack management ViewModel.
    static func packManagementViewModel() -> PackManagementViewModel {
        KoinHelperKt.getPackManagementViewModel()
    }

    /// Phase 26.5 (ADR-022 §6.4) — MFA enrollment + challenge ViewModels.
    static func mfaEnrollmentViewModel() -> MfaEnrollmentViewModel {
        KoinHelperKt.getMfaEnrollmentViewModel()
    }

    static func mfaChallengeViewModel() -> MfaChallengeViewModel {
        KoinHelperKt.getMfaChallengeViewModel()
    }

    /// Phase 26.6 (ADR-022 §6.5) — biometric settings ViewModel.
    static func biometricSettingsViewModel() -> BiometricSettingsViewModel {
        KoinHelperKt.getBiometricSettingsViewModel()
    }

    /// Phase 26.6 (ADR-022 §6.6) — post-OAuth profile setup ViewModel.
    /// The seed metadata is captured from the gate decision performed
    /// in `AppRouter` before this screen is mounted; passed through to
    /// the Koin parametric resolver.
    static func oauthProfileSetupViewModel(
        displayName: String?,
        pictureUrl: String?
    ) -> OAuthProfileSetupViewModel {
        KoinHelperKt.getOAuthProfileSetupViewModel(
            displayName: displayName,
            pictureUrl: pictureUrl
        )
    }

    /// Phase 27.2 (ADR-023 §UX) — data-wipe ViewModel. The
    /// `requiredPhrase` is the locale-active confirmation phrase
    /// (`delete my data` on EN, `データを削除` on JA), captured ONCE at
    /// view init via `NSLocalizedString("phrase_wipe_data_confirm", ...)`.
    /// Mid-flow locale change is not supported (ADR §UX).
    static func wipeDataViewModel(requiredPhrase: String) -> WipeDataViewModel {
        KoinHelperKt.getWipeDataViewModel(requiredPhrase: requiredPhrase)
    }

    /// Phase 25.3 (ADR-024 §(e)) — Connections (friends / pending /
    /// invite) ViewModel. Stateless factory; the VM resolves the
    /// caller id + initial three-list refresh in its `init` block.
    static func connectionsViewModel() -> ConnectionsViewModel {
        KoinHelperKt.getConnectionsViewModel()
    }

    /// Phase 25.4 (ADR-024 §Phase 25.4) — friend-invite redemption
    /// ViewModel. `token` picks the mode: non-nil ⇒ Token mode (deep
    /// link, auto-redeem on init); nil ⇒ Code mode (manual entry).
    static func friendInviteConfirmViewModel(
        token: String?
    ) -> FriendInviteConfirmViewModel {
        KoinHelperKt.getFriendInviteConfirmViewModel(token: token)
    }

    /// Phase 39 (ADR-021 §D4) — UGC report modal ViewModel. `targetType`
    /// + `targetId` thread through to Koin parametric resolution (same
    /// precedent as `commentSectionViewModel`).
    static func ugcReportViewModel(
        targetType: UgcTargetType,
        targetId: String
    ) -> UgcReportViewModel {
        KoinHelperKt.getUgcReportViewModel(targetType: targetType, targetId: targetId)
    }

    /// Phase 39 (ADR-021 §D4) — block-user confirmation ViewModel.
    static func blockUserViewModel(blockedUserId: String) -> BlockUserViewModel {
        KoinHelperKt.getBlockUserViewModel(blockedUserId: blockedUserId)
    }

    /// Phase 39 (ADR-021 §D4) — Settings → Privacy → Blocked Users
    /// list ViewModel. Stateless factory; the VM auto-loads the
    /// caller's block list in its `init`.
    static func blockedUsersViewModel() -> BlockedUsersViewModel {
        KoinHelperKt.getBlockedUsersViewModel()
    }

    /// Pre-Phase-40 A20 Option B — Settings → Privacy → Export My
    /// Data ViewModel. Stateless factory; no init-time work (the
    /// export runs only when the user taps Export). The OS share
    /// sheet is fired by the platform DataExportSaver from inside
    /// the VM on success.
    static func dataExportViewModel() -> DataExportViewModel {
        KoinHelperKt.getDataExportViewModel()
    }

    /// Pre-Phase-40 A33 — Settings → About → Open Source Licenses
    /// ViewModel. Auto-loads the bundled `aboutlibraries.json` on init
    /// (parsed by the shared `OssLibraryParser`); the SwiftUI view
    /// renders the resulting `OssLibrary` list natively.
    static func ossLicensesViewModel() -> OssLicensesViewModel {
        KoinHelperKt.getOssLicensesViewModel()
    }
}
