package io.github.b150005.skeinly.ui.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Sprint A PR6 — `SnackbarHost` wrapper that announces transient
 * messages to assistive technology (TalkBack on Android).
 *
 * Closes the WCAG 4.1.3 (Status Messages) finding from the pre-Phase-39
 * audit: bare Material 3 `SnackbarHost` does not set `liveRegion`, so
 * TalkBack never announces error / success messages — blind and
 * low-vision users receive no auditory feedback when an action
 * succeeds or fails.
 *
 * `LiveRegionMode.Polite` is the right shape for action confirmation:
 * the announcement waits for any current speech to finish, then plays.
 * `Assertive` would interrupt the user and is reserved for critical
 * errors (we have no such surface today).
 *
 * Use as a drop-in replacement for `SnackbarHost(state)` inside
 * `Scaffold(snackbarHost = ...)`.
 */
@Composable
fun LiveSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(
        hostState = hostState,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
