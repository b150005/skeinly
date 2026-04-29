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
 * (default per Phase 27a no-tracking stance), `capture()` is a silent
 * no-op — no event is emitted, so callers never need to check opt-in
 * state at the call site. This shifts the gate to one well-tested place.
 *
 * Phase F.4 added the optional `properties` map. Property values may be
 * Bool / Int / Double / String — the Application layer passes the map
 * through to PostHog SDKs which decode the underlying primitive type.
 * Callers should use [AnalyticsEvents] constants for both event names
 * and property keys to prevent typos.
 *
 * **Cardinality warning (devops invariant)**: NEVER include high-
 * cardinality values (pattern.id, project.id, user-authored titles,
 * email addresses, etc.) in properties — both for PII protection and
 * PostHog cost control. Only enum-like discrete values (`"rect"` /
 * `"polar"`), booleans, and small integers (e.g. ring count) are safe.
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
     * @param eventName prefer constants from [AnalyticsEvents].
     * @param properties optional Bool / Int / Double / String values.
     *   See cardinality warning in [AnalyticsTracker] KDoc.
     */
    fun capture(
        eventName: String,
        properties: Map<String, Any>? = null,
    )
}

/**
 * Phase F.4 added `properties` for parametric events. Phase F.5+ may
 * promote this to a sealed-class hierarchy enforcing per-event property
 * shape at compile time, but v1 keeps `Map<String, Any>` for the smaller
 * diff while [AnalyticsEvents] const keys provide a typo guard.
 */
data class AnalyticsEvent(
    val name: String,
    val properties: Map<String, Any>? = null,
)

internal class AnalyticsTrackerImpl(
    private val analyticsPrefs: AnalyticsPreferences,
) : AnalyticsTracker {
    // Buffered to absorb burst captures (e.g. row_incremented during a
    // rapid increment session) without blocking the ViewModel caller.
    // Capacity is generous because each event is tiny and the collector
    // (Application layer) is always live.
    private val _events = MutableSharedFlow<AnalyticsEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AnalyticsEvent> = _events.asSharedFlow()

    override fun capture(
        eventName: String,
        properties: Map<String, Any>?,
    ) {
        if (!analyticsPrefs.analyticsOptIn.value) return
        _events.tryEmit(AnalyticsEvent(eventName, properties))
    }
}
