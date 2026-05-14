package io.github.b150005.skeinly.ui.auth

import io.github.b150005.skeinly.domain.model.MfaEnrollment
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 26.5 (ADR-022 §6.4) — locks the MfaEnrollmentViewModel state
 * machine. Tests use lambda stubs to inject [enrollMfaTotp] +
 * [verifyMfaEnrollment] without standing up supabase-kt; the
 * production wiring at [io.github.b150005.skeinly.di.viewModelModule]
 * binds them to AuthRepository method references.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MfaEnrollmentViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val sampleEnrollment =
        MfaEnrollment(
            factorId = "factor-1",
            secret = "JBSWY3DPEHPK3PXP",
            otpAuthUri = "otpauth://totp/Skeinly?secret=JBSWY3DPEHPK3PXP",
            recoveryCode = "ABCD-EFGH-JKLM-NPQR",
        )

    @Test
    fun `Start kicks enrollment and lands on PairingQr with envelope`() =
        runTest {
            val vm =
                MfaEnrollmentViewModel(
                    enrollMfaTotp = { sampleEnrollment },
                    verifyMfaEnrollment = { _, _ -> },
                )

            vm.onEvent(MfaEnrollmentEvent.Start)
            val settled = vm.state.value
            assertEquals(sampleEnrollment, settled.enrollment)
            assertEquals(MfaEnrollmentPhase.PairingQr, settled.phase)
            assertNull(settled.error)
            assertFalseValue(settled.isSubmitting)
        }

    @Test
    fun `Start surfaces EnrollFailed error on repo throw`() =
        runTest {
            val vm =
                MfaEnrollmentViewModel(
                    enrollMfaTotp = { throw IllegalStateException("network") },
                    verifyMfaEnrollment = { _, _ -> },
                )
            vm.onEvent(MfaEnrollmentEvent.Start)
            val errored = vm.state.value
            assertEquals(MfaEnrollmentError.EnrollFailed, errored.error)
            assertNull(errored.enrollment)
            assertFalseValue(errored.isSubmitting)
        }

    private fun assertFalseValue(actual: Boolean) {
        if (actual) error("expected false but got true")
    }

    @Test
    fun `Start is idempotent — second call after success no-ops`() =
        runTest {
            var callCount = 0
            val vm =
                MfaEnrollmentViewModel(
                    enrollMfaTotp = {
                        callCount++
                        sampleEnrollment
                    },
                    verifyMfaEnrollment = { _, _ -> },
                )
            vm.onEvent(MfaEnrollmentEvent.Start)
            vm.onEvent(MfaEnrollmentEvent.Start)
            assertEquals(1, callCount)
        }

    @Test
    fun `UpdateCode filters non-digits and caps at 6 chars`() =
        runTest {
            val vm =
                MfaEnrollmentViewModel(
                    enrollMfaTotp = { sampleEnrollment },
                    verifyMfaEnrollment = { _, _ -> },
                )
            vm.onEvent(MfaEnrollmentEvent.UpdateCode("12 34-56-78"))
            assertEquals("123456", vm.state.value.codeInput)
        }

    @Test
    fun `AdvanceToConfirm flips phase to ConfirmCode`() =
        runTest {
            val vm =
                MfaEnrollmentViewModel(
                    enrollMfaTotp = { sampleEnrollment },
                    verifyMfaEnrollment = { _, _ -> },
                )
            vm.onEvent(MfaEnrollmentEvent.Start)
            vm.onEvent(MfaEnrollmentEvent.AdvanceToConfirm)
            assertEquals(MfaEnrollmentPhase.ConfirmCode, vm.state.value.phase)
        }

    @Test
    fun `SubmitCode with short code sets InvalidCode error without calling repo`() =
        runTest {
            var verifyCount = 0
            val vm =
                MfaEnrollmentViewModel(
                    enrollMfaTotp = { sampleEnrollment },
                    verifyMfaEnrollment = { _, _ -> verifyCount++ },
                )
            vm.onEvent(MfaEnrollmentEvent.Start)
            vm.onEvent(MfaEnrollmentEvent.AdvanceToConfirm)
            vm.onEvent(MfaEnrollmentEvent.UpdateCode("123"))
            vm.onEvent(MfaEnrollmentEvent.SubmitCode)
            assertEquals(0, verifyCount)
            assertEquals(MfaEnrollmentError.InvalidCode, vm.state.value.error)
        }

    @Test
    fun `SubmitCode happy path advances to RecoveryCodeDisplay`() =
        runTest {
            var factorIdSeen: String? = null
            var codeSeen: String? = null
            val vm =
                MfaEnrollmentViewModel(
                    enrollMfaTotp = { sampleEnrollment },
                    verifyMfaEnrollment = { factorId, code ->
                        factorIdSeen = factorId
                        codeSeen = code
                    },
                )
            vm.onEvent(MfaEnrollmentEvent.Start)
            vm.onEvent(MfaEnrollmentEvent.AdvanceToConfirm)
            vm.onEvent(MfaEnrollmentEvent.UpdateCode("654321"))
            vm.onEvent(MfaEnrollmentEvent.SubmitCode)
            assertEquals("factor-1", factorIdSeen)
            assertEquals("654321", codeSeen)
            assertEquals(MfaEnrollmentPhase.RecoveryCodeDisplay, vm.state.value.phase)
            // Submitting flag should be cleared post-verify-success.
            assertTrue(!vm.state.value.isSubmitting)
        }

    @Test
    fun `SubmitCode failure surfaces InvalidCode and keeps phase ConfirmCode`() =
        runTest {
            val vm =
                MfaEnrollmentViewModel(
                    enrollMfaTotp = { sampleEnrollment },
                    verifyMfaEnrollment = { _, _ ->
                        throw IllegalStateException("bad code")
                    },
                )
            vm.onEvent(MfaEnrollmentEvent.Start)
            vm.onEvent(MfaEnrollmentEvent.AdvanceToConfirm)
            vm.onEvent(MfaEnrollmentEvent.UpdateCode("000000"))
            vm.onEvent(MfaEnrollmentEvent.SubmitCode)
            assertEquals(MfaEnrollmentError.InvalidCode, vm.state.value.error)
            assertEquals(MfaEnrollmentPhase.ConfirmCode, vm.state.value.phase)
        }

    @Test
    fun `DismissRecoveryCode flips completed flag and drops enrollment`() =
        runTest {
            val vm =
                MfaEnrollmentViewModel(
                    enrollMfaTotp = { sampleEnrollment },
                    verifyMfaEnrollment = { _, _ -> },
                )
            vm.onEvent(MfaEnrollmentEvent.Start)
            vm.onEvent(MfaEnrollmentEvent.DismissRecoveryCode)
            assertTrue(vm.state.value.completed)
            assertNull(vm.state.value.enrollment)
        }

    @Test
    fun `ClearError drops the error field`() =
        runTest {
            val vm =
                MfaEnrollmentViewModel(
                    enrollMfaTotp = { throw IllegalStateException("network") },
                    verifyMfaEnrollment = { _, _ -> },
                )
            vm.onEvent(MfaEnrollmentEvent.Start)
            assertEquals(MfaEnrollmentError.EnrollFailed, vm.state.value.error)
            vm.onEvent(MfaEnrollmentEvent.ClearError)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `MfaEnrollment data class supports copy and equals`() {
        val original = sampleEnrollment
        val mutated = original.copy(secret = "different-secret")
        assertEquals(original.factorId, mutated.factorId)
        assertEquals("different-secret", mutated.secret)
        assertEquals(original, original.copy())
        assertTrue(original != mutated)
    }

    @Test
    fun `MfaEnrollmentStatus closed enum variants are distinct`() {
        val enrolled =
            io.github.b150005.skeinly.domain.model.MfaEnrollmentStatus
                .Enrolled("f1")
        val unverified =
            io.github.b150005.skeinly.domain.model.MfaEnrollmentStatus
                .EnrolledUnverified("f1")
        // Different concrete types — Enrolled and EnrolledUnverified are distinct.
        assertTrue((enrolled as Any) != (unverified as Any))
        // Different factor IDs produce distinct Enrolled values.
        assertTrue(
            enrolled !=
                io.github.b150005.skeinly.domain.model.MfaEnrollmentStatus
                    .Enrolled("f2"),
        )
        assertEquals(enrolled, enrolled.copy())
    }

    @Test
    fun `SubmitCode re-entry guarded by isSubmitting`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var verifyCount = 0
            val vm =
                MfaEnrollmentViewModel(
                    enrollMfaTotp = { sampleEnrollment },
                    verifyMfaEnrollment = { _, _ ->
                        verifyCount++
                        gate.await()
                    },
                )
            vm.onEvent(MfaEnrollmentEvent.Start)
            vm.onEvent(MfaEnrollmentEvent.AdvanceToConfirm)
            vm.onEvent(MfaEnrollmentEvent.UpdateCode("111111"))
            vm.onEvent(MfaEnrollmentEvent.SubmitCode)
            vm.onEvent(MfaEnrollmentEvent.SubmitCode) // second tap while in-flight
            gate.complete(Unit)
            assertEquals(1, verifyCount)
        }
}
