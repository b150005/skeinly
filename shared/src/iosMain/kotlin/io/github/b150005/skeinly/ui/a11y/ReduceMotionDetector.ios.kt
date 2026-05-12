package io.github.b150005.skeinly.ui.a11y

import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/**
 * iOS implementation of [ReduceMotionDetector].
 *
 * `UIAccessibilityIsReduceMotionEnabled()` is a top-level UIKit fn that
 * reads the current value of Settings → Accessibility → Motion →
 * Reduce Motion. Cheap to call (boolean read from a system
 * notification-backed cache); fires
 * `UIAccessibilityReduceMotionStatusDidChange` on toggle. Skeinly does
 * not subscribe to the notification — onboarding and other gated
 * surfaces read the value at composition time and accept that toggling
 * Reduce Motion mid-flow doesn't take effect until the next composition.
 *
 * No constructor argument is needed on iOS — the fn is a global
 * accessor with no Context dependency.
 */
actual class ReduceMotionDetector {
    actual fun isEnabled(): Boolean = UIAccessibilityIsReduceMotionEnabled()
}
