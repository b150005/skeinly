package com.knitnote.data.repository

import com.knitnote.data.local.LocalProgressDataSource
import com.knitnote.data.local.LocalProjectDataSource
import com.knitnote.data.sync.FakeSyncManager
import com.knitnote.data.sync.SyncEntityType
import com.knitnote.data.sync.SyncOperation
import com.knitnote.db.KnitNoteDatabase
import com.knitnote.db.createTestDriver
import com.knitnote.domain.model.Progress
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.knitnote.testJson
import kotlin.time.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressRepositoryImplTest {

    private lateinit var db: KnitNoteDatabase
    private lateinit var progressRepository: ProgressRepositoryImpl
    private lateinit var localProjectDataSource: LocalProjectDataSource
    private lateinit var fakeSyncManager: FakeSyncManager
    private val isOnline = MutableStateFlow(false)
    private val json = testJson

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        db = KnitNoteDatabase(driver)
        localProjectDataSource = LocalProjectDataSource(db)
        fakeSyncManager = FakeSyncManager()
        progressRepository = ProgressRepositoryImpl(
            local = LocalProgressDataSource(db),
            remote = null,
            isOnline = isOnline,
            syncManager = fakeSyncManager,
            json = json,
        )
    }

    private suspend fun createParentProject(): Project {
        val project = Project(
            id = "parent-project",
            ownerId = "local-user",
            patternId = "no-pattern",
            title = "Test Project",
            status = ProjectStatus.IN_PROGRESS,
            currentRow = 5,
            totalRows = 100,
            startedAt = Clock.System.now(),
            completedAt = null,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        localProjectDataSource.insert(project)
        return project
    }

    private fun createTestProgress(
        id: String = "progress-1",
        projectId: String = "parent-project",
        rowNumber: Int = 1,
    ): Progress = Progress(
        id = id,
        projectId = projectId,
        rowNumber = rowNumber,
        photoUrl = null,
        note = "",
        createdAt = Clock.System.now(),
    )

    @Test
    fun `insert and retrieve progress by id`() = runTest {
        createParentProject()
        val progress = createTestProgress()
        progressRepository.create(progress)

        val retrieved = progressRepository.getById(progress.id)

        assertNotNull(retrieved)
        assertEquals(progress.id, retrieved.id)
        assertEquals(progress.projectId, retrieved.projectId)
        assertEquals(progress.rowNumber, retrieved.rowNumber)
    }

    @Test
    fun `getById returns null for non-existent id`() = runTest {
        val result = progressRepository.getById("non-existent")
        assertNull(result)
    }

    @Test
    fun `retrieve by project id returns progress entries`() = runTest {
        createParentProject()
        progressRepository.create(createTestProgress(id = "p1", rowNumber = 1))
        progressRepository.create(createTestProgress(id = "p2", rowNumber = 2))

        val entries = progressRepository.getByProjectId("parent-project")

        assertEquals(2, entries.size)
    }

    @Test
    fun `delete progress removes it`() = runTest {
        createParentProject()
        val progress = createTestProgress()
        progressRepository.create(progress)

        progressRepository.delete(progress.id)

        val retrieved = progressRepository.getById(progress.id)
        assertNull(retrieved)
    }

    @Test
    fun `observeByProjectId emits current list`() = runTest {
        createParentProject()
        val progress = createTestProgress()
        progressRepository.create(progress)

        val entries = progressRepository.observeByProjectId("parent-project").first()

        assertEquals(1, entries.size)
        assertEquals(progress.id, entries[0].id)
    }

    // --- SyncManager integration tests ---

    @Test
    fun `create calls syncOrEnqueue with insert operation`() = runTest {
        createParentProject()
        val progress = createTestProgress()
        progressRepository.create(progress)

        assertEquals(1, fakeSyncManager.calls.size)
        val call = fakeSyncManager.calls[0]
        assertEquals(SyncEntityType.PROGRESS, call.entityType)
        assertEquals(progress.id, call.entityId)
        assertEquals(SyncOperation.INSERT, call.operation)
        assertTrue(call.payload.contains(progress.id))
    }

    @Test
    fun `delete calls syncOrEnqueue with delete operation`() = runTest {
        createParentProject()
        val progress = createTestProgress()
        progressRepository.create(progress)
        fakeSyncManager.calls.clear()

        progressRepository.delete(progress.id)

        assertEquals(1, fakeSyncManager.calls.size)
        val call = fakeSyncManager.calls[0]
        assertEquals(SyncEntityType.PROGRESS, call.entityType)
        assertEquals(progress.id, call.entityId)
        assertEquals(SyncOperation.DELETE, call.operation)
        assertEquals("", call.payload)
    }
}
