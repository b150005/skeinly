package io.github.b150005.knitnote.data.sync

import io.github.b150005.knitnote.data.local.LocalPatternDataSource
import io.github.b150005.knitnote.data.local.LocalProgressDataSource
import io.github.b150005.knitnote.data.local.LocalProjectDataSource
import io.github.b150005.knitnote.data.realtime.FakeRealtimeChannelProvider
import io.github.b150005.knitnote.db.KnitNoteDatabase
import io.github.b150005.knitnote.db.createTestDriver
import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for RealtimeSyncManager.
 *
 * Part 1: Tests the data layer contracts that RealtimeSyncManager depends on.
 * Part 2 (Phase 13): Tests the subscription lifecycle via FakeRealtimeChannelProvider.
 */
class RealtimeSyncManagerTest {
    private lateinit var localProject: LocalProjectDataSource
    private lateinit var localProgress: LocalProgressDataSource

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        val db = KnitNoteDatabase(driver)
        val testDispatcher = Dispatchers.Unconfined
        localProject = LocalProjectDataSource(db, testDispatcher)
        localProgress = LocalProgressDataSource(db, testDispatcher)
    }

    private fun testProject(id: String = "p1") =
        Project(
            id = id,
            ownerId = "owner-1",
            patternId = "pattern-1",
            title = "Test Project",
            status = ProjectStatus.IN_PROGRESS,
            currentRow = 10,
            totalRows = 100,
            startedAt = null,
            completedAt = null,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private fun testProgress(
        id: String = "prog-1",
        projectId: String = "p1",
    ) = Progress(
        id = id,
        projectId = projectId,
        rowNumber = 5,
        photoUrl = null,
        note = "Row 5 done",
        createdAt = Instant.parse("2026-01-01T12:00:00Z"),
    )

    // ===== Part 1: Data layer contract tests =====

    @Test
    fun `project INSERT event upserts into local db`() =
        runTest {
            val project = testProject()
            localProject.upsert(project)

            val result = localProject.getById("p1")
            assertNotNull(result)
            assertEquals("Test Project", result.title)
        }

    @Test
    fun `project UPDATE event upserts updated fields`() =
        runTest {
            localProject.upsert(testProject())

            val updated = testProject().copy(title = "Updated via Realtime", currentRow = 42)
            localProject.upsert(updated)

            val result = localProject.getById("p1")
            assertNotNull(result)
            assertEquals("Updated via Realtime", result.title)
            assertEquals(42, result.currentRow)
        }

    @Test
    fun `project DELETE event removes from local db`() =
        runTest {
            localProject.upsert(testProject())
            localProject.delete("p1")

            assertNull(localProject.getById("p1"))
        }

    @Test
    fun `duplicate upsert is idempotent`() =
        runTest {
            val project = testProject()
            localProject.upsert(project)
            localProject.upsert(project)

            val results = localProject.getByOwnerId("owner-1")
            assertEquals(1, results.size)
        }

    @Test
    fun `progress INSERT for known project upserts locally`() =
        runTest {
            localProject.upsert(testProject())
            val progress = testProgress()

            val isKnown = localProject.getById(progress.projectId) != null
            assertEquals(true, isKnown)

            localProgress.upsert(progress)

            val result = localProgress.getById("prog-1")
            assertNotNull(result)
            assertEquals("Row 5 done", result.note)
        }

    @Test
    fun `progress INSERT for unknown project is skipped`() =
        runTest {
            val progress = testProgress(projectId = "unknown-project")

            val isKnown = localProject.getById(progress.projectId) != null
            assertEquals(false, isKnown)
        }

    @Test
    fun `progress DELETE removes from local db`() =
        runTest {
            localProject.upsert(testProject())
            localProgress.upsert(testProgress())

            localProgress.delete("prog-1")

            assertNull(localProgress.getById("prog-1"))
        }

    @Test
    fun `project upsert does not cascade-delete child progress entries`() =
        runTest {
            localProject.upsert(testProject())
            localProgress.upsert(testProgress(id = "prog-1"))
            localProgress.upsert(testProgress(id = "prog-2", projectId = "p1"))

            val updated = testProject().copy(title = "Updated Title", currentRow = 50)
            localProject.upsert(updated)

            val progressList = localProgress.getByProjectId("p1")
            assertEquals(2, progressList.size)
        }

    @Test
    fun `project UPDATE with lower currentRow does not overwrite local`() =
        runTest {
            localProject.upsert(testProject().copy(currentRow = 10))

            val remote = testProject().copy(currentRow = 5)
            val local = localProject.getById(remote.id)
            if (local == null || local.currentRow <= remote.currentRow) {
                localProject.upsert(remote)
            }

            val result = localProject.getById("p1")
            assertNotNull(result)
            assertEquals(10, result.currentRow)
        }

    @Test
    fun `project UPDATE with higher currentRow overwrites local`() =
        runTest {
            localProject.upsert(testProject().copy(currentRow = 5))

            val remote = testProject().copy(currentRow = 15)
            val local = localProject.getById(remote.id)
            if (local == null || local.currentRow <= remote.currentRow) {
                localProject.upsert(remote)
            }

            val result = localProject.getById("p1")
            assertNotNull(result)
            assertEquals(15, result.currentRow)
        }

    @Test
    fun `progress upsert replaces existing entry`() =
        runTest {
            localProject.upsert(testProject())
            localProgress.upsert(testProgress())

            val updated = testProgress().copy(note = "Updated note", rowNumber = 10)
            localProgress.upsert(updated)

            val result = localProgress.getById("prog-1")
            assertNotNull(result)
            assertEquals("Updated note", result.note)
            assertEquals(10, result.rowNumber)
        }

    // ===== Part 2: Subscription lifecycle tests via FakeRealtimeChannelProvider =====

    private data class ManagerSetup(
        val manager: RealtimeSyncManager,
        val channelProvider: FakeRealtimeChannelProvider,
        val logger: CapturingSyncLogger,
    )

    private fun createManager(
        isOnline: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
        config: RealtimeConfig = RealtimeConfig(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): ManagerSetup {
        val driver = createTestDriver()
        val db = KnitNoteDatabase(driver)
        val testDispatcher = Dispatchers.Unconfined
        val channelProvider = FakeRealtimeChannelProvider()
        val fakeAuth = FakeAuthRepository()
        val logger = CapturingSyncLogger()

        val manager =
            RealtimeSyncManager(
                channelProvider = channelProvider,
                localProject = LocalProjectDataSource(db, testDispatcher),
                localProgress = LocalProgressDataSource(db, testDispatcher),
                localPattern = LocalPatternDataSource(db, testDispatcher),
                authRepository = fakeAuth,
                scope = scope,
                logger = logger,
                isOnline = isOnline,
                config = config,
                random = kotlin.random.Random(seed = 42),
            )
        return ManagerSetup(manager, channelProvider, logger)
    }

    private fun createManager(): Pair<RealtimeSyncManager, FakeRealtimeChannelProvider> {
        val setup = createManager(isOnline = null)
        return setup.manager to setup.channelProvider
    }

    @Test
    fun `subscribe creates 3 channels with correct names`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")

            assertNotNull(channelProvider.channelFor("projects-owner-1"))
            assertNotNull(channelProvider.channelFor("progress-owner-1"))
            assertNotNull(channelProvider.channelFor("patterns-owner-1"))
            assertEquals(3, channelProvider.createdChannels.size)
        }

    @Test
    fun `subscribe sets all channels to subscribed`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")

            channelProvider.createdChannels.values.forEach { handle ->
                assertTrue(handle.subscribed)
            }
        }

    @Test
    fun `unsubscribe calls unsubscribe on all channels`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")
            manager.unsubscribe()

            channelProvider.createdChannels.values.forEach { handle ->
                assertTrue(handle.unsubscribed)
            }
        }

    @Test
    fun `subscribe configures correct table and filters`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")

            val projectHandle = channelProvider.channelFor("projects-owner-1")!!
            assertEquals("projects", projectHandle.subscribedTable)
            assertEquals("owner_id", projectHandle.subscribedFilter?.column)
            assertEquals("owner-1", projectHandle.subscribedFilter?.value)

            val progressHandle = channelProvider.channelFor("progress-owner-1")!!
            assertEquals("progress", progressHandle.subscribedTable)
            assertEquals("owner_id", progressHandle.subscribedFilter?.column)
            assertEquals("owner-1", progressHandle.subscribedFilter?.value)

            val patternHandle = channelProvider.channelFor("patterns-owner-1")!!
            assertEquals("patterns", patternHandle.subscribedTable)
            assertEquals("owner_id", patternHandle.subscribedFilter?.column)
            assertEquals("owner-1", patternHandle.subscribedFilter?.value)
        }

    @Test
    fun `re-subscribe unsubscribes old channels before creating new ones`() =
        runTest {
            val (manager, channelProvider) = createManager()

            manager.subscribe("owner-1")
            val firstHandles = channelProvider.createdChannels.values.toList()

            manager.subscribe("owner-2")

            // Old channels should be unsubscribed
            firstHandles.forEach { handle ->
                assertTrue(handle.unsubscribed)
            }
            // New channels should be created
            assertNotNull(channelProvider.channelFor("projects-owner-2"))
            assertNotNull(channelProvider.channelFor("progress-owner-2"))
            assertNotNull(channelProvider.channelFor("patterns-owner-2"))
        }

    // ===== Part 3: Reconnect and error logging tests =====

    @Test
    fun `channel flow error logs the error`() =
        runTest {
            val setup =
                createManager(
                    isOnline = null,
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )

            setup.manager.subscribe("owner-1")
            testScheduler.advanceUntilIdle()

            setup.channelProvider.channelFor("projects-owner-1")!!.completeWithError(RuntimeException("WebSocket closed"))
            testScheduler.advanceUntilIdle()

            assertTrue(setup.logger.entries.isNotEmpty())
            val entry = setup.logger.entries.first()
            assertEquals("RealtimeSyncManager", entry.tag)
            assertTrue(entry.message.contains("projects"))
            assertNotNull(entry.throwable)
        }

    @Test
    fun `channel flow error triggers resubscription`() =
        runTest {
            val setup =
                createManager(
                    isOnline = null,
                    config = RealtimeConfig(baseDelayMs = 10),
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )

            setup.manager.subscribe("owner-1")
            testScheduler.advanceUntilIdle()
            assertEquals(3, setup.channelProvider.createCount)

            setup.channelProvider.channelFor("projects-owner-1")!!.completeWithError(RuntimeException("connection lost"))
            testScheduler.advanceUntilIdle()

            // After error + retry, 3 new channels should be created (total 6)
            assertTrue(setup.channelProvider.createCount > 3)
        }

    @Test
    fun `max retry exhaustion stops auto-retry`() =
        runTest {
            val setup =
                createManager(
                    isOnline = null,
                    config = RealtimeConfig(maxRetries = 1, baseDelayMs = 10),
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )

            setup.manager.subscribe("owner-1")
            testScheduler.advanceUntilIdle()

            // Trigger 1st error → should retry (retryCount becomes 1)
            setup.channelProvider.channelFor("projects-owner-1")!!.completeWithError(RuntimeException("error 1"))
            testScheduler.advanceUntilIdle()

            val countAfterFirstRetry = setup.channelProvider.createCount

            // Trigger 2nd error on the new channel → should NOT retry (retryCount >= maxRetries)
            setup.channelProvider.channelFor("projects-owner-1")!!.completeWithError(RuntimeException("error 2"))
            testScheduler.advanceUntilIdle()

            // No additional channels created — retry exhausted
            assertEquals(countAfterFirstRetry, setup.channelProvider.createCount)

            // Verify the log shows max retries reached
            val maxRetryLog = setup.logger.entries.any { it.message.contains("Max retries") }
            assertTrue(maxRetryLog)
        }

    @Test
    fun `retry count increments on error and persists across retries`() =
        runTest {
            val setup =
                createManager(
                    isOnline = null,
                    config = RealtimeConfig(baseDelayMs = 10),
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )

            setup.manager.subscribe("owner-1")
            testScheduler.advanceUntilIdle()
            assertEquals(0, setup.manager.retryCount)

            // Trigger error — retryCount should increment
            setup.channelProvider.channelFor("projects-owner-1")!!.completeWithError(RuntimeException("err"))
            testScheduler.advanceUntilIdle()

            assertTrue(setup.manager.retryCount > 0)
        }

    @Test
    fun `connectivity online transition triggers resubscription`() =
        runTest {
            val onlineState = kotlinx.coroutines.flow.MutableStateFlow(false)
            val setup =
                createManager(
                    isOnline = onlineState,
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )

            setup.manager.subscribe("owner-1")
            testScheduler.advanceUntilIdle()
            val countAfterSubscribe = setup.channelProvider.createCount

            setup.manager.start()
            testScheduler.advanceUntilIdle()

            onlineState.value = true
            testScheduler.advanceUntilIdle()

            // Should have re-subscribed (3 new channels)
            assertTrue(setup.channelProvider.createCount > countAfterSubscribe)
            assertEquals("owner-1", setup.manager.lastOwnerId)
        }

    @Test
    fun `connectivity online with no prior subscription is no-op`() =
        runTest {
            val onlineState = kotlinx.coroutines.flow.MutableStateFlow(false)
            val setup =
                createManager(
                    isOnline = onlineState,
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )

            setup.manager.start()
            testScheduler.advanceUntilIdle()

            onlineState.value = true
            testScheduler.advanceUntilIdle()

            // No channels should be created (no lastOwnerId)
            assertTrue(setup.channelProvider.createdChannels.isEmpty())
        }

    @Test
    fun `connectivity recovery resets retry count`() =
        runTest {
            val onlineState = kotlinx.coroutines.flow.MutableStateFlow(true)
            val setup =
                createManager(
                    isOnline = onlineState,
                    config = RealtimeConfig(maxRetries = 1, baseDelayMs = 10),
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )

            setup.manager.subscribe("owner-1")
            setup.manager.start()
            testScheduler.advanceUntilIdle()

            setup.channelProvider.channelFor("projects-owner-1")!!.completeWithError(RuntimeException("err"))
            testScheduler.advanceUntilIdle()

            onlineState.value = false
            testScheduler.advanceUntilIdle()
            onlineState.value = true
            testScheduler.advanceUntilIdle()

            assertEquals(0, setup.manager.retryCount)
        }
}
