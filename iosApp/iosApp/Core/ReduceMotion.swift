import SwiftUI

/// Pre-alpha A25 — Reduce Motion respect for *explicit* SwiftUI animations.
///
/// SwiftUI does **not** auto-suppress an explicit `withAnimation { }` /
/// animated `.transition` when Settings → Accessibility → Motion →
/// Reduce Motion is on (unlike stock UIKit-bridged system transitions,
/// which substitute a cross-dissolve automatically). WCAG 2.3.3: motion
/// that is not essential must be removable. Call `withMotion` instead of
/// a bare `withAnimation` for non-essential decorative motion (toast
/// slide/fade, double-tap zoom spring) so the state change is applied
/// instantly when the user has asked for reduced motion — the feedback
/// still happens, just without the timed animation.
///
/// Pass the `@Environment(\.accessibilityReduceMotion)` value as
/// `reduceMotion`. That environment key is the SwiftUI-native, reactive
/// binding to `UIAccessibility.isReduceMotionEnabled` — the same OS
/// setting the Compose side reads through the Kotlin
/// `ReduceMotionDetector` `expect/actual`. SwiftUI-only views use this
/// helper rather than the Kotlin bridge: it is reactive (recomposes on
/// toggle), needs no Koin resolution, and is injectable in previews/tests
/// via `.environment(\.accessibilityReduceMotion, true)`.
///
/// When `reduceMotion` is on, the transition declared on the affected
/// view (e.g. `.transition(.opacity)`) becomes inert because there is no
/// animation transaction — the insert/remove is instantaneous, which is
/// the desired Reduce-Motion behavior.
@MainActor
func withMotion(
    _ reduceMotion: Bool,
    _ animation: Animation = .default,
    _ body: () -> Void
) {
    if reduceMotion {
        body()
    } else {
        withAnimation(animation) { body() }
    }
}
