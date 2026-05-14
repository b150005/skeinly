package io.github.b150005.skeinly.ui.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Phase 26.5 (ADR-022 §6.4) — locks the MfaChallengeViewModel state
 * machine. Lambda stubs inject [submitMfaChallenge] +
 * [consumeRecoveryCode] without supabase-kt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MfaChallengeViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `UpdateCode in EnterCode phase keeps only digits up to 6`() {
        val vm =
            MfaChallengeViewModel(
                submitMfaChallenge = {},
                consumeRecoveryCode = { false },
            )
        vm.onEvent(MfaChallengeEvent.UpdateCode("12abc34567"))
        assertEquals("123456", vm.state.value.codeInput)
    }

    @Test
    fun `SubmitCode happy path elevates completed and clears input`() =
        runTest {
            var seenCode: String? = null
            val vm =
                MfaChallengeViewModel(
                    submitMfaChallenge = { seenCode = it },
                    consumeRecoveryCode = { false },
                )
            vm.onEvent(MfaChallengeEvent.UpdateCode("123456"))
            vm.onEvent(MfaChallengeEvent.SubmitCode)
            assertEquals("123456", seenCode)
            assertTrue(vm.state.value.completed)
            assertEquals("", vm.state.value.codeInput)
            assertEquals(0, vm.state.value.failedAttempts)
        }

    @Test
    fun `SubmitCode with short input surfaces InvalidCode without calling repo`() =
        runTest {
            var callCount = 0
            val vm =
                MfaChallengeViewModel(
                    submitMfaChallenge = { callCount++ },
                    consumeRecoveryCode = { false },
                )
            vm.onEvent(MfaChallengeEvent.UpdateCode("12"))
            vm.onEvent(MfaChallengeEvent.SubmitCode)
            assertEquals(0, callCount)
            assertEquals(MfaChallengeError.InvalidCode, vm.state.value.error)
        }

    @Test
    fun `SubmitCode failure increments failedAttempts and surfaces InvalidCode`() =
        runTest {
            val vm =
                MfaChallengeViewModel(
                    submitMfaChallenge = { throw IllegalStateException("bad") },
                    consumeRecoveryCode = { false },
                )
            vm.onEvent(MfaChallengeEvent.UpdateCode("000000"))
            vm.onEvent(MfaChallengeEvent.SubmitCode)
            assertEquals(1, vm.state.value.failedAttempts)
            assertEquals(MfaChallengeError.InvalidCode, vm.state.value.error)
            assertFalse(vm.state.value.completed)
        }

    @Test
    fun `SubmitCode 3 failed attempts triggers Locked error`() =
        runTest {
            val vm =
                MfaChallengeViewModel(
                    submitMfaChallenge = { throw IllegalStateException("bad") },
                    consumeRecoveryCode = { false },
                )
            repeat(3) {
                vm.onEvent(MfaChallengeEvent.UpdateCode("000000"))
                vm.onEvent(MfaChallengeEvent.SubmitCode)
            }
            assertEquals(MfaChallengeError.Locked, vm.state.value.error)
            assertEquals(3, vm.state.value.failedAttempts)
        }

    @Test
    fun `SubmitCode after lockout returns Locked without calling repo`() =
        runTest {
            var callCount = 0
            val vm =
                MfaChallengeViewModel(
                    submitMfaChallenge = {
                        callCount++
                        throw IllegalStateException("bad")
                    },
                    consumeRecoveryCode = { false },
                )
            repeat(3) {
                vm.onEvent(MfaChallengeEvent.UpdateCode("000000"))
                vm.onEvent(MfaChallengeEvent.SubmitCode)
            }
            assertEquals(3, callCount)
            vm.onEvent(MfaChallengeEvent.UpdateCode("000000"))
            vm.onEvent(MfaChallengeEvent.SubmitCode)
            assertEquals(3, callCount) // no additional call
        }

    @Test
    fun `SwitchToRecoveryCode resets input and clears error`() =
        runTest {
            val vm =
                MfaChallengeViewModel(
                    submitMfaChallenge = { throw IllegalStateException("bad") },
                    consumeRecoveryCode = { true },
                )
            vm.onEvent(MfaChallengeEvent.UpdateCode("123"))
            vm.onEvent(MfaChallengeEvent.SubmitCode) // surfaces InvalidCode
            vm.onEvent(MfaChallengeEvent.SwitchToRecoveryCode)
            assertEquals(MfaChallengePhase.EnterRecoveryCode, vm.state.value.phase)
            assertEquals("", vm.state.value.codeInput)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `UpdateCode in EnterRecoveryCode phase uppercases and allows alphanumeric`() {
        val vm =
            MfaChallengeViewModel(
                submitMfaChallenge = {},
                consumeRecoveryCode = { true },
            )
        vm.onEvent(MfaChallengeEvent.SwitchToRecoveryCode)
        vm.onEvent(MfaChallengeEvent.UpdateCode("abcd-1234"))
        // Hyphen is non-alphanumeric → filtered; rest uppercased.
        assertEquals("ABCD1234", vm.state.value.codeInput)
    }

    @Test
    fun `SubmitRecoveryCode happy path elevates completed`() =
        runTest {
            var seen: String? = null
            val vm =
                MfaChallengeViewModel(
                    submitMfaChallenge = {},
                    consumeRecoveryCode = { plain ->
                        seen = plain
                        true
                    },
                )
            vm.onEvent(MfaChallengeEvent.SwitchToRecoveryCode)
            vm.onEvent(MfaChallengeEvent.UpdateCode("RECOVERY-CODE"))
            vm.onEvent(MfaChallengeEvent.SubmitRecoveryCode)
            assertEquals("RECOVERYCODE", seen)
            assertTrue(vm.state.value.completed)
        }

    @Test
    fun `SubmitRecoveryCode false return surfaces InvalidRecoveryCode`() =
        runTest {
            val vm =
                MfaChallengeViewModel(
                    submitMfaChallenge = {},
                    consumeRecoveryCode = { false },
                )
            vm.onEvent(MfaChallengeEvent.SwitchToRecoveryCode)
            vm.onEvent(MfaChallengeEvent.UpdateCode("WRONGCODE"))
            vm.onEvent(MfaChallengeEvent.SubmitRecoveryCode)
            assertEquals(MfaChallengeError.InvalidRecoveryCode, vm.state.value.error)
            assertFalse(vm.state.value.completed)
        }

    @Test
    fun `SubmitRecoveryCode with blank input shows InvalidRecoveryCode without calling repo`() =
        runTest {
            var calls = 0
            val vm =
                MfaChallengeViewModel(
                    submitMfaChallenge = {},
                    consumeRecoveryCode = {
                        calls++
                        true
                    },
                )
            vm.onEvent(MfaChallengeEvent.SwitchToRecoveryCode)
            vm.onEvent(MfaChallengeEvent.SubmitRecoveryCode)
            assertEquals(0, calls)
            assertEquals(MfaChallengeError.InvalidRecoveryCode, vm.state.value.error)
        }

    @Test
    fun `SubmitRecoveryCode failure surfaces Generic error`() =
        runTest {
            val vm =
                MfaChallengeViewModel(
                    submitMfaChallenge = {},
                    consumeRecoveryCode = { throw IllegalStateException("network") },
                )
            vm.onEvent(MfaChallengeEvent.SwitchToRecoveryCode)
            vm.onEvent(MfaChallengeEvent.UpdateCode("WHATEVER"))
            vm.onEvent(MfaChallengeEvent.SubmitRecoveryCode)
            assertEquals(MfaChallengeError.Generic, vm.state.value.error)
        }

    @Test
    fun `SwitchToTotp resets phase and clears state`() =
        runTest {
            val vm =
                MfaChallengeViewModel(
                    submitMfaChallenge = {},
                    consumeRecoveryCode = { false },
                )
            vm.onEvent(MfaChallengeEvent.SwitchToRecoveryCode)
            vm.onEvent(MfaChallengeEvent.UpdateCode("ABC"))
            vm.onEvent(MfaChallengeEvent.SwitchToTotp)
            assertEquals(MfaChallengePhase.EnterCode, vm.state.value.phase)
            assertEquals("", vm.state.value.codeInput)
        }

    @Test
    fun `ClearError drops the error field`() =
        runTest {
            val vm =
                MfaChallengeViewModel(
                    submitMfaChallenge = { throw IllegalStateException("bad") },
                    consumeRecoveryCode = { false },
                )
            vm.onEvent(MfaChallengeEvent.UpdateCode("123456"))
            vm.onEvent(MfaChallengeEvent.SubmitCode)
            assertEquals(MfaChallengeError.InvalidCode, vm.state.value.error)
            vm.onEvent(MfaChallengeEvent.ClearError)
            assertNull(vm.state.value.error)
        }
}
