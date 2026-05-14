package io.github.b150005.skeinly.ui.biometric

import io.github.b150005.skeinly.biometric.BiometricAvailability
import io.github.b150005.skeinly.biometric.FakeBiometricPreferences
import io.github.b150005.skeinly.data.preferences.ThresholdChoice
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
import kotlin.test.assertTrue

/**
 * Phase 26.6 (ADR-022 §6.5) — locks the BiometricSettingsViewModel
 * state machine. Tests use [FakeBiometricPreferences] + lambda stubs
 * for `queryAvailability` so the BiometricAuthenticator expect/actual
 * surface stays out of commonTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BiometricSettingsViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init reads preferences + availability into state`() =
        runTest {
            val prefs =
                FakeBiometricPreferences(
                    initialEnabled = true,
                    initialThresholdSeconds = ThresholdChoice.FifteenMinutes.seconds,
                )
            val vm =
                BiometricSettingsViewModel(
                    preferences = prefs,
                    queryAvailability = { BiometricAvailability.Available },
                )
            val state = vm.state.value
            assertTrue(state.enabled)
            assertEquals(ThresholdChoice.FifteenMinutes, state.threshold)
            assertEquals(BiometricAvailability.Available, state.availability)
            assertTrue(state.canToggle)
        }

    @Test
    fun `toggle ON when availability is Available persists to preferences`() =
        runTest {
            val prefs = FakeBiometricPreferences(initialEnabled = false)
            val vm =
                BiometricSettingsViewModel(
                    preferences = prefs,
                    queryAvailability = { BiometricAvailability.Available },
                )
            vm.onEvent(BiometricSettingsEvent.ToggleEnabled(true))
            assertTrue(prefs.biometricEnabled.value)
            assertTrue(vm.state.value.enabled)
        }

    @Test
    fun `toggle ON is blocked when availability is NotEnrolled`() =
        runTest {
            val prefs = FakeBiometricPreferences(initialEnabled = false)
            val vm =
                BiometricSettingsViewModel(
                    preferences = prefs,
                    queryAvailability = { BiometricAvailability.NotEnrolled },
                )
            vm.onEvent(BiometricSettingsEvent.ToggleEnabled(true))
            assertFalse(prefs.biometricEnabled.value, "preferences must stay OFF when OS can't satisfy gate")
            assertFalse(vm.state.value.enabled)
        }

    @Test
    fun `toggle OFF always persists regardless of availability`() =
        runTest {
            val prefs = FakeBiometricPreferences(initialEnabled = true)
            val vm =
                BiometricSettingsViewModel(
                    preferences = prefs,
                    queryAvailability = { BiometricAvailability.NoHardware },
                )
            vm.onEvent(BiometricSettingsEvent.ToggleEnabled(false))
            assertFalse(prefs.biometricEnabled.value)
        }

    @Test
    fun `SelectThreshold persists the chosen value`() =
        runTest {
            val prefs = FakeBiometricPreferences(initialEnabled = true)
            val vm =
                BiometricSettingsViewModel(
                    preferences = prefs,
                    queryAvailability = { BiometricAvailability.Available },
                )
            vm.onEvent(BiometricSettingsEvent.SelectThreshold(ThresholdChoice.OneHour))
            assertEquals(ThresholdChoice.OneHour.seconds, prefs.reauthThresholdSeconds.value)
            assertEquals(ThresholdChoice.OneHour, vm.state.value.threshold)
        }

    @Test
    fun `preferences flow change reflects in VM state`() =
        runTest {
            val prefs = FakeBiometricPreferences(initialEnabled = false)
            val vm =
                BiometricSettingsViewModel(
                    preferences = prefs,
                    queryAvailability = { BiometricAvailability.Available },
                )
            assertFalse(vm.state.value.enabled)
            // External flip — e.g. through another VM instance or a
            // future deep-link — surfaces here through the
            // observePreferences collector.
            prefs.setBiometricEnabled(true)
            assertTrue(vm.state.value.enabled)
        }

    @Test
    fun `state canToggle is false when availability misses`() =
        runTest {
            val prefs = FakeBiometricPreferences()
            val vm =
                BiometricSettingsViewModel(
                    preferences = prefs,
                    queryAvailability = { BiometricAvailability.NoHardware },
                )
            assertFalse(vm.state.value.canToggle)
        }

    @Test
    fun `toggle ON re-queries availability at the toggle moment`() =
        runTest {
            // Initial availability is NoHardware; the user walks through
            // OS Settings to enroll biometric; the re-query at toggle
            // time picks up Available + the toggle succeeds.
            val prefs = FakeBiometricPreferences(initialEnabled = false)
            var availability = BiometricAvailability.NoHardware
            val vm =
                BiometricSettingsViewModel(
                    preferences = prefs,
                    queryAvailability = { availability },
                )
            assertFalse(vm.state.value.canToggle, "initial availability is NoHardware")
            availability = BiometricAvailability.Available
            vm.onEvent(BiometricSettingsEvent.ToggleEnabled(true))
            assertTrue(prefs.biometricEnabled.value)
            assertTrue(vm.state.value.canToggle)
        }
}
