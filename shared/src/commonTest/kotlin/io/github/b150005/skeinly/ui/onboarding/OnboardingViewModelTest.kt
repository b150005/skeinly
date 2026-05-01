package io.github.b150005.skeinly.ui.onboarding

import app.cash.turbine.test
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.RecordingAnalyticsTracker
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferences
import io.github.b150005.skeinly.data.preferences.FakeOnboardingPreferences
import io.github.b150005.skeinly.domain.usecase.CompleteOnboardingUseCase
import io.github.b150005.skeinly.domain.usecase.GetOnboardingCompletedUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val preferences = FakeOnboardingPreferences()
    private val getOnboardingCompleted = GetOnboardingCompletedUseCase(preferences)
    private val completeOnboarding = CompleteOnboardingUseCase(preferences)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        analyticsTracker: RecordingAnalyticsTracker? = null,
        analyticsPreferences: AnalyticsPreferences? = null,
        includeBetaConsent: Boolean = false,
    ) = OnboardingViewModel(
        getOnboardingCompleted = getOnboardingCompleted,
        completeOnboarding = completeOnboarding,
        analyticsTracker = analyticsTracker,
        analyticsPreferences = analyticsPreferences,
        includeBetaConsent = includeBetaConsent,
    )

    @Test
    fun `initial state has currentPage 0 and isCompleted false`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(0, state.currentPage)
                assertFalse(state.isCompleted)
            }
        }

    @Test
    fun `pages list has 3 items`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                assertEquals(3, awaitItem().pages.size)
            }
        }

    @Test
    fun `NextPage advances currentPage by 1`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                assertEquals(0, awaitItem().currentPage)
                viewModel.onEvent(OnboardingEvent.NextPage)
                assertEquals(1, awaitItem().currentPage)
            }
        }

    @Test
    fun `NextPage does not exceed last page index`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                awaitItem() // initial
                // Advance to last page
                viewModel.onEvent(OnboardingEvent.NextPage)
                awaitItem() // page 1
                viewModel.onEvent(OnboardingEvent.NextPage)
                assertEquals(2, awaitItem().currentPage) // page 2 (last)
                // Try to go beyond — no state change, so no emission
                viewModel.onEvent(OnboardingEvent.NextPage)
                expectNoEvents()
                // Verify value directly
                assertEquals(2, viewModel.state.value.currentPage)
            }
        }

    @Test
    fun `PreviousPage decrements currentPage by 1`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                awaitItem() // initial page 0
                viewModel.onEvent(OnboardingEvent.NextPage)
                assertEquals(1, awaitItem().currentPage)
                viewModel.onEvent(OnboardingEvent.PreviousPage)
                assertEquals(0, awaitItem().currentPage)
            }
        }

    @Test
    fun `PreviousPage does not go below 0`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                assertEquals(0, awaitItem().currentPage)
                // Already at 0 — no state change, so no emission
                viewModel.onEvent(OnboardingEvent.PreviousPage)
                expectNoEvents()
                assertEquals(0, viewModel.state.value.currentPage)
            }
        }

    @Test
    fun `Skip completes onboarding`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                awaitItem() // initial
                viewModel.onEvent(OnboardingEvent.Skip)
                assertTrue(awaitItem().isCompleted)
            }
            assertTrue(preferences.hasSeenOnboarding)
        }

    @Test
    fun `Complete persists the onboarding flag`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                awaitItem() // initial
                viewModel.onEvent(OnboardingEvent.Complete)
                assertTrue(awaitItem().isCompleted)
            }
            assertTrue(preferences.hasSeenOnboarding)
        }

    @Test
    fun `already completed onboarding shows isCompleted true`() =
        runTest {
            preferences.markOnboardingComplete()
            val viewModel = createViewModel()
            viewModel.state.test {
                assertTrue(awaitItem().isCompleted)
            }
        }

    @Test
    fun `PageChanged updates currentPage`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                assertEquals(0, awaitItem().currentPage)
                viewModel.onEvent(OnboardingEvent.PageChanged(2))
                assertEquals(2, awaitItem().currentPage)
            }
        }

    @Test
    fun `PageChanged clamps negative values to 0`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                awaitItem() // initial
                viewModel.onEvent(OnboardingEvent.PageChanged(-1))
                // Clamped to 0 — same as initial, so no emission
                expectNoEvents()
                assertEquals(0, viewModel.state.value.currentPage)
            }
        }

    @Test
    fun `PageChanged clamps out-of-range values to last index`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                assertEquals(0, awaitItem().currentPage)
                viewModel.onEvent(OnboardingEvent.PageChanged(99))
                assertEquals(2, awaitItem().currentPage) // clamped to lastIndex
            }
        }

    @Test
    fun `Skip captures onboarding_completed analytics event`() =
        runTest {
            val tracker = RecordingAnalyticsTracker()
            val viewModel = createViewModel(tracker)
            viewModel.onEvent(OnboardingEvent.Skip)
            assertEquals(
                listOf<AnalyticsEvent>(AnalyticsEvent.OnboardingCompleted),
                tracker.outcomeEvents,
            )
        }

    @Test
    fun `Complete captures onboarding_completed analytics event`() =
        runTest {
            val tracker = RecordingAnalyticsTracker()
            val viewModel = createViewModel(tracker)
            viewModel.onEvent(OnboardingEvent.Complete)
            assertEquals(
                listOf<AnalyticsEvent>(AnalyticsEvent.OnboardingCompleted),
                tracker.outcomeEvents,
            )
        }

    @Test
    fun `Complete does not double-capture when already completed`() =
        runTest {
            preferences.markOnboardingComplete()
            val tracker = RecordingAnalyticsTracker()
            val viewModel = createViewModel(tracker)
            viewModel.onEvent(OnboardingEvent.Complete)
            assertTrue(
                tracker.outcomeEvents.isEmpty(),
                "no event should fire when transitioning from completed to completed",
            )
        }

    // Phase 39.4 (ADR-015 §6) — beta-gated 4th page coverage.

    @Test
    fun `pages list has 3 items when includeBetaConsent is false`() =
        runTest {
            val viewModel = createViewModel(includeBetaConsent = false)
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(3, state.pages.size)
                // No diagnostic-data sentinel must surface in production.
                assertEquals(
                    listOf("home", "add_circle", "favorite"),
                    state.pages.map { it.iconName },
                )
            }
        }

    @Test
    fun `pages list has 4 items when includeBetaConsent is true`() =
        runTest {
            val viewModel =
                createViewModel(
                    analyticsPreferences = FakeAnalyticsPreferencesForOnboarding(),
                    includeBetaConsent = true,
                )
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(4, state.pages.size)
                assertEquals(
                    "diagnostic_data",
                    state.pages.last().iconName,
                    "consent page must be the LAST page so the user sees it after the value props",
                )
            }
        }

    @Test
    fun `SetAnalyticsOptIn writes through to AnalyticsPreferences`() =
        runTest {
            val prefs = FakeAnalyticsPreferencesForOnboarding()
            val viewModel = createViewModel(analyticsPreferences = prefs, includeBetaConsent = true)
            viewModel.onEvent(OnboardingEvent.SetAnalyticsOptIn(true))
            assertTrue(prefs.analyticsOptIn.value, "preference must persist immediately on toggle")
            // State mirrors the preference via the init-block observer.
            viewModel.state.test {
                assertTrue(awaitItem().analyticsOptIn)
            }
            viewModel.onEvent(OnboardingEvent.SetAnalyticsOptIn(false))
            assertFalse(prefs.analyticsOptIn.value)
        }

    @Test
    fun `state reflects persisted analyticsOptIn at init`() =
        runTest {
            val prefs = FakeAnalyticsPreferencesForOnboarding()
            prefs.setAnalyticsOptIn(true)
            val viewModel = createViewModel(analyticsPreferences = prefs, includeBetaConsent = true)
            viewModel.state.test {
                assertTrue(
                    awaitItem().analyticsOptIn,
                    "init must mirror the persisted preference value rather than always default OFF",
                )
            }
        }

    @Test
    fun `SetAnalyticsOptIn no-ops when AnalyticsPreferences is null`() =
        runTest {
            // includeBetaConsent = false (production) + analyticsPreferences = null.
            // The event must not throw — the screen will never dispatch
            // it under this config, but defensive against a future
            // refactor that fires it from an unrelated code path.
            val viewModel = createViewModel(includeBetaConsent = false)
            viewModel.onEvent(OnboardingEvent.SetAnalyticsOptIn(true))
            // No exception, no state change.
            viewModel.state.test {
                assertFalse(awaitItem().analyticsOptIn)
            }
        }
}

private class FakeAnalyticsPreferencesForOnboarding : AnalyticsPreferences {
    private val flow = MutableStateFlow(false)
    override val analyticsOptIn: StateFlow<Boolean> = flow.asStateFlow()

    override fun setAnalyticsOptIn(value: Boolean) {
        flow.value = value
    }
}
