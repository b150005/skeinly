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
    fun `capture is silent no-op when opt-in is OFF`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = false)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.capture("test_event")

            assertTrue(collected.isEmpty(), "no event should be emitted while opt-in is OFF")
            job.cancel()
        }

    @Test
    fun `capture emits event when opt-in is ON`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = true)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.capture("project_created")

            assertEquals(listOf(AnalyticsEvent("project_created")), collected)
            job.cancel()
        }

    @Test
    fun `flipping opt-in OFF stops subsequent emissions`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = true)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.capture("first")
            prefs.setAnalyticsOptIn(false)
            tracker.capture("second")

            assertEquals(listOf(AnalyticsEvent("first")), collected)
            job.cancel()
        }

    @Test
    fun `flipping opt-in ON resumes emissions`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = false)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.capture("dropped")
            prefs.setAnalyticsOptIn(true)
            tracker.capture("kept")

            assertEquals(listOf(AnalyticsEvent("kept")), collected)
            job.cancel()
        }

    @Test
    fun `event carries the event name verbatim`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = true)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.capture("chart_editor_save")

            assertEquals(1, collected.size)
            assertEquals("chart_editor_save", collected[0].name)
            job.cancel()
        }

    @Test
    fun `properties round-trip through the events flow`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = true)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.capture(
                eventName = "chart_editor_save",
                properties = mapOf("is_new" to true, "chart_format" to "rect"),
            )

            assertEquals(1, collected.size)
            assertEquals("chart_editor_save", collected[0].name)
            assertEquals(
                mapOf("is_new" to true, "chart_format" to "rect"),
                collected[0].properties,
            )
            job.cancel()
        }

    @Test
    fun `null properties are preserved as null in the emitted event`() =
        runUnconfined {
            val prefs = FakeAnalyticsPreferences(initial = true)
            val tracker = AnalyticsTrackerImpl(prefs)
            val collected = mutableListOf<AnalyticsEvent>()
            val job = launch { tracker.events.collect { collected.add(it) } }

            tracker.capture("project_created")

            assertEquals(AnalyticsEvent(name = "project_created", properties = null), collected[0])
            job.cancel()
        }
}
