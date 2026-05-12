package io.github.b150005.skeinly.ui.a11y

/**
 * Pre-alpha A25 — Reduce Motion respect.
 *
 * Reads the OS-level "reduce motion" / "remove animations" setting.
 *
 * Implementations:
 *  - iOS: `UIAccessibilityIsReduceMotionEnabled()` — top-level UIKit fn
 *    available since iOS 8; reads the toggle at Settings →
 *    Accessibility → Motion → Reduce Motion.
 *  - Android: queries the three `Settings.Global` animation-scale values
 *    (`TRANSITION_ANIMATION_SCALE`, `ANIMATOR_DURATION_SCALE`,
 *    `WINDOW_ANIMATION_SCALE`); any being `0f` is treated as Reduce
 *    Motion. Reached via Settings → Accessibility → "Remove animations"
 *    on Android 12+ or Settings → Developer Options on every version.
 *
 * Modeled as an [expect] class so Android's actual can carry the
 * required `Context` constructor dependency (Application Context
 * injected via Koin's `androidContext()`). Mirrors the established
 * platform-launcher pattern (`SupportContactLauncher`, etc.).
 *
 * Stock Material 3 transitions (modal sheets, navigation, list-item
 * animations) consult the platform setting automatically; this detector
 * exists for **custom** animations that bypass the stock machinery —
 * e.g. `HorizontalPager.animateScrollToPage`, `animateColorAsState`,
 * onboarding page slides, splash transitions.
 *
 * Call from the main thread / Compose context. Cache at composition
 * setup with `remember { detector.isEnabled() }` if you read it inside
 * a high-frequency recomposition.
 */
expect class ReduceMotionDetector {
    fun isEnabled(): Boolean
}
