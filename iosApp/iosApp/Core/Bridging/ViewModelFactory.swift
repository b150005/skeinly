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

    static func chartDiffViewModel(
        baseRevisionId: String?,
        targetRevisionId: String
    ) -> ChartDiffViewModel {
        KoinHelperKt.getChartDiffViewModel(
            baseRevisionId: baseRevisionId,
            targetRevisionId: targetRevisionId
        )
    }

    static func chartBranchPickerViewModel(patternId: String) -> ChartBranchPickerViewModel {
        KoinHelperKt.getChartBranchPickerViewModel(patternId: patternId)
    }

    static func pullRequestListViewModel(defaultFilter: PullRequestFilter) -> PullRequestListViewModel {
        KoinHelperKt.getPullRequestListViewModel(defaultFilter: defaultFilter)
    }

    static func pullRequestDetailViewModel(prId: String) -> PullRequestDetailViewModel {
        KoinHelperKt.getPullRequestDetailViewModel(prId: prId)
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
    static func structuredChartRepository() -> StructuredChartRepository {
        KoinHelperKt.getStructuredChartRepository()
    }

    static func symbolGalleryViewModel() -> SymbolGalleryViewModel {
        KoinHelperKt.getSymbolGalleryViewModel()
    }
}
