package io.github.b150005.skeinly.ui.bugreport

import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.EventRingBuffer
import io.github.b150005.skeinly.data.analytics.Screen
import io.github.b150005.skeinly.platform.DeviceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 39.5 (ADR-015 §6) — ViewModel coverage for the bug-report preview.
 *
 * `Dispatchers.setMain(UnconfinedTestDispatcher)` is required because
 * `viewModelScope` (from androidx.lifecycle) uses `Dispatchers.Main` by
 * default — without the override, every `viewModelScope.launch { ... }`
 * inside the ViewModel either fails with `IllegalStateException` (no
 * Main dispatcher) or never runs synchronously inside `runTest`. The
 * unconfined variant runs queued coroutines synchronously on the
 * caller's thread so state assertions land without `advanceUntilIdle()`
 * after every event dispatch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BugReportPreviewViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val deviceContext =
        DeviceContext(
            appVersion = "1.0.0-beta1 (3)",
            osVersion = "Android 14 (API 34)",
            deviceModel = "Google Pixel 8",
            platformName = "Android",
            locale = "en-US",
        )

    @Test
    fun init_renders_initial_preview_with_empty_description_and_empty_events() =
        runTest {
            val rig = makeRig()
            val state = rig.viewModel.state.value
            assertEquals("", state.description)
            assertTrue(state.previewBody.isNotEmpty())
            assertTrue(state.previewBody.contains("## Description"))
            assertTrue(state.previewBody.contains("- Platform: Android Android 14"))
            assertTrue(state.previewBody.contains("_No actions captured"))
        }

    @Test
    fun description_changed_updates_state_and_re_renders_preview() =
        runTest {
            val rig = makeRig()
            rig.viewModel.onEvent(BugReportPreviewEvent.DescriptionChanged("Pattern editor froze"))
            val state = rig.viewModel.state.value
            assertEquals("Pattern editor froze", state.description)
            assertTrue(state.previewBody.contains("Pattern editor froze"))
        }

    @Test
    fun description_capped_at_max_length() =
        runTest {
            val rig = makeRig()
            val tooLong = "a".repeat(BugReportPreviewViewModel.MAX_DESCRIPTION_LENGTH + 100)
            rig.viewModel.onEvent(BugReportPreviewEvent.DescriptionChanged(tooLong))
            val state = rig.viewModel.state.value
            assertEquals(BugReportPreviewViewModel.MAX_DESCRIPTION_LENGTH, state.description.length)
        }

    @Test
    fun submit_invokes_launcher_with_title_and_body() =
        runTest {
            val rig =
                makeRig(
                    seedEvents =
                        listOf(
                            AnalyticsEvent.ScreenViewed(Screen.ProjectList),
                            AnalyticsEvent.ProjectCreated,
                        ),
                )
            rig.viewModel.onEvent(BugReportPreviewEvent.DescriptionChanged("Steps to reproduce..."))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            assertEquals(1, rig.calls.size)
            val (title, body) = rig.calls.first()
            assertTrue(title.contains("[Beta]"))
            assertTrue(title.contains("Steps to reproduce..."))
            assertTrue(body.contains("Steps to reproduce..."))
            assertTrue(body.contains("project_created"))
        }

    @Test
    fun submit_with_empty_description_uses_default_title() =
        runTest {
            val rig = makeRig()
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            assertEquals("[Beta] Bug report", rig.calls.single().first)
        }

    @Test
    fun submit_truncates_overlong_first_line_to_title_cap() =
        runTest {
            val rig = makeRig()
            val firstLine = "a".repeat(120)
            rig.viewModel.onEvent(BugReportPreviewEvent.DescriptionChanged(firstLine))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            val title = rig.calls.single().first
            assertTrue(title.endsWith("..."))
            // "[Beta] " + 80-char body + "..." = 7 + 80 + 3 = 90.
            assertEquals(90, title.length)
        }

    @Test
    fun submit_skips_blank_first_line_and_uses_first_non_blank() =
        runTest {
            val rig = makeRig()
            rig.viewModel.onEvent(
                BugReportPreviewEvent.DescriptionChanged("\n\n  \n  Real first line\n"),
            )
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            assertEquals("[Beta] Real first line", rig.calls.single().first)
        }

    @Test
    fun submit_re_snapshots_buffer_so_events_added_after_init_are_included() =
        runTest {
            val rig = makeRig()
            // Initial render already happened with empty buffer in init.
            rig.tracker.emit(AnalyticsEvent.ProjectCreated)
            rig.tracker.emit(AnalyticsEvent.RowIncremented)
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            val body = rig.calls.single().second
            assertTrue(body.contains("project_created"))
            assertTrue(body.contains("row_incremented"))
        }

    @Test
    fun refresh_preview_re_runs_against_current_buffer_snapshot() =
        runTest {
            val rig = makeRig()
            assertFalse(
                rig.viewModel.state.value.previewBody
                    .contains("project_created"),
            )
            rig.tracker.emit(AnalyticsEvent.ProjectCreated)
            rig.viewModel.onEvent(BugReportPreviewEvent.RefreshPreview)
            assertTrue(
                rig.viewModel.state.value.previewBody
                    .contains("project_created"),
            )
        }

    @Test
    fun is_submitting_toggles_off_after_launch_returns() =
        runTest {
            val rig = makeRig()
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            assertFalse(rig.viewModel.state.value.isSubmitting)
        }

    @Test
    fun preview_body_carries_device_context_lines() =
        runTest {
            val rig = makeRig()
            val body = rig.viewModel.state.value.previewBody
            assertTrue(body.contains("Google Pixel 8"))
            assertTrue(body.contains("en-US"))
        }

    private data class TestRig(
        val viewModel: BugReportPreviewViewModel,
        val tracker: FakeAnalyticsTracker,
        val calls: List<Pair<String, String>>,
    )

    private fun makeRig(seedEvents: List<AnalyticsEvent> = emptyList()): TestRig {
        val tracker = FakeAnalyticsTracker()
        val scope = TestScope(testDispatcher)
        val ringBuffer = EventRingBuffer(tracker)
        ringBuffer.start(scope)
        seedEvents.forEach(tracker::emit)
        val calls = mutableListOf<Pair<String, String>>()
        val vm =
            BugReportPreviewViewModel(
                ringBuffer = ringBuffer,
                deviceContext = deviceContext,
                submit = { title, body -> calls.add(title to body) },
            )
        return TestRig(vm, tracker, calls)
    }

    private class FakeAnalyticsTracker : AnalyticsTracker {
        private val sharedFlow = MutableSharedFlow<AnalyticsEvent>(extraBufferCapacity = 64)
        override val events: SharedFlow<AnalyticsEvent> = sharedFlow.asSharedFlow()

        override fun track(event: AnalyticsEvent) {
            sharedFlow.tryEmit(event)
        }

        fun emit(event: AnalyticsEvent) {
            sharedFlow.tryEmit(event)
        }
    }
}
