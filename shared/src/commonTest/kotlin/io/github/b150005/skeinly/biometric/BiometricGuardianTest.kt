package io.github.b150005.skeinly.biometric

import io.github.b150005.skeinly.data.preferences.ThresholdChoice
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Phase 26.6 (ADR-022 §6.5) — locks the BiometricGuardian's
 * threshold-evaluation + availability-gate + mutex-serialization
 * semantics. The Guardian's `requireForResume` / `requireForAction`
 * stay testable because the BiometricAuthenticator dependency is
 * threaded as lambda seams (`queryAvailability` +
 * `performAuthentication`) — see Guardian constructor + DI module.
 */
@OptIn(ExperimentalTime::class)
class BiometricGuardianTest {
    /** Controllable Clock for deterministic threshold evaluation. */
    private class TestClock(
        initial: Instant = Instant.fromEpochSeconds(1_000_000),
    ) : Clock {
        var now: Instant = initial

        override fun now(): Instant = now

        fun advanceBySeconds(seconds: Long) {
            now = now.plus(seconds.seconds)
        }
    }

    private suspend fun stubStrings(copy: BiometricPromptCopy): String = copy.name

    @Test
    fun `requireForResume short-circuits to Success when biometric disabled`() =
        runTest {
            val prefs = FakeBiometricPreferences(initialEnabled = false)
            val guardian =
                BiometricGuardian(
                    queryAvailability = { error("should not query when disabled") },
                    performAuthentication = { _, _ -> error("should not prompt when disabled") },
                    preferences = prefs,
                    resolveString = ::stubStrings,
                    clock = TestClock(),
                )
            guardian.onBackgrounded()
            assertEquals(BiometricResult.Success, guardian.requireForResume())
        }

    @Test
    fun `requireForResume short-circuits when no prior background captured`() =
        runTest {
            val prefs = FakeBiometricPreferences(initialEnabled = true)
            val guardian =
                BiometricGuardian(
                    queryAvailability = { error("should not query on cold-start path") },
                    performAuthentication = { _, _ -> error("should not prompt on cold-start path") },
                    preferences = prefs,
                    resolveString = ::stubStrings,
                    clock = TestClock(),
                )
            // No onBackgrounded() call — cold-start path.
            assertEquals(BiometricResult.Success, guardian.requireForResume())
        }

    @Test
    fun `requireForResume short-circuits when threshold not yet exceeded`() =
        runTest {
            val prefs =
                FakeBiometricPreferences(
                    initialEnabled = true,
                    initialThresholdSeconds = ThresholdChoice.FiveMinutes.seconds,
                )
            val clock = TestClock()
            val guardian =
                BiometricGuardian(
                    queryAvailability = { error("threshold not exceeded — should not query") },
                    performAuthentication = { _, _ -> error("threshold not exceeded — should not prompt") },
                    preferences = prefs,
                    resolveString = ::stubStrings,
                    clock = clock,
                )
            guardian.onBackgrounded()
            // Only 60s — far short of the 300s threshold.
            clock.advanceBySeconds(60)
            assertEquals(BiometricResult.Success, guardian.requireForResume())
        }

    @Test
    fun `requireForResume fires prompt and returns Success on threshold + availability + user-pass`() =
        runTest {
            val prefs =
                FakeBiometricPreferences(
                    initialEnabled = true,
                    initialThresholdSeconds = ThresholdChoice.OneMinute.seconds,
                )
            val clock = TestClock()
            var promptCount = 0
            val guardian =
                BiometricGuardian(
                    queryAvailability = { BiometricAvailability.Available },
                    performAuthentication = { _, _ ->
                        promptCount++
                        BiometricResult.Success
                    },
                    preferences = prefs,
                    resolveString = ::stubStrings,
                    clock = clock,
                )
            guardian.onBackgrounded()
            clock.advanceBySeconds(120)
            assertEquals(BiometricResult.Success, guardian.requireForResume())
            assertEquals(1, promptCount, "OS prompt fires exactly once on threshold breach")
            // lastBackgroundedAt is cleared after the prompt — a follow-up
            // requireForResume without another Backgrounded event short-
            // circuits cleanly.
            clock.advanceBySeconds(120)
            assertEquals(BiometricResult.Success, guardian.requireForResume())
            assertEquals(1, promptCount, "no second prompt without another Background event")
        }

