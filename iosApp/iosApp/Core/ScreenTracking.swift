import SwiftUI
import Shared

/// Phase 39.3 (ADR-015 §6) — SwiftUI counterpart to the Android
/// `MainActivity.OnDestinationChangedListener` wiring. Top-level screen
/// views attach this modifier to register a `ScreenViewed` event into
/// the shared analytics tracker on first appearance.
///
/// Why per-screen explicit calls instead of a global listener: SwiftUI's
/// `NavigationStack` does not expose a public destination-change event;
/// each screen view is the natural anchor to declare its own identity.
/// The modifier reads the shared `AnalyticsTracker` from the Koin DI
/// graph and emits exactly one `ScreenViewed` per `.task` lifecycle,
/// which matches the Android `OnDestinationChangedListener` semantics
/// (one fire per back-stack push).
///
/// Usage:
/// ```
/// var body: some View {
///     contentView
///         .trackScreen(.projectList)
/// }
/// ```
///
/// Cardinality contract preserved with the Kotlin `Screen` enum: the
/// `wireValue` PostHog sees is the same testTag-aligned string the
/// Android NavController listener emits, so iOS / Android share a
/// single dashboard slice.
extension View {
    /// Emit `AnalyticsEvent.ScreenViewed(screen)` on first task
    /// activation. Repeated `.task` runs (e.g. tab switch back to the
    /// view) re-fire — that matches the Android NavController
    /// listener's "one fire per back-stack push" behavior.
    func trackScreen(_ screen: Screen) -> some View {
        modifier(TrackScreenModifier(screen: screen))
    }
}

private struct TrackScreenModifier: ViewModifier {
    let screen: Screen

    func body(content: Content) -> some View {
        content.task {
            // Pull the tracker fresh on each .task — `getAnalyticsTracker`
            // resolves the Koin singleton, so this is cheap. Capturing
            // it as `@State` would require a custom equatable wrapper
            // since `AnalyticsTracker` is a Kotlin protocol; the simpler
            // path is the resolve.
            let tracker = KoinHelperKt.getAnalyticsTracker()
            // Kotlin nested type `AnalyticsEvent.ScreenViewed` flattens to
            // `AnalyticsEventScreenViewed` on the Swift bridge — same
            // ObjC interop convention as `AnalyticsEventProjectCreated`
            // and friends already in this codebase.
            tracker.track(event: AnalyticsEventScreenViewed(screen: screen))
        }
    }
}
