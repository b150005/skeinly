package io.github.b150005.skeinly.ui.a11y

import android.content.Context
import android.provider.Settings

/**
 * Android implementation of [ReduceMotionDetector].
 *
 * Android does not expose a single "Reduce Motion" toggle. The user-
 * facing options (Settings → Accessibility → "Remove animations" on
 * Android 12+, Settings → Developer Options → "Animator/Transition/
 * Window animation scale = Off" on every version) all collapse to one
 * of three `Settings.Global` scale values being `0f`:
 *
 *  - `TRANSITION_ANIMATION_SCALE` — activity transitions
 *  - `ANIMATOR_DURATION_SCALE` — `ObjectAnimator` / `ValueAnimator`
 *  - `WINDOW_ANIMATION_SCALE` — window enter/exit
 *
 * We treat any of the three being `0f` as Reduce Motion enabled. This
 * matches the convention adopted by other accessibility-respecting
 * Android libraries.
 *
 * The Application `Context` dependency is injected by Koin
 * (`androidContext()`); same wiring as `SupportContactLauncher`.
 *
 * `Settings.Global` reads are not security-sensitive (the values are
 * world-readable), so no permission is required.
 */
actual class ReduceMotionDetector(
    private val context: Context,
) {
    actual fun isEnabled(): Boolean {
        val resolver = context.contentResolver
        val transitionScale =
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        val animatorScale =
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val windowScale =
            Settings.Global.getFloat(resolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
        return transitionScale == 0f || animatorScale == 0f || windowScale == 0f
    }
}
