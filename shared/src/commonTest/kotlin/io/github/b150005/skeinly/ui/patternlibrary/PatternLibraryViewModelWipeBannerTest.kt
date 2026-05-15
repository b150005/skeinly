package io.github.b150005.skeinly.ui.patternlibrary

import app.cash.turbine.test
import io.github.b150005.skeinly.data.wipe.WipeCompletionNotifier
import io.github.b150005.skeinly.domain.usecase.DeletePatternUseCase
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.domain.usecase.FakePatternRepository
import io.github.b150005.skeinly.domain.usecase.GetPatternsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
 * Phase 27.2 (ADR-023 §UX) — covers the [WipeCompletionNotifier] →
 * [PatternLibraryViewModel] banner-state contract:
 *
 * - Initial state: banner hidden.
 * - On notifier emission: banner becomes visible.
 * - After [WIPE_BANNER_DURATION_MS] virtual time elapses: banner
 *   auto-clears.
 * - On manual [PatternLibraryEvent.DismissWipeBanner]: banner clears
 *   immediately + the auto-timer is cancelled (so a late tick can't
 *   re-clear an already-cleared state).
 * - On back-to-back emissions: the 8 s window restarts from the latest
 *   emission rather than from the first (no accidental early clear).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PatternLibraryViewModelWipeBannerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val patternRepository = FakePatternRepository()
    private val authRepository = FakeAuthRepository()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(notifier: WipeCompletionNotifier): PatternLibraryViewModel {
        val getPatterns = GetPatternsUseCase(patternRepository, authRepository)
        val deletePattern = DeletePatternUseCase(patternRepository)
        return PatternLibraryViewModel(
            getPatterns = getPatterns,
            deletePattern = deletePattern,
            wipeCompletionNotifier = notifier,
        )
    }

    @Test
    fun `notifier emit flips wipeBannerVisible to true`() =
        runTest {
            val notifier = WipeCompletionNotifier()
            val viewModel = createViewModel(notifier)

            viewModel.state.test {
                // Initial state: banner hidden + isLoading=true.
                assertFalse(awaitItem().wipeBannerVisible)
                // patternsFlow emits empty list → second emission with
                // isLoading flipping to false.
                val loaded = awaitItem()
                assertFalse(loaded.wipeBannerVisible)
                assertFalse(loaded.isLoading)

                // Let the init's events.collect launch reach its
                // suspension point BEFORE we emit, otherwise the
                // SharedFlow drops the value (no replay).
                advanceUntilIdle()

                notifier.notify()
                val withBanner = awaitItem()
                assertTrue(withBanner.wipeBannerVisible)
            }
        }

    @Test
    fun `banner auto-clears after WIPE_BANNER_DURATION_MS`() =
        runTest {
            val notifier = WipeCompletionNotifier()
            val viewModel = createViewModel(notifier)

            viewModel.state.test {
                awaitItem() // initial
                awaitItem() // patterns loaded (isLoading flips)
                advanceUntilIdle()

                notifier.notify()
                assertTrue(awaitItem().wipeBannerVisible)

                advanceTimeBy(WIPE_BANNER_DURATION_MS + 100)
                val cleared = awaitItem()
                assertFalse(cleared.wipeBannerVisible)
            }
        }

    @Test
    fun `banner still visible just before the 8 s mark`() =
        runTest {
            val notifier = WipeCompletionNotifier()
            val viewModel = createViewModel(notifier)

            viewModel.state.test {
                awaitItem()
                awaitItem()
                advanceUntilIdle()

                notifier.notify()
                assertTrue(awaitItem().wipeBannerVisible)

                advanceTimeBy(WIPE_BANNER_DURATION_MS - 100)
                expectNoEvents()
            }
        }

    @Test
    fun `DismissWipeBanner event clears banner immediately`() =
        runTest {
            val notifier = WipeCompletionNotifier()
            val viewModel = createViewModel(notifier)

            viewModel.state.test {
                awaitItem()
                awaitItem()
                advanceUntilIdle()

                notifier.notify()
                assertTrue(awaitItem().wipeBannerVisible)

                viewModel.onEvent(PatternLibraryEvent.DismissWipeBanner)
                val cleared = awaitItem()
                assertFalse(cleared.wipeBannerVisible)
            }
        }

    @Test
    fun `DismissWipeBanner cancels pending auto-clear timer`() =
        runTest {
            val notifier = WipeCompletionNotifier()
            val viewModel = createViewModel(notifier)

            viewModel.state.test {
                awaitItem()
                awaitItem()
                advanceUntilIdle()

                notifier.notify()
                assertTrue(awaitItem().wipeBannerVisible)

                viewModel.onEvent(PatternLibraryEvent.DismissWipeBanner)
                assertFalse(awaitItem().wipeBannerVisible)

                // Drain past the original 8 s window. The pending timer
                // would have set wipeBannerVisible = false again, but
                // the dismiss event already cancelled it. Assert no
                // emission fires within the would-be window.
                advanceTimeBy(WIPE_BANNER_DURATION_MS + 1000)
                expectNoEvents()
            }
        }

    @Test
    fun `back-to-back notifier emissions restart the 8 s window`() =
        runTest {
            val notifier = WipeCompletionNotifier()
            val viewModel = createViewModel(notifier)

            viewModel.state.test {
                awaitItem()
                awaitItem()
                advanceUntilIdle()

                notifier.notify()
                assertTrue(awaitItem().wipeBannerVisible)

                // Wait halfway through the window, then emit again.
                // `update { it.copy(wipeBannerVisible = true) }` on an
                // already-true StateFlow is a no-op (StateFlow dedupes
                // by equality) — so the second emit produces NO new
                // state emission, but its internal effect is to cancel
                // the prior auto-clear job + start a new 8 s timer.
                advanceTimeBy(WIPE_BANNER_DURATION_MS / 2)
                expectNoEvents()
                notifier.notify()
                expectNoEvents()

                // If the timer correctly restarted from the SECOND
                // emission, the banner should still be up at
                // `t = window/2 + window - 200` (well past the
                // ORIGINAL window's expiry at `t = window`).
                advanceTimeBy(WIPE_BANNER_DURATION_MS - 200)
                expectNoEvents()

                // Cross the second window boundary.
                advanceTimeBy(400)
                assertFalse(awaitItem().wipeBannerVisible)
            }
        }

    @Test
    fun `WIPE_BANNER_DURATION_MS equals 8 seconds`() {
        // ADR-023 §UX names "8 s" as the banner display duration. This
        // test pins the contract so a tuning commit can't silently
        // diverge from the user-facing copy that says "your data has
        // been deleted" without re-reading the ADR.
        assertEquals(8_000L, WIPE_BANNER_DURATION_MS)
    }
}
