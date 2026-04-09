package com.knitnote.data.sync

import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import com.knitnote.testJson
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncManagerTest {

    private val json = testJson
    private lateinit var fakePendingSync: FakeLocalPendingSyncDataSource
    private lateinit var fakeRemoteProject: FakeRemoteProjectDataSource
    private lateinit var fakeRemoteProgress: FakeRemoteProgressDataSource
    private lateinit var syncExecutor: SyncExecutor
    private val isOnline = MutableStateFlow(false)

    private val testProject = Project(
        id = "p-1",
        ownerId = "user-1",
        patternId = "no-pattern",
        title = "Test",
        status = ProjectStatus.NOT_STARTED,
        currentRow = 0,
        totalRows = 100,
        startedAt = null,
        completedAt = null,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    @BeforeTest
    fun setUp() {
        fakePendingSync = FakeLocalPendingSyncDataSource()
        fakeRemoteProject = FakeRemoteProjectDataSource()
        fakeRemoteProgress = FakeRemoteProgressDataSource()
        syncExecutor = SyncExecutor(fakeRemoteProject, fakeRemoteProgress, null, json)
        isOnline.value = false
    }

    private fun TestScope.createSyncManager(
        config: SyncConfig = SyncConfig(maxRetries = 3, baseDelayMs = 100, maxDelayMs = 1000),
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
    ): SyncManager = SyncManager(
        pendingSyncDataSource = fakePendingSync,
        syncExecutor = syncExecutor,
        isOnline = isOnline,
        scope = scope,
        config = config,
    )

    // --- syncOrEnqueue tests ---

    @Test
    fun `syncOrEnqueue when online syncs immediately without enqueue`() = runTest {
        isOnline.value = true
        val manager = createSyncManager()

        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, json.encodeToString(testProject))

        assertEquals(1, fakeRemoteProject.insertedProjects.size)
        assertEquals(0, fakePendingSync.countPending())
    }

    @Test
    fun `syncOrEnqueue when offline enqueues without remote call`() = runTest {
        isOnline.value = false
        val manager = createSyncManager()

        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, json.encodeToString(testProject))

        assertTrue(fakeRemoteProject.insertedProjects.isEmpty())
        assertEquals(1, fakePendingSync.countPending())
    }

    @Test
    fun `syncOrEnqueue when online but remote fails enqueues`() = runTest {
        isOnline.value = true
        fakeRemoteProject.shouldFail = true
        val manager = createSyncManager()

        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, json.encodeToString(testProject))

        assertEquals(1, fakePendingSync.countPending())
    }

    // --- processQueue tests ---

    @Test
    fun `processQueue syncs pending entries and deletes on success`() = runTest {
        val manager = createSyncManager()

        // Enqueue offline
        fakePendingSync.enqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, json.encodeToString(testProject), 1000L)
        assertEquals(1, fakePendingSync.countPending())

        // Process
        manager.processQueue()

        assertEquals(1, fakeRemoteProject.insertedProjects.size)
        assertEquals(0, fakePendingSync.countPending())
    }

    @Test
    fun `processQueue increments retry on failure`() = runTest {
        val manager = createSyncManager()
        fakeRemoteProject.shouldFail = true

        fakePendingSync.enqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, json.encodeToString(testProject), 1000L)

        manager.processQueue()

        val entries = fakePendingSync.allEntries()
        assertEquals(1, entries.size)
        assertEquals(1, entries[0].retryCount)
        assertEquals(SyncStatus.PENDING, entries[0].status)
    }

    @Test
    fun `processQueue marks entry failed at max retries`() = runTest {
        val manager = createSyncManager(SyncConfig(maxRetries = 2, baseDelayMs = 1, maxDelayMs = 1))

        fakePendingSync.enqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, json.encodeToString(testProject), 1000L)
        // Simulate prior retries
        val entry = fakePendingSync.getAllPending()[0]
        fakePendingSync.incrementRetry(entry.id)
        fakePendingSync.incrementRetry(entry.id)

        manager.processQueue()

        val entries = fakePendingSync.allEntries()
        assertEquals(1, entries.size)
        assertEquals(SyncStatus.FAILED, entries[0].status)
        assertEquals(0, fakePendingSync.countPending())
    }

    @Test
    fun `processQueue processes entries in FIFO order`() = runTest {
        val manager = createSyncManager()

        val project2 = testProject.copy(id = "p-2", title = "Second")
        fakePendingSync.enqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, json.encodeToString(testProject), 1000L)
        fakePendingSync.enqueue(SyncEntityType.PROJECT, "p-2", SyncOperation.INSERT, json.encodeToString(project2), 2000L)

        manager.processQueue()

        assertEquals(2, fakeRemoteProject.insertedProjects.size)
        assertEquals("p-1", fakeRemoteProject.insertedProjects[0].id)
        assertEquals("p-2", fakeRemoteProject.insertedProjects[1].id)
    }

    // --- start/connectivity tests ---

    @Test
    fun `start triggers processQueue when going online`() = runTest {
        val manager = createSyncManager()

        fakePendingSync.enqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, json.encodeToString(testProject), 1000L)

        manager.start()
        advanceUntilIdle() // ensure collect is active

        // Go online — should trigger processQueue
        isOnline.value = true
        advanceUntilIdle()

        assertEquals(0, fakePendingSync.countPending())
        assertEquals(1, fakeRemoteProject.insertedProjects.size)

        manager.stop()
    }

    @Test
    fun `empty queue does not fail on processQueue`() = runTest {
        val manager = createSyncManager()
        manager.processQueue()
        // No exception
        assertEquals(0, fakePendingSync.countPending())
    }

    // --- Coalescing tests ---

    @Test
    fun `coalescing insert then update keeps insert with latest payload`() = runTest {
        isOnline.value = false
        val manager = createSyncManager()

        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, """{"v":1}""")
        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.UPDATE, """{"v":2}""")

        val entries = fakePendingSync.getAllPending()
        assertEquals(1, entries.size)
        assertEquals(SyncOperation.INSERT, entries[0].operation)
        assertEquals("""{"v":2}""", entries[0].payload)
    }

    @Test
    fun `coalescing insert then delete cancels both`() = runTest {
        isOnline.value = false
        val manager = createSyncManager()

        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, """{"v":1}""")
        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.DELETE, "")

        assertEquals(0, fakePendingSync.countPending())
    }

    @Test
    fun `coalescing update then update keeps latest payload`() = runTest {
        isOnline.value = false
        val manager = createSyncManager()

        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.UPDATE, """{"v":1}""")
        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.UPDATE, """{"v":2}""")

        val entries = fakePendingSync.getAllPending()
        assertEquals(1, entries.size)
        assertEquals(SyncOperation.UPDATE, entries[0].operation)
        assertEquals("""{"v":2}""", entries[0].payload)
    }

    @Test
    fun `coalescing update then delete replaces with delete`() = runTest {
        isOnline.value = false
        val manager = createSyncManager()

        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.UPDATE, """{"v":1}""")
        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.DELETE, "")

        val entries = fakePendingSync.getAllPending()
        assertEquals(1, entries.size)
        assertEquals(SyncOperation.DELETE, entries[0].operation)
    }

    @Test
    fun `coalescing does not affect different entities`() = runTest {
        isOnline.value = false
        val manager = createSyncManager()

        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, """{"id":"p-1"}""")
        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-2", SyncOperation.INSERT, """{"id":"p-2"}""")

        assertEquals(2, fakePendingSync.countPending())
    }

    // --- Other tests ---

    @Test
    fun `delete operation syncs correctly`() = runTest {
        isOnline.value = true
        val manager = createSyncManager()

        manager.syncOrEnqueue(SyncEntityType.PROJECT, "p-1", SyncOperation.DELETE, "")

        assertEquals(1, fakeRemoteProject.deletedIds.size)
        assertEquals("p-1", fakeRemoteProject.deletedIds[0])
        assertEquals(0, fakePendingSync.countPending())
    }
}
