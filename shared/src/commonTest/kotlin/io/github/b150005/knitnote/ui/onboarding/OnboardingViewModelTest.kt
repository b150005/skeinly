package io.github.b150005.knitnote.ui.onboarding

import app.cash.turbine.test
import io.github.b150005.knitnote.data.preferences.FakeOnboardingPreferences
import io.github.b150005.knitnote.domain.usecase.CompleteOnboardingUseCase
import io.github.b150005.knitnote.domain.usecase.GetOnboardingCompletedUseCase
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

    private fun createViewModel() = OnboardingViewModel(getOnboardingCompleted, completeOnboarding)

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
}