    @Test
    fun `requireForResume returns Failed when availability is not Available`() =
        runTest {
            val prefs =
                FakeBiometricPreferences(
                    initialEnabled = true,
                    initialThresholdSeconds = ThresholdChoice.OneMinute.seconds,
                )
            val clock = TestClock()
            val guardian =
                BiometricGuardian(
                    queryAvailability = { BiometricAvailability.NoHardware },
                    performAuthentication = { _, _ -> error("should not prompt when unavailable") },
                    preferences = prefs,
                    resolveString = ::stubStrings,
                    clock = clock,
                )
            guardian.onBackgrounded()
            clock.advanceBySeconds(120)
            assertEquals(BiometricResult.Failed, guardian.requireForResume())
        }

    @Test
    fun `requireForResume propagates Cancelled outcome from authenticator`() =
        runTest {
            val prefs =
                FakeBiometricPreferences(
                    initialEnabled = true,
                    initialThresholdSeconds = ThresholdChoice.OneMinute.seconds,
                )
            val clock = TestClock()
            val guardian =
                BiometricGuardian(
                    queryAvailability = { BiometricAvailability.Available },
                    performAuthentication = { _, _ -> BiometricResult.Cancelled },
                    preferences = prefs,
                    resolveString = ::stubStrings,
                    clock = clock,
                )
            guardian.onBackgrounded()
            clock.advanceBySeconds(120)
            assertEquals(BiometricResult.Cancelled, guardian.requireForResume())
        }

    @Test
    fun `requireForAction bypasses to Success when availability is NoHardware`() =
        runTest {
            val prefs = FakeBiometricPreferences(initialEnabled = false)
            val guardian =
                BiometricGuardian(
                    queryAvailability = { BiometricAvailability.NoHardware },
                    performAuthentication = { _, _ -> error("should not prompt on no-hardware device") },
                    preferences = prefs,
                    resolveString = ::stubStrings,
                    clock = TestClock(),
                )
            assertEquals(
                BiometricResult.Success,
                guardian.requireForAction(SensitiveAction.AccountDeletion),
            )
        }

    @Test
    fun `requireForAction surfaces Cancelled from the authenticator`() =
        runTest {
            val prefs = FakeBiometricPreferences(initialEnabled = false)
            val captured = mutableListOf<String>()
            val guardian =
                BiometricGuardian(
                    queryAvailability = { BiometricAvailability.Available },
                    performAuthentication = { _, reason ->
                        captured.add(reason)
                        BiometricResult.Cancelled
                    },
                    preferences = prefs,
                    resolveString = ::stubStrings,
                    clock = TestClock(),
                )
            val outcome = guardian.requireForAction(SensitiveAction.MfaDisable)
            assertEquals(BiometricResult.Cancelled, outcome)
            assertEquals(BiometricPromptCopy.MfaDisableReason.name, captured.single())
        }

    @Test
    fun `requireForAction routes AccountDeletion to the correct reason copy`() =
        runTest {
            val prefs = FakeBiometricPreferences(initialEnabled = false)
            val captured = mutableListOf<String>()
            val guardian =
                BiometricGuardian(
                    queryAvailability = { BiometricAvailability.Available },
                    performAuthentication = { _, reason ->
                        captured.add(reason)
                        BiometricResult.Success
                    },
                    preferences = prefs,
                    resolveString = ::stubStrings,
                    clock = TestClock(),
                )
            guardian.requireForAction(SensitiveAction.AccountDeletion)
            assertEquals(BiometricPromptCopy.AccountDeletionReason.name, captured.single())
        }

    @Test
    fun `requireForResume threshold edge case fires at exact boundary`() =
        runTest {
            val prefs =
                FakeBiometricPreferences(
                    initialEnabled = true,
                    initialThresholdSeconds = ThresholdChoice.OneMinute.seconds,
                )
            val clock = TestClock()
            var promptCount = 0
            val guardian =
                BiometricGuardian(
                    queryAvailability = { BiometricAvailability.Available },
                    performAuthentication = { _, _ ->
                        promptCount++
                        BiometricResult.Success
                    },
                    preferences = prefs,
                    resolveString = ::stubStrings,
                    clock = clock,
                )
            guardian.onBackgrounded()
            // Exactly equal to threshold — `elapsedSeconds < threshold` is
            // false, so the prompt fires.
            clock.advanceBySeconds(60)
            guardian.requireForResume()
            assertEquals(1, promptCount)
        }
}
