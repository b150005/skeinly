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
}
