@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.ui.platform

import androidx.compose.runtime.Composable

@Composable
actual fun SystemBackHandler(
    @Suppress("UNUSED_PARAMETER") enabled: Boolean,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
) {
    // iOS screens are SwiftUI (not shared Compose); back-intercept lives in
    // each SwiftUI screen via a custom toolbar back button. See the iosApp
    // mirror of any screen that also uses SystemBackHandler on Android.
}
