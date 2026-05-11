package io.github.b150005.skeinly.ui.bugreport

import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.EventRingBuffer
import io.github.b150005.skeinly.data.analytics.Screen
import io.github.b150005.skeinly.data.bug.BugReportProxyException
import io.github.b150005.skeinly.data.bug.SubmitOutcome
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 39 W5b (ADR-020) — ViewModel coverage rewritten for suspend
 * submit + state.submitResult. Phase 39.5's fire-and-forget submission
 * is replaced by a proper round-trip exposing typed Success / Error
 * states.
 *
 * `Dispatchers.setMain(UnconfinedTestDispatcher)` keeps every
 * `viewModelScope.launch { ... }` coroutine synchronous within
 * `runTest`, so state assertions can land without
 * `advanceUntilIdle()` after every dispatch.
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
            appVersion = "0.1.0 (4)",
            osVersion = "Android 14 (API 34)",
            deviceModel = "Google Pixel 8",
            platformName = "Android",
            locale = "en-US",
        )

    @Test
    fun init_renders_initial_preview() =
        runTest {
            val rig = makeRig()
            val state = rig.viewModel.state.value
            assertEquals("", state.description)
            assertTrue(state.previewBody.isNotEmpty())
            assertTrue(state.previewBody.contains("## Description"))
            assertTrue(state.previewBody.contains("- Platform: Android Android 14"))
            assertNull(state.submitResult)
            assertFalse(state.isSubmitting)
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
    fun submit_success_records_outcome_in_state() =
        runTest {
            val rig =
                makeRig(
                    submitResult =
                        Result.success(
                            SubmitOutcome(issueNumber = 42, htmlUrl = "https://github.com/b150005/skeinly/issues/42"),
                        ),
                )
            rig.viewModel.onEvent(BugReportPreviewEvent.DescriptionChanged("Steps…"))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            val state = rig.viewModel.state.value
            assertFalse(state.isSubmitting)
            val result = assertNotNull(state.submitResult)
            val success = assertIs<SubmitResultState.Success>(result)
            assertEquals(42, success.issueNumber)
            assertEquals("https://github.com/b150005/skeinly/issues/42", success.htmlUrl)
        }

    @Test
    fun submit_invokes_callback_with_title_and_body() =
        runTest {
            val rig =
                makeRig(
                    seedEvents =
                        listOf(
                            AnalyticsEvent.ScreenViewed(Screen.ProjectList),
                            AnalyticsEvent.ProjectCreated,
                        ),
                    submitResult = Result.success(SubmitOutcome(1, "u")),
                )
            rig.viewModel.onEvent(BugReportPreviewEvent.DescriptionChanged("Steps to reproduce..."))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            assertEquals(1, rig.calls.size)
            val (title, body) = rig.calls.first()
            // 2026-05-12 amendment: title prefix removed (Phase 39.5
            // "[Beta]" did not survive the W5 GA-readiness review).
            // Title is now just the first non-blank line.
            assertEquals("Steps to reproduce...", title)
            assertTrue(body.contains("Steps to reproduce..."))
            assertTrue(body.contains("project_created"))
        }

    @Test
    fun submit_with_empty_description_uses_default_title() =
        runTest {
            val rig = makeRig(submitResult = Result.success(SubmitOutcome(1, "u")))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            assertEquals("Bug report", rig.calls.single().first)
        }

    @Test
    fun submit_truncates_overlong_first_line_to_title_cap() =
        runTest {
            val rig = makeRig(submitResult = Result.success(SubmitOutcome(1, "u")))
            val firstLine = "a".repeat(120)
            rig.viewModel.onEvent(BugReportPreviewEvent.DescriptionChanged(firstLine))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            val title = rig.calls.single().first
            assertTrue(title.endsWith("..."))
            // MAX_TITLE_LENGTH (80) + "..." trailer = 83 chars after the
            // W5 prefix removal (down from 90 when "[Beta] " prefix was
            // still in play).
            assertEquals(83, title.length)
        }

    @Test
    fun submit_offline_failure_classified_as_OFFLINE() =
        runTest {
            val rig = makeRig(submitResult = Result.failure(BugReportProxyException.Offline("DNS")))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            val error = assertIs<SubmitResultState.Error>(rig.viewModel.state.value.submitResult)
            assertEquals(ErrorKind.OFFLINE, error.kind)
        }

    @Test
    fun submit_rate_limited_failure_classified_as_RATE_LIMITED() =
        runTest {
            val rig = makeRig(submitResult = Result.failure(BugReportProxyException.RateLimited("try in 47 minutes")))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            val error = assertIs<SubmitResultState.Error>(rig.viewModel.state.value.submitResult)
            assertEquals(ErrorKind.RATE_LIMITED, error.kind)
            assertTrue(error.rawMessage.contains("47"))
        }

    @Test
    fun submit_validation_failed_classified_as_VALIDATION_FAILED() =
        runTest {
            val rig = makeRig(submitResult = Result.failure(BugReportProxyException.ValidationFailed("title too long")))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            val error = assertIs<SubmitResultState.Error>(rig.viewModel.state.value.submitResult)
            assertEquals(ErrorKind.VALIDATION_FAILED, error.kind)
        }

    @Test
    fun submit_config_missing_failure_classified_as_CONFIG_MISSING() =
        runTest {
            val rig = makeRig(submitResult = Result.failure(BugReportProxyException.ConfigMissing("secrets absent")))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            val error = assertIs<SubmitResultState.Error>(rig.viewModel.state.value.submitResult)
            assertEquals(ErrorKind.CONFIG_MISSING, error.kind)
        }

    @Test
    fun submit_server_failure_classified_as_SERVER() =
        runTest {
            val rig = makeRig(submitResult = Result.failure(BugReportProxyException.Server("HTTP 503")))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            val error = assertIs<SubmitResultState.Error>(rig.viewModel.state.value.submitResult)
            assertEquals(ErrorKind.SERVER, error.kind)
        }

    @Test
    fun submit_unknown_failure_classified_as_UNKNOWN() =
        runTest {
            val rig = makeRig(submitResult = Result.failure(BugReportProxyException.Unknown("unparseable")))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            val error = assertIs<SubmitResultState.Error>(rig.viewModel.state.value.submitResult)
            assertEquals(ErrorKind.UNKNOWN, error.kind)
        }

    @Test
    fun submit_non_proxy_exception_classified_as_UNKNOWN() =
        runTest {
            val rig = makeRig(submitResult = Result.failure(IllegalStateException("boom")))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            val error = assertIs<SubmitResultState.Error>(rig.viewModel.state.value.submitResult)
            assertEquals(ErrorKind.UNKNOWN, error.kind)
        }

    @Test
    fun dismiss_result_clears_banner() =
        runTest {
            val rig = makeRig(submitResult = Result.success(SubmitOutcome(1, "u")))
            rig.viewModel.onEvent(BugReportPreviewEvent.Submit)
            assertNotNull(rig.viewModel.state.value.submitResult)
            rig.viewModel.onEvent(BugReportPreviewEvent.DismissResult)
            assertNull(rig.viewModel.state.value.submitResult)
        }

    @Test
    fun submit_is_idempotent_during_round_trip() =
        runTest {
            // Configure submit to never return so we can observe the
            // is-submitting guard. The TestScope's unconfined dispatcher
            // makes the launch synchronous up to the suspension point.
            val calls = mutableListOf<Pair<String, String>>()
            val tracker = FakeAnalyticsTracker()
            val scope = TestScope(testDispatcher)
            val ringBuffer = EventRingBuffer(tracker)
            ringBuffer.start(scope)
            val pending = kotlinx.coroutines.CompletableDeferred<Result<SubmitOutcome>>()
            val vm =
                BugReportPreviewViewModel(
                    ringBuffer = ringBuffer,
                    deviceContext = deviceContext,
                    submit = { title, body ->
                        calls.add(title to body)
                        pending.await()
                    },
                )
            vm.onEvent(BugReportPreviewEvent.Submit)
            assertTrue(vm.state.value.isSubmitting)
            // Second tap while the first is in flight — must be a no-op
            // (no additional call recorded).
            vm.onEvent(BugReportPreviewEvent.Submit)
            assertEquals(1, calls.size)
            // Let the first submission complete.
            pending.complete(Result.success(SubmitOutcome(1, "u")))
        }

    @Test
    fun submit_re_snapshots_buffer_so_events_added_after_init_are_included() =
        runTest {
            val rig = makeRig(submitResult = Result.success(SubmitOutcome(1, "u")))
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

    private fun makeRig(
        seedEvents: List<AnalyticsEvent> = emptyList(),
        submitResult: Result<SubmitOutcome> = Result.success(SubmitOutcome(1, "u")),
    ): TestRig {
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
                submit = { title, body ->
                    calls.add(title to body)
                    submitResult
                },
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
