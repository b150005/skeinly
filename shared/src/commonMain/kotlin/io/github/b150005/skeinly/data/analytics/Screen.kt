package io.github.b150005.skeinly.data.analytics

/**
 * Phase 39.3 (ADR-015 §6) — closed taxonomy of user-visible screens.
 *
 * Used by [AnalyticsEvent.ScreenViewed] and [AnalyticsEvent.ClickAction] so
 * the wire-side `screen` property is a small categorical enum, not a
 * free-form String. Cardinality stays bounded (no per-pattern / per-project
 * id leakage) and PostHog dashboards group cleanly across releases.
 *
 * The [wireValue] mirrors each screen's existing testTag landmark from the
 * Phase 33.x i18n sweep — keeping the mental model "the value PostHog sees
 * is the same string Maestro / XCUITest queries against" so future readers
 * can grep one identifier across analytics, instrumented tests, and route
 * names without translation.
 *
 * **When adding a new screen**: add the entry here, add a corresponding
 * route mapping in [androidx.navigation.NavController]'s
 * `OnDestinationChangedListener` wiring (`MainActivity.kt`), and call
 * `.trackScreen(.<entry>)` from the SwiftUI view's body. Missing any of
 * the three is silent — the screen will simply not register engagement
 * data, with no compile-time error.
 */
enum class Screen(
    val wireValue: String,
) {
    Onboarding("onboardingScreen"),
    Login("loginScreen"),
    ProjectList("projectListScreen"),
    ProjectDetail("projectDetailScreen"),
    Profile("profileScreen"),
    Settings("settingsScreen"),
    Discovery("discoveryScreen"),
    PatternLibrary("patternLibraryScreen"),
    PatternEdit("patternEditScreen"),
    ChartViewer("chartViewerScreen"),
    ChartEditor("chartEditorScreen"),
    ChartHistory("chartHistoryScreen"),
    ChartDiff("chartDiffScreen"),
    ChartConflictResolution("chartConflictResolutionScreen"),
    SymbolGallery("symbolGalleryScreen"),
    ActivityFeed("activityFeedScreen"),
    SharedWithMe("sharedWithMeScreen"),
    SharedContent("sharedContentScreen"),
    PullRequestList("pullRequestListScreen"),
    PullRequestDetail("pullRequestDetailScreen"),
    BugReportPreview("bugReportPreviewScreen"),
}
