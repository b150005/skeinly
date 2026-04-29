package io.github.b150005.knitnote.data.analytics

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test fake that records every captured event by name + properties into
 * [captured]. Does NOT gate on opt-in state — that is tested separately
 * in [AnalyticsTrackerTest]. ViewModel tests that inject this can assume
 * every `capture(eventName, properties)` call lands in the list.
 *
 * Phase F.4: extended to record properties alongside event name. Tests
 * that only care about names use the [capturedNames] convenience
 * accessor; tests that assert property shape read [captured] directly.
 */
internal class RecordingAnalyticsTracker : AnalyticsTracker {
    private val _events = MutableSharedFlow<AnalyticsEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AnalyticsEvent> = _events.asSharedFlow()

    val captured: MutableList<AnalyticsEvent> = mutableListOf()

    /** Convenience accessor for tests that only assert event names. */
    val capturedNames: List<String>
        get() = captured.map { it.name }

    override fun capture(
        eventName: String,
        properties: Map<String, Any>?,
    ) {
        val event = AnalyticsEvent(eventName, properties)
        captured.add(event)
        _events.tryEmit(event)
    }
}
