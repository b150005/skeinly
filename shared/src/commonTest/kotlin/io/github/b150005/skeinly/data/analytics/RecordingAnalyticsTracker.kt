package io.github.b150005.skeinly.data.analytics

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test fake that records every tracked [AnalyticsEvent] into [captured].
 * Does NOT gate on opt-in state — that is tested separately in
 * [AnalyticsTrackerTest]. ViewModel tests that inject this can assume
 * every `track(event)` call lands in the list.
 *
 * Phase F.5+ updated the recording shape to typed [AnalyticsEvent]
 * variants. Tests asserting on event identity should compare against
 * the typed variant directly (e.g. `AnalyticsEvent.ProjectCreated`)
 * rather than wire names — that way property-shape regressions surface
 * at the test layer too.
 *
 * The [capturedNames] convenience accessor remains for tests that only
 * care that an event of a given name fired (e.g. opt-in gating).
 */
internal class RecordingAnalyticsTracker : AnalyticsTracker {
    private val _events = MutableSharedFlow<AnalyticsEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AnalyticsEvent> = _events.asSharedFlow()

    val captured: MutableList<AnalyticsEvent> = mutableListOf()

    /** Convenience accessor for tests that only assert event names. */
    val capturedNames: List<String>
        get() = captured.map { it.name }

    /**
     * Phase 39.3 (ADR-015 §6) — captures filtered to outcome-only events.
     *
     * Excludes the generic engagement-funnel variants
     * [AnalyticsEvent.ClickAction] and [AnalyticsEvent.ScreenViewed] so
     * pre-39.3 tests asserting on `listOf(OutcomeEvent)` do not need to
     * be rewritten to enumerate the surrounding ClickAction noise.
     * The intent-vs-outcome distinction is documented in the
     * [io.github.b150005.skeinly.data.analytics.ClickActionId] KDoc.
     */
    val outcomeEvents: List<AnalyticsEvent>
        get() =
            captured.filter {
                it !is AnalyticsEvent.ClickAction && it !is AnalyticsEvent.ScreenViewed
            }

    override fun track(event: AnalyticsEvent) {
        captured.add(event)
        _events.tryEmit(event)
    }
}
