package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.realtime.FakeRealtimeChannelProvider
import io.github.b150005.knitnote.data.remote.FakeRemoteActivityDataSource
import io.github.b150005.knitnote.domain.model.Activity
import io.github.b150005.knitnote.domain.model.ActivityTargetType
import io.github.b150005.knitnote.domain.model.ActivityType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityRepositoryImplTest {
    private lateinit var remote: FakeRemoteActivityDataSource
    private lateinit var channelProvider: FakeRealtimeChannelProvider
    private lateinit var repository: ActivityRepositoryImpl

    @BeforeTest
    fun setUp() {
        remote = FakeRemoteActivityDataSource()
        channelProvider = FakeRealtimeChannelProvider()
        repository =
            ActivityRepositoryImpl(
                remote = remote,
                channelProvider = channelProvider,
                scope = CoroutineScope(Dispatchers.Unconfined),
            )
    }

    private fun testActivity(
        id: String = "activity-1",
        userId: String = "user-1",
        type: ActivityType = ActivityType.SHARED,
    ) = Activity(
        id = id,
        userId = userId,
        type = type,
        targetType = ActivityTargetType.PATTERN,
        targetId = "target-1",
        metadata = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    // -- Delegation tests --

    @Test
    fun `getByUserId delegates to remote`() =
        runTest {
            remote.addActivity(testActivity(id = "a1", userId = "user-1"))
            remote.addActivity(testActivity(id = "a2", userId = "user-2"))
            val result = repository.getByUserId("user-1")
            assertEquals(1, result.size)
            assertEquals("a1", result[0].id)
        }

    @Test
    fun `create delegates to remote insert`() =
        runTest {
            val activity = testActivity()
            val created = repository.create(activity)
            assertEquals(activity.id, created.id)
        }

    // -- Realtime subscription tests --

    @Test
    fun `observeByUserId seeds from remote`() =
        runTest {
            remote.addActivity(testActivity(id = "a1", userId = "user-1"))
            remote.addActivity(testActivity(id = "a2", userId = "user-1"))

            val activities = repository.observeByUserId("user-1").first()
            assertEquals(2, activities.size)
        }

    @Test
    fun `observeByUserId creates channel with correct name`() =
        runTest {
            repository.observeByUserId("user-1").first()

            val handle = channelProvider.channelFor("activities-user-1")
            assertNotNull(handle)
            assertEquals("activities", handle.subscribedTable)
            assertTrue(handle.subscribed)
        }

    @Test
    fun `observeByUserId filters activities for correct user`() =
        runTest {
            remote.addActivity(testActivity(id = "a1", userId = "user-1"))
            remote.addActivity(testActivity(id = "a2", userId = "user-2"))

            val activities = repository.observeByUserId("user-1").first()
            assertEquals(1, activities.size)
            assertEquals("a1", activities[0].id)
        }

    @Test
    fun `closeChannel unsubscribes and clears state`() =
        runTest {
            repository.observeByUserId("user-1").first()

            val handle = channelProvider.channelFor("activities-user-1")
            assertNotNull(handle)
            assertTrue(handle.subscribed)

            repository.closeChannel()

            assertTrue(handle.unsubscribed)
        }

    @Test
    fun `channel swap on user change closes old channel`() =
        runTest {
            repository.observeByUserId("user-1").first()
            val oldHandle = channelProvider.channelFor("activities-user-1")
            assertNotNull(oldHandle)

            repository.closeChannel()
            repository.observeByUserId("user-2").first()

            assertTrue(oldHandle.unsubscribed)

            val newHandle = channelProvider.channelFor("activities-user-2")
            assertNotNull(newHandle)
            assertTrue(newHandle.subscribed)
        }

    // NOTE: Event path tests (PostgresAction.Insert/Update/Delete emission via FakeChannelHandle.events)
    // are deferred because PostgresAction construction requires Supabase-internal types
    // (SupabaseSerializer, kotlinx.datetime.Instant). The event handling logic
    // (handleActivityAction) is simple list manipulation and is tested indirectly via
    // RealtimeSyncManagerTest Part 1 data layer contract tests.
    // Future improvement: abstract the event decoding in ChannelHandle to decouple from
    // PostgresAction entirely.
}
