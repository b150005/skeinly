package io.github.b150005.skeinly.platform

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * Phase 26.6 (ADR-022 §6.5) — iOS process-level lifecycle observer.
 *
 * Listens for the system-level notifications:
 *  - `UIApplicationWillEnterForegroundNotification` — about-to-enter
 *    active state. Equivalent to Android's `onStart`.
 *  - `UIApplicationDidEnterBackgroundNotification` — just-entered
 *    background. Equivalent to Android's `onStop`.
 *
 * Notifications are observed on the main queue
 * (`NSOperationQueue.mainQueue`) so emits happen on a known thread.
 *
 * **Observer lifecycle hygiene** (per Phase 26.6 code review §HIGH-3):
 * the inner [callbackFlow] registers the NSNotificationCenter
 * observers on subscription and `removeObserver`s them via
 * [awaitClose] when the flow is cancelled. The outer [shareIn] holds
 * a single application-scope subscription open for the process
 * lifetime, so the registration happens exactly once regardless of
 * how many collectors subscribe to [events]. This prevents
 * per-subscription observer accumulation that the original
 * [addObserverForName]/lambda-block approach would leak: each
 * registration returned a new opaque observer that needed explicit
 * removal, and lambda-block identity is not deduped by
 * NSNotificationCenter.
 *
 * Drop-old buffer semantics match the Android sibling: collectors
 * that subscribe after a transition has fired don't see it
 * retroactively. The [io.github.b150005.skeinly.biometric.BiometricGuardian]
 * handles the "no signal yet" path structurally
 * (`lastBackgroundedAt = null` short-circuits to Success).
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
actual class AppLifecycleObserver actual constructor() {
    actual val events: Flow<AppLifecycleEvent> =
        callbackFlow {
            val nc = NSNotificationCenter.defaultCenter
            val q = NSOperationQueue.mainQueue
            val fgObserver =
                nc.addObserverForName(
                    name = UIApplicationWillEnterForegroundNotification,
                    `object` = null,
                    queue = q,
                    usingBlock = { trySend(AppLifecycleEvent.Foregrounded) },
                )
            val bgObserver =
                nc.addObserverForName(
                    name = UIApplicationDidEnterBackgroundNotification,
                    `object` = null,
                    queue = q,
                    usingBlock = { trySend(AppLifecycleEvent.Backgrounded) },
                )
            awaitClose {
                nc.removeObserver(fgObserver)
                nc.removeObserver(bgObserver)
            }
        }.shareIn(
            // GlobalScope is the established pattern for a
            // process-lifetime singleton flow that outlives any
            // individual collector — the Koin AppLifecycleObserver
            // itself is process-scoped. SharingStarted.Eagerly keeps
            // the upstream callbackFlow active even if no collectors
            // are attached so the OS notifications aren't dropped
            // between the bridge start-up and the first transition.
            scope = GlobalScope,
            started = SharingStarted.Eagerly,
            replay = 0,
        )
}
