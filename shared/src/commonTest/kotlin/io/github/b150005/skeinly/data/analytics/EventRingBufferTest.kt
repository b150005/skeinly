package io.github.b150005.skeinly.data.analytics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase 39.3 (ADR-015 §6) — coverage for the bug-report event trail.
 *
 * UnconfinedTestDispatcher: matches `AnalyticsTrackerTest`'s pattern so
 * `tracker.events.collect { ... }` is registered against the underlying
 * SharedFlow synchronously (`launch` returns after the collector
 * suspends on `collect`). Without this, [RecordingAnalyticsTracker]'s
 * `tryEmit` calls before the collector attaches would silently drop
 * (replay = 0 SharedFlow), and the FIFO test could read 0 events.
 *
 * [TestScope] supplies the `start(scope)` argument; cancelling it at
 * test-end tears down the collector coroutine cleanly so each test is
 * isolated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventRingBufferTest {
    private fun runUnconfined(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    @Test
    fun `default capacity is 10`() {
        // ADR-015 §6 invariant — the default sets the bug-report trail
        // size; changing it requires an ADR amendment because Phase
        // 39.5's GitHub Issue body formatting assumes ~10-line max.
        assertEquals(10, EventRingBuffer.DEFAULT_CAPACITY)
    }

    @Test
    fun `constructor rejects non-positive capacity`() {
        assertFailsWith<IllegalArgumentException> {
            EventRingBuffer(tracker = RecordingAnalyticsTracker(), capacity = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            EventRingBuffer(tracker = RecordingAnalyticsTracker(), capacity = -1)
        }
    }

    @Test
    fun `snapshot returns empty when no events emitted`() =
        runUnconfined {
            val tracker = RecordingAnalyticsTracker()
            val buffer = EventRingBuffer(tracker = tracker)
            buffer.start(backgroundScope)

            assertEquals(emptyList(), buffer.snapshot())
        }

    @Test
    fun `snapshot accumulates events in arrival order`() =
        runUnconfined {
            val tracker = RecordingAnalyticsTracker()
            val buffer = EventRingBuffer(tracker = tracker)
            buffer.start(backgroundScope)

            tracker.track(AnalyticsEvent.ProjectCreated)
            tracker.track(AnalyticsEvent.RowIncremented)
            tracker.track(AnalyticsEvent.PullRequestCommented)

            assertEquals(
                listOf(
                    AnalyticsEvent.ProjectCreated,
                    AnalyticsEvent.RowIncremented,
                    AnalyticsEvent.PullRequestCommented,
                ),
                buffer.snapshot(),
            )
        }

    @Test
    fun `FIFO eviction drops oldest when capacity exceeded`() =
        runUnconfined {
            val tracker = RecordingAnalyticsTracker()
            val buffer = EventRingBuffer(tracker = tracker, capacity = 3)
            buffer.start(backgroundScope)

            tracker.track(AnalyticsEvent.ProjectCreated)
            tracker.track(AnalyticsEvent.RowIncremented)
            tracker.track(AnalyticsEvent.PullRequestCommented)
            // Fourth tracked event evicts ProjectCreated (oldest).
            tracker.track(AnalyticsEvent.PatternCreated)

            assertEquals(
                listOf(
                    AnalyticsEvent.RowIncremented,
                    AnalyticsEvent.PullRequestCommented,
                    AnalyticsEvent.PatternCreated,
                ),
                buffer.snapshot(),
            )
        }

    @Test
    fun `FIFO holds at capacity through sustained burst`() =
        runUnconfined {
            val tracker = RecordingAnalyticsTracker()
            val buffer = EventRingBuffer(tracker = tracker, capacity = 2)
            buffer.start(backgroundScope)

            // Emit 50 events into a 2-slot buffer; buffer must never grow
            // beyond capacity (catches an off-by-one in the eviction check).
            repeat(50) { tracker.track(AnalyticsEvent.RowIncremented) }

            assertEquals(2, buffer.snapshot().size)
        }

    @Test
    fun `start is idempotent and does not double-subscribe`() =
        runUnconfined {
            val tracker = RecordingAnalyticsTracker()
            val buffer = EventRingBuffer(tracker = tracker, capacity = 5)
            // Calling start twice on the same instance must not result in
            // each event being recorded twice — the second subscriber
            // would race with the first. Idempotency contract is
            // load-bearing for an Application restart edge case (Android
            // onCreate re-run on rare lifecycle paths).
            buffer.start(backgroundScope)
            buffer.start(backgroundScope)

            tracker.track(AnalyticsEvent.ProjectCreated)
            tracker.track(AnalyticsEvent.RowIncremented)

            assertEquals(
                listOf(AnalyticsEvent.ProjectCreated, AnalyticsEvent.RowIncremented),
                buffer.snapshot(),
            )
        }

    @Test
    fun `snapshot returns immutable copy`() =
        runUnconfined {
            val tracker = RecordingAnalyticsTracker()
            val buffer = EventRingBuffer(tracker = tracker)
            buffer.start(backgroundScope)

            tracker.track(AnalyticsEvent.ProjectCreated)
            val firstSnapshot = buffer.snapshot()

            tracker.track(AnalyticsEvent.RowIncremented)
            // The previously-captured snapshot must not see the new event —
            // it is a defensive copy, not a live view.
            assertEquals(listOf(AnalyticsEvent.ProjectCreated), firstSnapshot)
            assertEquals(2, buffer.snapshot().size)
        }

    @Test
    fun `snapshot preserves wire shape of typed event variants`() =
        runUnconfined {
            val tracker = RecordingAnalyticsTracker()
            val buffer = EventRingBuffer(tracker = tracker)
            buffer.start(backgroundScope)

            tracker.track(AnalyticsEvent.ScreenViewed(Screen.ChartEditor))
            tracker.track(
                AnalyticsEvent.ClickAction(ClickActionId.SaveChart, Screen.ChartEditor),
            )

            // Phase 39.5 will serialize these into the bug-report body.
            // The buffer must round-trip the typed variants intact so the
            // formatter reads `name` + `properties` correctly.
            val snapshot = buffer.snapshot()
            assertEquals(2, snapshot.size)
            assertEquals("screen_viewed", snapshot[0].name)
            assertEquals(mapOf("screen" to "chartEditorScreen"), snapshot[0].properties)
            assertEquals("click_action", snapshot[1].name)
            assertEquals(
                mapOf("action" to "save_chart", "screen" to "chartEditorScreen"),
                snapshot[1].properties,
            )
        }

    @Test
    fun `does not record events tracked before start is called`() =
        runUnconfined {
            val tracker = RecordingAnalyticsTracker()
            val buffer = EventRingBuffer(tracker = tracker)

            // Pre-start track() lands in tracker's [RecordingAnalyticsTracker.captured]
            // (it always records) but does NOT land in the ring buffer
            // because the collector is not yet attached. SharedFlow with
            // replay = 0 (which AnalyticsTrackerImpl uses) drops emissions
            // that have no live subscriber. RecordingAnalyticsTracker
            // shares the same SharedFlow contract, so this test exercises
            // the production semantics.
            tracker.track(AnalyticsEvent.ProjectCreated)

            buffer.start(backgroundScope)
            tracker.track(AnalyticsEvent.RowIncremented)

            assertEquals(listOf(AnalyticsEvent.RowIncremented), buffer.snapshot())
            assertTrue(
                tracker.captured.contains(AnalyticsEvent.ProjectCreated),
                "tracker still records the pre-start event in its own list",
            )
        }
}
