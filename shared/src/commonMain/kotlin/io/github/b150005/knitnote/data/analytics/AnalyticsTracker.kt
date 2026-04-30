package io.github.b150005.knitnote.data.analytics

import io.github.b150005.knitnote.data.preferences.AnalyticsPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Phase F.3 (alpha1 learning loop): emits analytics capture events from
 * shared code (ViewModels) and dispatches them to platform-native PostHog
 * SDKs at the Application layer.
 *
 * The Kotlin side stays SDK-agnostic — `events` is a hot SharedFlow that
 * Application layer collects (Android: KnitNoteApplication, iOS:
 * iOSApp.swift) and forwards to `PostHog.capture(...)` /
 * `PostHogSDK.shared.capture(...)`. This avoids dragging the iOS SwiftPM
 * package into Kotlin/Native interop.
 *
 * Privacy invariant: when `analyticsPrefs.analyticsOptIn.value` is false
 * (default per Phase 27a no-tracking stance), `track()` is a silent
 * no-op — no event is emitted, so callers never need to check opt-in
 * state at the call site. This shifts the gate to one well-tested place.
 *
 * Phase F.5+ promoted the wire shape from `(eventName: String, properties:
 * Map<String, Any>?)` to a typed [AnalyticsEvent] sealed-interface
 * hierarchy. Per-event property shape is now enforced at compile time —
 * see [AnalyticsEvent] for the cardinality contract and variant catalog.
 */
interface AnalyticsTracker {
    /**
     * Hot stream of opt-in-gated events. Application layer should
     * `collect` this for the entire process lifetime and forward each
     * emission to the platform PostHog SDK.
     */
    val events: SharedFlow<AnalyticsEvent>

    /**
     * Records a user-intent event. Silent no-op when analytics is opted
     * out. Safe to call from any thread; emission is non-suspending and
     * uses a buffered SharedFlow so emit-faster-than-collect cases drop
     * to the buffer rather than blocking the caller.
     *
     * @param event a typed [AnalyticsEvent] variant. Construct directly
     *   at the call site — the sealed-type design prevents typos in
     *   event names, property keys, and property values.
     */
    fun track(event: AnalyticsEvent)
}

internal class AnalyticsTrackerImpl(
    private val analyticsPrefs: AnalyticsPreferences,
) : AnalyticsTracker {
    // Buffered to absorb burst captures (e.g. row_incremented during a
    // rapid increment session) without blocking the ViewModel caller.
    // Capacity is generous because each event is tiny and the collector
    // (Application layer) is always live.
    private val _events = MutableSharedFlow<AnalyticsEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AnalyticsEvent> = _events.asSharedFlow()

    override fun track(event: AnalyticsEvent) {
        if (!analyticsPrefs.analyticsOptIn.value) return
        _events.tryEmit(event)
    }
}
