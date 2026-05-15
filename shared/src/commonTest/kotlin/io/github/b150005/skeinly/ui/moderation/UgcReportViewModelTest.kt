package io.github.b150005.skeinly.ui.moderation

import io.github.b150005.skeinly.domain.model.MAX_UGC_REASON_LENGTH
import io.github.b150005.skeinly.domain.model.UgcReportCategory
import io.github.b150005.skeinly.domain.model.UgcTargetType
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 39 (ADR-021 §D4) — locks the UgcReportViewModel state machine.
 * Recording lambda for [submitReport] injects success / failure
 * without standing up the repository — mirrors WipeDataViewModelTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UgcReportViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val targetId = "cccc3333-3333-3333-3333-333333333333"

    private fun vm(
        result: UseCaseResult<Unit> = UseCaseResult.Success(Unit),
        recorder: MutableList<List<Any>> = mutableListOf(),
    ): UgcReportViewModel =
        UgcReportViewModel(
            targetType = UgcTargetType.Pattern,
            targetId = targetId,
            submitReport = { type, id, cat, reason ->
                recorder.add(listOf(type, id, cat, reason))
                result
            },
        )

    @Test
    fun `initial state has no category blank reason and submit disabled`() =
        runTest {
            val s = vm().state.value
            assertNull(s.category)
            assertEquals("", s.reason)
            assertFalse(s.isSubmitting)
            assertNull(s.error)
            assertFalse(s.submitEnabled())
        }

    @Test
    fun `SelectCategory updates category and clears error`() =
        runTest {
            val v = vm()
            v.onEvent(UgcReportEvent.SelectCategory(UgcReportCategory.Hate))
            assertEquals(UgcReportCategory.Hate, v.state.value.category)
        }

    @Test
    fun `UpdateReason updates reason`() =
        runTest {
            val v = vm()
            v.onEvent(UgcReportEvent.UpdateReason("offensive symbols"))
            assertEquals("offensive symbols", v.state.value.reason)
        }

    @Test
    fun `submit disabled when category missing even with valid reason`() =
        runTest {
            val v = vm()
            v.onEvent(UgcReportEvent.UpdateReason("a real reason"))
            assertFalse(v.state.value.submitEnabled())
        }

    @Test
    fun `submit disabled when reason blank even with category`() =
        runTest {
            val v = vm()
            v.onEvent(UgcReportEvent.SelectCategory(UgcReportCategory.Spam))
            v.onEvent(UgcReportEvent.UpdateReason("   "))
            assertFalse(v.state.value.submitEnabled())
        }

    @Test
    fun `submit disabled when reason exceeds cap`() =
        runTest {
            val v = vm()
            v.onEvent(UgcReportEvent.SelectCategory(UgcReportCategory.Spam))
            v.onEvent(UgcReportEvent.UpdateReason("z".repeat(MAX_UGC_REASON_LENGTH + 1)))
            assertFalse(v.state.value.reasonValid)
            assertFalse(v.state.value.submitEnabled())
        }

    @Test
    fun `submit enabled when category present and reason valid`() =
        runTest {
            val v = vm()
            v.onEvent(UgcReportEvent.SelectCategory(UgcReportCategory.Other))
            v.onEvent(UgcReportEvent.UpdateReason("clear description"))
            assertTrue(v.state.value.submitEnabled())
        }

    @Test
    fun `Submit no-op when not enabled`() =
        runTest {
            val recorder = mutableListOf<List<Any>>()
            val v = vm(recorder = recorder)
            v.onEvent(UgcReportEvent.Submit) // nothing selected/typed
            assertTrue(recorder.isEmpty(), "must not call submitReport when gate is closed")
        }

    @Test
    fun `Submit success emits Submitted nav and clears submitting`() =
        runTest {
            val recorder = mutableListOf<List<Any>>()
            val v = vm(result = UseCaseResult.Success(Unit), recorder = recorder)
            v.onEvent(UgcReportEvent.SelectCategory(UgcReportCategory.Violence))
            v.onEvent(UgcReportEvent.UpdateReason("graphic content"))
            v.onEvent(UgcReportEvent.Submit)
            assertEquals(UgcReportNavEvent.Submitted, v.navEvents.first())
            assertFalse(v.state.value.isSubmitting)
            assertEquals(1, recorder.size)
            assertEquals(
                listOf(UgcTargetType.Pattern, targetId, UgcReportCategory.Violence, "graphic content"),
                recorder.first(),
            )
        }

    @Test
    fun `Submit failure surfaces error and preserves reason`() =
        runTest {
            val v =
                vm(result = UseCaseResult.Failure(UseCaseError.Network(RuntimeException("offline"))))
            v.onEvent(UgcReportEvent.SelectCategory(UgcReportCategory.Spam))
            v.onEvent(UgcReportEvent.UpdateReason("keep this text"))
            v.onEvent(UgcReportEvent.Submit)
            assertEquals(ErrorMessage.NetworkUnavailable, v.state.value.error)
            assertEquals("keep this text", v.state.value.reason, "reason preserved on failure")
            assertFalse(v.state.value.isSubmitting)
        }

    @Test
    fun `Submit rate-limited surfaces RateLimitExceeded`() =
        runTest {
            val v = vm(result = UseCaseResult.Failure(UseCaseError.RateLimited))
            v.onEvent(UgcReportEvent.SelectCategory(UgcReportCategory.Spam))
            v.onEvent(UgcReportEvent.UpdateReason("too many reports"))
            v.onEvent(UgcReportEvent.Submit)
            assertEquals(ErrorMessage.RateLimitExceeded, v.state.value.error)
        }

    @Test
    fun `Submit re-entry guard collapses double tap to one call`() =
        runTest {
            val gate = CompletableDeferred<UseCaseResult<Unit>>()
            val recorder = mutableListOf<List<Any>>()
            val v =
                UgcReportViewModel(
                    targetType = UgcTargetType.Comment,
                    targetId = targetId,
                    submitReport = { _, _, _, _ ->
                        recorder.add(emptyList())
                        gate.await()
                    },
                )
            v.onEvent(UgcReportEvent.SelectCategory(UgcReportCategory.Spam))
            v.onEvent(UgcReportEvent.UpdateReason("dup"))
            v.onEvent(UgcReportEvent.Submit)
            v.onEvent(UgcReportEvent.Submit) // second tap during in-flight
            assertTrue(v.state.value.isSubmitting)
            assertEquals(1, recorder.size, "second tap swallowed by re-entry guard")
            gate.complete(UseCaseResult.Success(Unit))
            assertEquals(UgcReportNavEvent.Submitted, v.navEvents.first())
        }

    @Test
    fun `ClearError drops the error`() =
        runTest {
            val v = vm(result = UseCaseResult.Failure(UseCaseError.Unknown(RuntimeException("x"))))
            v.onEvent(UgcReportEvent.SelectCategory(UgcReportCategory.Other))
            v.onEvent(UgcReportEvent.UpdateReason("r"))
            v.onEvent(UgcReportEvent.Submit)
            assertEquals(ErrorMessage.Generic, v.state.value.error)
            v.onEvent(UgcReportEvent.ClearError)
            assertNull(v.state.value.error)
        }
}
