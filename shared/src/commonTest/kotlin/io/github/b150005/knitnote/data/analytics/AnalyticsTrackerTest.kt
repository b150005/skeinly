package io.github.b150005.knitnote.data.analytics

import io.github.b150005.knitnote.data.preferences.AnalyticsPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeAnalyticsPreferences(
    initial: Boolean,
) : AnalyticsPreferences {
    private val _flag = MutableStateFlow(initial)
    override val analyticsOptIn: StateFlow<Boolean> = _flag.asStateFlow()

    override fun setAnalyticsOptIn(value: Boolean) {
        _flag.value = value
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsTrackerTest {
    // UnconfinedTestDispatcher so `launch { events.collect ... }` runs
    // synchronously up to the first suspension point — by the time `launch`
    // returns, the collector is already registered against the underlying
    // MutableSharedFlow. Without this, replay = 0 SharedFlow would silently
    // drop tryEmit() calls before the collector attaches, masking the
    // production contract under test.
    private fun runUnconfined(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) =
        runTest(UnconfinedTestDispatcher(), testBody = block)

    @Test
    fun `track is silent no-op when opt-in is OFF`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = false)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.track(AnalyticsEvent.ProjectCreated)

            assertTrue(collected.isEmpty(), "no event should be emitted while opt-in is OFF")
            job.cancel()
        }

    @Test
    fun `track emits event when opt-in is ON`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = true)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.track(AnalyticsEvent.ProjectCreated)

            assertEquals(listOf<AnalyticsEvent>(AnalyticsEvent.ProjectCreated), collected)
            job.cancel()
        }

    @Test
    fun `flipping opt-in OFF stops subsequent emissions`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = true)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.track(AnalyticsEvent.ProjectCreated)
            prefs.setAnalyticsOptIn(false)
            tracker.track(AnalyticsEvent.RowIncremented)

            assertEquals(listOf<AnalyticsEvent>(AnalyticsEvent.ProjectCreated), collected)
            job.cancel()
        }

    @Test
    fun `flipping opt-in ON resumes emissions`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = false)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.track(AnalyticsEvent.ProjectCreated)
            prefs.setAnalyticsOptIn(true)
            tracker.track(AnalyticsEvent.RowIncremented)

            assertEquals(listOf<AnalyticsEvent>(AnalyticsEvent.RowIncremented), collected)
            job.cancel()
        }

    @Test
    fun `event carries the wire name from the typed variant`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = true)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.track(
                AnalyticsEvent.ChartEditorSave(isNew = true, chartFormat = ChartFormat.Rect),
            )

            assertEquals(1, collected.size)
            assertEquals("chart_editor_save", collected[0].name)
            job.cancel()
        }

    @Test
    fun `parametric variant builds wire properties from constructor params`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = true)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.track(
                AnalyticsEvent.ChartEditorSave(isNew = true, chartFormat = ChartFormat.Rect),
            )

            assertEquals(1, collected.size)
            assertEquals(
                mapOf("is_new" to true, "chart_format" to "rect"),
                collected[0].properties,
            )
            job.cancel()
        }

    @Test
    fun `non-parametric variant emits null properties`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = true)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.track(AnalyticsEvent.ProjectCreated)

            assertEquals(null, collected[0].properties)
            job.cancel()
        }

    @Test
    fun `SegmentMarkedDone wire shape covers all gesture paths`() {
        // No emit/collect needed — the wire shape is a pure function of
        // constructor params and is what we care about here.
        assertEquals(
            mapOf("via" to "tap"),
            AnalyticsEvent.SegmentMarkedDone(SegmentVia.Tap).properties,
        )
        assertEquals(
            mapOf("via" to "long_press"),
            AnalyticsEvent.SegmentMarkedDone(SegmentVia.LongPress).properties,
        )
        assertEquals(
            mapOf("via" to "row_batch"),
            AnalyticsEvent.SegmentMarkedDone(SegmentVia.RowBatch).properties,
        )
    }

    @Test
    fun `ChartFormat polar wire value matches storage taxonomy`() {
        assertEquals(
            mapOf("chart_format" to "polar"),
            AnalyticsEvent.PullRequestOpened(ChartFormat.Polar).properties,
        )
    }
}
