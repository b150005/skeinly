package io.github.b150005.knitnote.data.analytics

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test fake that records every captured event by name into [captured].
 * Does NOT gate on opt-in state — that is tested separately in
 * [AnalyticsTrackerTest]. ViewModel tests that inject this can assume
 * every `capture(eventName)` call lands in the list.
 */
internal class RecordingAnalyticsTracker : AnalyticsTracker {
    private val _events = MutableSharedFlow<AnalyticsEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AnalyticsEvent> = _events.asSharedFlow()

    val captured: MutableList<String> = mutableListOf()

    override fun capture(eventName: String) {
        captured.add(eventName)
        _events.tryEmit(AnalyticsEvent(eventName))
    }
}
