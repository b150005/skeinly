package io.github.b150005.skeinly.data.wipe

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 27.2 (ADR-023 §UX) — direct contract for the [WipeCompletionNotifier]
 * singleton event bus. The higher-level usage is covered by
 * [io.github.b150005.skeinly.ui.patternlibrary.PatternLibraryViewModelWipeBannerTest]
 * but those tests are coupled to the VM. These cases pin the notifier's
 * own contract:
 *
 * - One subscriber observes each emit exactly once.
 * - `replay = 0` — a late subscriber does NOT observe past emits.
 * - `extraBufferCapacity = 1` — a synchronous emit does not suspend on
 *   the first emission even if no collector is active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WipeCompletionNotifierTest {
    @Test
    fun `notify is observed by an active subscriber`() =
        runTest {
            val notifier = WipeCompletionNotifier()
            notifier.events.test {
                notifier.notify()
                awaitItem()
                expectNoEvents()
            }
        }

    @Test
    fun `replay zero — late subscriber misses past emits`() =
        runTest {
            val notifier = WipeCompletionNotifier()
            // Emit BEFORE any collector — with replay=0, the value is
            // delivered into the buffer but no one is around to read
            // it; a subsequent collector starts from "now" and sees
            // nothing.
            notifier.notify()

            notifier.events.test {
                expectNoEvents()
            }
        }

    @Test
    fun `multiple emits while subscribed all observed in order`() =
        runTest {
            val notifier = WipeCompletionNotifier()
            val received = mutableListOf<Unit>()
            notifier.events.test {
                notifier.notify()
                received.add(awaitItem())
                notifier.notify()
                received.add(awaitItem())
                notifier.notify()
                received.add(awaitItem())
                expectNoEvents()
            }
            assertEquals(3, received.size)
        }
}
