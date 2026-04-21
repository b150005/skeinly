@file:Suppress("ktlint:standard:filename")

package io.github.b150005.knitnote.ui.platform

import androidx.compose.runtime.Composable

/**
 * Intercepts the platform system-back gesture (Android hardware back /
 * predictive-back gesture). Call this from a Composable screen that has
 * state the user could lose if they navigate away — when [enabled] is true,
 * the platform back is consumed and [onBack] fires instead; when false, the
 * caller receives the default pop behavior.
 *
 * iOS shared Compose screens are not currently used for surfaces with
 * discard-guard semantics (see `iosApp/` SwiftUI mirrors), so the iOS actual
 * is a no-op. If a shared Compose screen is ever rendered on iOS, revisit
 * this to route through Compose Multiplatform's `BackHandler`.
 */
@Composable
expect fun SystemBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
