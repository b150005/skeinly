package io.github.b150005.skeinly.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * Phase 26.6 (ADR-022 §6.5) — Android process-level lifecycle observer.
 *
 * `ProcessLifecycleOwner` debounces Activity lifecycles into a single
 * Lifecycle owner: `onStart()` fires when the FIRST Activity moves to
 * foreground; `onStop()` fires when the LAST one moves to background.
 * Survives Activity recreate (rotation) — only inter-process /
 * inter-task transitions are surfaced.
 *
 * `addObserver` MUST be called on the main thread; we wrap the
 * registration in [Dispatchers.Main] via [onStart] so collectors from
 * any dispatcher are safe.
 *
 * SharedFlow with `replay = 0` and `BufferOverflow.DROP_OLDEST` —
 * collectors that subscribe after a transition has fired don't see it
 * retroactively (which would surface a spurious `Foregrounded` to a
 * lifecycle-scoped collector that joins mid-state). The
 * [BiometricGuardian] handles the "no signal yet" path structurally
 * (`lastBackgroundedAt = null` short-circuits to Success), so the
 * dropped-old semantics are safe.
 */
actual class AppLifecycleObserver actual constructor() {
    private val flow =
        MutableSharedFlow<AppLifecycleEvent>(
            replay = 0,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val observer =
        object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                flow.tryEmit(AppLifecycleEvent.Foregrounded)
            }

            override fun onStop(owner: LifecycleOwner) {
                flow.tryEmit(AppLifecycleEvent.Backgrounded)
            }
        }

    actual val events: Flow<AppLifecycleEvent> =
        flow
            .asSharedFlow()
            .onStart {
                // Lazy registration on first subscription. Main-thread
                // requirement of `addObserver` is satisfied via the
                // withContext switch. ProcessLifecycleOwner is a
                // process-scope singleton — multiple installs of the
                // same observer are no-ops on the Lifecycle's internal
                // observer set (LifecycleRegistry uses a SafeIterableMap
                // keyed by observer identity).
                withContext(Dispatchers.Main) {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
                }
            }
}
