package io.github.b150005.skeinly.ui.settings

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
 * Phase 27.1 (ADR-023 §6.2 / §UX) — locks the WipeDataViewModel state
 * machine. Tests use a recording lambda for [wipeData] to inject
 * success / failure outcomes without standing up
 * `WipeDataRepository` / supabase-kt — mirrors the lambda-seam test
 * shape used by `MfaEnrollmentViewModelTest` /
 * `OAuthProfileSetupViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WipeDataViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val requiredPhraseEn = "delete my data"

    @Test
    fun `initial state is Modal step with empty phrase and no error`() =
        runTest {
            val vm =
                WipeDataViewModel(
                    requiredPhrase = requiredPhraseEn,
                    wipeData = { UseCaseResult.Success(Unit) },
                )
            val state = vm.state.value
            assertEquals(WipeDataStep.Modal, state.step)
            assertEquals("", state.phraseInput)
            assertFalse(state.isSubmitting)
            assertNull(state.error)
            assertFalse(state.submitEnabled(requiredPhraseEn))
        }

    @Test
    fun `Continue transitions Modal to PhraseEntry`() =
        runTest {
            val vm =
                WipeDataViewModel(
                    requiredPhrase = requiredPhraseEn,
                    wipeData = { UseCaseResult.Success(Unit) },
                )
            vm.onEvent(WipeDataEvent.Continue)
            assertEquals(WipeDataStep.PhraseEntry, vm.state.value.step)
        }

    @Test
    fun `BackToModal resets phrase and clears error`() =
        runTest {
            val vm =
                WipeDataViewModel(
                    requiredPhrase = requiredPhraseEn,
                    wipeData = { UseCaseResult.Failure(UseCaseError.Network(RuntimeException("oops"))) },
                )
            vm.onEvent(WipeDataEvent.Continue)
            vm.onEvent(WipeDataEvent.UpdatePhrase(requiredPhraseEn))
            vm.onEvent(WipeDataEvent.Submit)
            // surface an error first via the network failure
            assertEquals(ErrorMessage.NetworkUnavailable, vm.state.value.error)
            vm.onEvent(WipeDataEvent.BackToModal)
            val state = vm.state.value
            assertEquals(WipeDataStep.Modal, state.step)
            assertEquals("", state.phraseInput)
            assertNull(state.error)
        }

    @Test
    fun `submitEnabled is false when phrase does not match`() =
        runTest {
            val vm =
                WipeDataViewModel(
                    requiredPhrase = requiredPhraseEn,
                    wipeData = { UseCaseResult.Success(Unit) },
                )
            vm.onEvent(WipeDataEvent.UpdatePhrase("wrong text"))
            assertFalse(vm.state.value.submitEnabled(requiredPhraseEn))
        }

    @Test
    fun `submitEnabled is true when trimmed case-insensitive phrase matches`() =
        runTest {
            val vm =
                WipeDataViewModel(
                    requiredPhrase = requiredPhraseEn,
                    wipeData = { UseCaseResult.Success(Unit) },
                )
            vm.onEvent(WipeDataEvent.UpdatePhrase("  DELETE MY DATA  "))
            assertTrue(vm.state.value.submitEnabled(requiredPhraseEn))
        }

    @Test
    fun `Submit with mismatched phrase silently no-ops`() =
        runTest {
            var callCount = 0
            val vm =
                WipeDataViewModel(
                    requiredPhrase = requiredPhraseEn,
                    wipeData = {
                        callCount++
                        UseCaseResult.Success(Unit)
                    },
                )
            vm.onEvent(WipeDataEvent.UpdatePhrase("wrong"))
            vm.onEvent(WipeDataEvent.Submit)
            assertEquals(0, callCount, "Submit with mismatched phrase must not call wipeData")
            assertFalse(vm.state.value.isSubmitting)
        }

    @Test
    fun `Submit success emits WipeCompleted nav event`() =
        runTest {
            var callCount = 0
            val vm =
                WipeDataViewModel(
                    requiredPhrase = requiredPhraseEn,
                    wipeData = {
                        callCount++
                        UseCaseResult.Success(Unit)
                    },
                )
            vm.onEvent(WipeDataEvent.UpdatePhrase(requiredPhraseEn))
            vm.onEvent(WipeDataEvent.Submit)
            val event = vm.navEvents.first()
            assertEquals(WipeDataNavEvent.WipeCompleted, event)
            assertEquals(1, callCount)
            assertFalse(vm.state.value.isSubmitting)
        }

    @Test
    fun `Submit failure surfaces ErrorMessage and stays on PhraseEntry`() =
        runTest {
            val vm =
                WipeDataViewModel(
                    requiredPhrase = requiredPhraseEn,
                    wipeData = {
                        UseCaseResult.Failure(UseCaseError.Network(RuntimeException("offline")))
                    },
                )
            vm.onEvent(WipeDataEvent.Continue)
            vm.onEvent(WipeDataEvent.UpdatePhrase(requiredPhraseEn))
            vm.onEvent(WipeDataEvent.Submit)
            val state = vm.state.value
            assertEquals(WipeDataStep.PhraseEntry, state.step)
            assertEquals(ErrorMessage.NetworkUnavailable, state.error)
            assertFalse(state.isSubmitting)
        }

    @Test
    fun `Submit re-entry guard prevents double-tap from firing twice`() =
        runTest {
            var callCount = 0
            val gate = CompletableDeferred<Unit>()
            val vm =
                WipeDataViewModel(
                    requiredPhrase = requiredPhraseEn,
                    wipeData = {
                        callCount++
                        gate.await()
                        UseCaseResult.Success(Unit)
                    },
                )
            vm.onEvent(WipeDataEvent.UpdatePhrase(requiredPhraseEn))
            vm.onEvent(WipeDataEvent.Submit)
            vm.onEvent(WipeDataEvent.Submit) // ignored
            gate.complete(Unit)
            vm.navEvents.first()
            assertEquals(1, callCount, "Second Submit during in-flight call must be dropped")
        }

    @Test
    fun `UpdatePhrase clears stale error from prior failure`() =
        runTest {
            val vm =
                WipeDataViewModel(
                    requiredPhrase = requiredPhraseEn,
                    wipeData = {
                        UseCaseResult.Failure(UseCaseError.SignInRequired)
                    },
                )
            vm.onEvent(WipeDataEvent.UpdatePhrase(requiredPhraseEn))
            vm.onEvent(WipeDataEvent.Submit)
            assertEquals(ErrorMessage.SignInRequired, vm.state.value.error)
            vm.onEvent(WipeDataEvent.UpdatePhrase("delete my data "))
            assertNull(vm.state.value.error)
        }

    @Test
    fun `ClearError drops the active ErrorMessage`() =
        runTest {
            val vm =
                WipeDataViewModel(
                    requiredPhrase = requiredPhraseEn,
                    wipeData = {
                        UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
                    },
                )
            vm.onEvent(WipeDataEvent.UpdatePhrase(requiredPhraseEn))
            vm.onEvent(WipeDataEvent.Submit)
            assertEquals(ErrorMessage.RequiresConnectivity, vm.state.value.error)
            vm.onEvent(WipeDataEvent.ClearError)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `Japanese required phrase matches case-insensitively`() =
        runTest {
            val requiredPhraseJa = "データを削除"
            val vm =
                WipeDataViewModel(
                    requiredPhrase = requiredPhraseJa,
                    wipeData = { UseCaseResult.Success(Unit) },
                )
            vm.onEvent(WipeDataEvent.UpdatePhrase("  データを削除  "))
            assertTrue(vm.state.value.submitEnabled(requiredPhraseJa))
            vm.onEvent(WipeDataEvent.Submit)
            val event = vm.navEvents.first()
            assertEquals(WipeDataNavEvent.WipeCompleted, event)
        }

    @Test
    fun `requiredPhrase accessor returns the locale-resolved phrase`() =
        runTest {
            val vm =
                WipeDataViewModel(
                    requiredPhrase = "データを削除",
                    wipeData = { UseCaseResult.Success(Unit) },
                )
            assertEquals("データを削除", vm.requiredPhrase())
        }
}
