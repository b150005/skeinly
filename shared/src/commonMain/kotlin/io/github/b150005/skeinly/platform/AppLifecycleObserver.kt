package io.github.b150005.skeinly.platform

import kotlinx.coroutines.flow.Flow

/**
 * Phase 26.6 (ADR-022 §6.5) — process-level foreground/background
 * lifecycle signal.
 *
 * Each `actual` implementation owns:
 *  - Android: `ProcessLifecycleOwner.get().lifecycle` — single Lifecycle
 *    owner that emits `onStart()` when the process moves *any* Activity
 *    to the foreground and `onStop()` when the last one moves to
 *    background. Survives configuration changes; does not fire during
 *    rotation.
 *  - iOS: `NSNotificationCenter` observing
 *    `UIApplicationWillEnterForegroundNotification` +
 *    `UIApplicationDidEnterBackgroundNotification`.
 *
 * The flow is hot — emits as the OS surfaces transitions; the
 * [io.github.b150005.skeinly.biometric.BiometricGuardian] collects via
 * the application-scope coroutine
 * [io.github.b150005.skeinly.di.applicationScopeQualifier] so the gate
 * lives for the process lifetime regardless of which UI screen is
 * mounted.
 *
 * **Initial emission**: not specified. Different actuals may or may
 * not emit a synthetic [AppLifecycleEvent.Foregrounded] when the flow
 * is first subscribed — the Guardian's logic tolerates either because
 * `requireForResume` only fires when `lastBackgroundedAt != null`. The
 * very first foreground signal (cold start, no prior background) is
 * structurally a no-op.
 */
expect class AppLifecycleObserver() {
    /**
     * Hot flow emitting [AppLifecycleEvent.Backgrounded] /
     * [AppLifecycleEvent.Foregrounded]. Sticks for the process
     * lifetime; multiple collectors are supported.
     */
    val events: Flow<AppLifecycleEvent>
}

/**
 * Closed sealed interface — easy to add `Resumed` /
 * `Paused` granular events if a future feature needs them, without
 * churning the existing `Backgrounded` / `Foregrounded` consumers.
 */
sealed interface AppLifecycleEvent {
    /** Last Activity moved to background (Android) or app went
     *  inactive (iOS). The Guardian timestamps `lastBackgroundedAt`
     *  here. */
    data object Backgrounded : AppLifecycleEvent

    /** First Activity returned to foreground (Android) or app moved
     *  to active (iOS). The Guardian's `requireForResume()` check
     *  fires here. */
    data object Foregrounded : AppLifecycleEvent
}
