package io.github.b150005.skeinly.data.sync

import io.github.b150005.skeinly.data.local.LocalChartVersionDataSource
import io.github.b150005.skeinly.data.local.LocalPatternDataSource
import io.github.b150005.skeinly.data.local.LocalProgressDataSource
import io.github.b150005.skeinly.data.local.LocalProjectDataSource
import io.github.b150005.skeinly.data.local.LocalProjectSegmentDataSource
import io.github.b150005.skeinly.data.local.LocalSuggestionDataSource
import io.github.b150005.skeinly.data.realtime.FakeRealtimeChannelProvider
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.db.createTestDriver
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.testJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 38.1: RealtimeSyncManager subscription contract for the two
 * suggestion channels (ADR-014 §7).
 *
 * Lives in its own test class so its assertions stay focused on the
 * suggestion-channel surface; the broader [RealtimeSyncManagerTest]
 * exercises connectivity + retry + the other five channels.
 */
class RealtimeSyncManagerSuggestionTest {
    private fun createManager(): Pair<RealtimeSyncManager, FakeRealtimeChannelProvider> {
        val driver = createTestDriver()
        val db = SkeinlyDatabase(driver)
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
                isOnline = kotlinx.coroutines.flow.MutableStateFlow(false),
                config = RealtimeConfig(),
                random = kotlin.random.Random(seed = 42),
                localChartVersion = LocalChartVersionDataSource(db, testDispatcher, testJson),
                localSuggestion = LocalSuggestionDataSource(db, testDispatcher),
            )
        return manager to channelProvider
    }

    @Test
    fun `subscribe creates suggestion incoming and outgoing channels alongside the others`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")

            // Phase 37.1 channels still present.
            assertNotNull(channelProvider.channelFor("projects-owner-1"))
            assertNotNull(channelProvider.channelFor("progress-owner-1"))
            assertNotNull(channelProvider.channelFor("patterns-owner-1"))
            assertNotNull(channelProvider.channelFor("project-segments-owner-1"))
            assertNotNull(channelProvider.channelFor("chart-versions-owner-1"))
            // Phase 38.1 additions — 6 + 7 channels.
            assertNotNull(channelProvider.channelFor("suggestions-incoming-owner-1"))
            assertNotNull(channelProvider.channelFor("suggestions-outgoing-owner-1"))
            assertEquals(7, channelProvider.createdChannels.size)
        }

    @Test
    fun `outgoing channel filters by author_id`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")

            val outgoingHandle = channelProvider.channelFor("suggestions-outgoing-owner-1")!!
            assertEquals("suggestions", outgoingHandle.subscribedTable)
            assertEquals("author_id", outgoingHandle.subscribedFilter?.column)
            assertEquals("owner-1", outgoingHandle.subscribedFilter?.value)
        }

    @Test
    fun `incoming channel uses no client-side filter and relies on RLS`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")

            val incomingHandle = channelProvider.channelFor("suggestions-incoming-owner-1")!!
            assertEquals("suggestions", incomingHandle.subscribedTable)
            // Single-eq ChangeFilter cannot express "target_pattern_id IN
            // owned_patterns"; ADR-014 §7 routes incoming through RLS scoping.
            assertNull(incomingHandle.subscribedFilter)
        }

    @Test
    fun `unsubscribe closes suggestion channels alongside the others`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")
            manager.unsubscribe()

            channelProvider.createdChannels.values.forEach { handle ->
                assertTrue(handle.unsubscribed)
            }
        }
}
