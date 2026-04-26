package io.github.b150005.knitnote.data.sync

import io.github.b150005.knitnote.data.local.LocalChartRevisionDataSource
import io.github.b150005.knitnote.data.local.LocalPatternDataSource
import io.github.b150005.knitnote.data.local.LocalProgressDataSource
import io.github.b150005.knitnote.data.local.LocalProjectDataSource
import io.github.b150005.knitnote.data.local.LocalProjectSegmentDataSource
import io.github.b150005.knitnote.data.local.LocalPullRequestDataSource
import io.github.b150005.knitnote.data.realtime.FakeRealtimeChannelProvider
import io.github.b150005.knitnote.db.KnitNoteDatabase
import io.github.b150005.knitnote.db.createTestDriver
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import io.github.b150005.knitnote.testJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 38.1: RealtimeSyncManager subscription contract for the two new
 * pull-request channels (ADR-014 §7).
 *
 * Lives in its own test class because the existing [RealtimeSyncManagerTest]
 * setup helper doesn't wire `localPullRequest` (kept null-default for
 * backward compatibility — the precedent set by Phase 37.1 chart_revisions).
 * Threading the new local data source through the existing helper would
 * widen its signature and force every other test in the file to learn about
 * a parameter they don't exercise; a dedicated class is the lighter touch.
 */
class RealtimeSyncManagerPullRequestTest {
    private fun createManager(): Pair<RealtimeSyncManager, FakeRealtimeChannelProvider> {
        val driver = createTestDriver()
        val db = KnitNoteDatabase(driver)
        val testDispatcher = Dispatchers.Unconfined
        val channelProvider = FakeRealtimeChannelProvider()
        val fakeAuth = FakeAuthRepository()

        val manager =
            RealtimeSyncManager(
                channelProvider = channelProvider,
                localProject = LocalProjectDataSource(db, testDispatcher),
                localProgress = LocalProgressDataSource(db, testDispatcher),
                localPattern = LocalPatternDataSource(db, testDispatcher),
                localProjectSegment = LocalProjectSegmentDataSource(db, testDispatcher),
                authRepository = fakeAuth,
                scope = CoroutineScope(Dispatchers.Unconfined),
                isOnline = null,
                config = RealtimeConfig(),
                random = kotlin.random.Random(seed = 42),
                localChartRevision = LocalChartRevisionDataSource(db, testDispatcher, testJson),
                localPullRequest = LocalPullRequestDataSource(db, testDispatcher),
            )
        return manager to channelProvider
    }

    @Test
    fun `subscribe creates pull-request incoming and outgoing channels alongside the others`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")

            // Phase 37.1 channels still present.
            assertNotNull(channelProvider.channelFor("projects-owner-1"))
            assertNotNull(channelProvider.channelFor("progress-owner-1"))
            assertNotNull(channelProvider.channelFor("patterns-owner-1"))
            assertNotNull(channelProvider.channelFor("project-segments-owner-1"))
            assertNotNull(channelProvider.channelFor("chart-revisions-owner-1"))
            // Phase 38.1 additions — 6 + 7 channels.
            assertNotNull(channelProvider.channelFor("pull-requests-incoming-owner-1"))
            assertNotNull(channelProvider.channelFor("pull-requests-outgoing-owner-1"))
            assertEquals(7, channelProvider.createdChannels.size)
        }

    @Test
    fun `outgoing channel filters by author_id`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")

            val outgoingHandle = channelProvider.channelFor("pull-requests-outgoing-owner-1")!!
            assertEquals("pull_requests", outgoingHandle.subscribedTable)
            assertEquals("author_id", outgoingHandle.subscribedFilter?.column)
            assertEquals("owner-1", outgoingHandle.subscribedFilter?.value)
        }

    @Test
    fun `incoming channel uses no client-side filter and relies on RLS`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")

            val incomingHandle = channelProvider.channelFor("pull-requests-incoming-owner-1")!!
            assertEquals("pull_requests", incomingHandle.subscribedTable)
            // Single-eq ChangeFilter cannot express "target_pattern_id IN
            // owned_patterns"; ADR-014 §7 routes incoming through RLS scoping.
            assertNull(incomingHandle.subscribedFilter)
        }

    @Test
    fun `unsubscribe closes pull-request channels alongside the others`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")
            manager.unsubscribe()

            channelProvider.createdChannels.values.forEach { handle ->
                assertTrue(handle.unsubscribed)
            }
        }
}
