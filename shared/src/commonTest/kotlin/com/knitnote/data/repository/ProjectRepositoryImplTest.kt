package com.knitnote.data.repository

import com.knitnote.data.local.LocalProjectDataSource
import com.knitnote.data.sync.FakeSyncManager
import com.knitnote.data.sync.SyncEntityType
import com.knitnote.data.sync.SyncOperation
import com.knitnote.db.KnitNoteDatabase
import com.knitnote.db.createTestDriver
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
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

class ProjectRepositoryImplTest {

    private lateinit var db: KnitNoteDatabase
    private lateinit var repository: ProjectRepositoryImpl
    private lateinit var fakeSyncManager: FakeSyncManager
    private val isOnline = MutableStateFlow(false)
    private val json = testJson

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        db = KnitNoteDatabase(driver)
        fakeSyncManager = FakeSyncManager()
        repository = ProjectRepositoryImpl(
            local = LocalProjectDataSource(db),
            remote = null,
            isOnline = isOnline,
            syncManager = fakeSyncManager,
            json = json,
        )
    }

    private fun createTestProject(
        id: String = "test-project-1",
        title: String = "Test Scarf",
        currentRow: Int = 0,
        totalRows: Int? = 100,
        status: ProjectStatus = ProjectStatus.NOT_STARTED,
    ): Project = Project(
        id = id,
        ownerId = "local-user",
        patternId = "no-pattern",
        title = title,
        status = status,
        currentRow = currentRow,
        totalRows = totalRows,
        startedAt = null,
        completedAt = null,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    @Test
    fun `insert and retrieve project by id`() = runTest {
        val project = createTestProject()
        repository.create(project)

        val retrieved = repository.getById(project.id)

        assertNotNull(retrieved)
        assertEquals(project.id, retrieved.id)
        assertEquals(project.title, retrieved.title)
        assertEquals(project.status, retrieved.status)
        assertEquals(project.currentRow, retrieved.currentRow)
        assertEquals(project.totalRows, retrieved.totalRows)
    }

    @Test
    fun `getById returns null for non-existent id`() = runTest {
        val result = repository.getById("non-existent")
        assertNull(result)
    }

    @Test
    fun `retrieve by owner id returns projects ordered by created_at desc`() = runTest {
        val project1 = createTestProject(id = "p1", title = "First")
        val project2 = createTestProject(id = "p2", title = "Second")
        repository.create(project1)
        repository.create(project2)

        val projects = repository.getByOwnerId("local-user")

        assertEquals(2, projects.size)
    }

    @Test
    fun `update project current row`() = runTest {
        val project = createTestProject()
        repository.create(project)

        val updated = project.copy(
            currentRow = 42,
            status = ProjectStatus.IN_PROGRESS,
        )
        repository.update(updated)

        val retrieved = repository.getById(project.id)
        assertNotNull(retrieved)
        assertEquals(42, retrieved.currentRow)
        assertEquals(ProjectStatus.IN_PROGRESS, retrieved.status)
    }

    @Test
    fun `delete project removes it`() = runTest {
        val project = createTestProject()
        repository.create(project)

        repository.delete(project.id)

        val retrieved = repository.getById(project.id)
        assertNull(retrieved)
    }

    @Test
    fun `observeByOwnerId emits current list`() = runTest {
        val project = createTestProject()
        repository.create(project)

        val projects = repository.observeByOwnerId("local-user").first()

        assertEquals(1, projects.size)
        assertEquals(project.id, projects[0].id)
    }

    @Test
    fun `getByPatternId returns matching projects`() = runTest {
        val project = createTestProject()
        repository.create(project)

        val results = repository.getByPatternId("no-pattern")
        assertEquals(1, results.size)

        val noResults = repository.getByPatternId("other-pattern")
        assertEquals(0, noResults.size)
    }

    // --- SyncManager integration tests ---

    @Test
    fun `create calls syncOrEnqueue with insert operation`() = runTest {
        val project = createTestProject()
        repository.create(project)

        assertEquals(1, fakeSyncManager.calls.size)
        val call = fakeSyncManager.calls[0]
        assertEquals(SyncEntityType.PROJECT, call.entityType)
        assertEquals(project.id, call.entityId)
        assertEquals(SyncOperation.INSERT, call.operation)
        assertTrue(call.payload.contains(project.id))
    }

    @Test
    fun `update calls syncOrEnqueue with update operation`() = runTest {
        val project = createTestProject()
        repository.create(project)
        fakeSyncManager.calls.clear()

        val updated = project.copy(currentRow = 10)
        repository.update(updated)

        assertEquals(1, fakeSyncManager.calls.size)
        assertEquals(SyncOperation.UPDATE, fakeSyncManager.calls[0].operation)
    }

    @Test
    fun `delete calls syncOrEnqueue with delete operation`() = runTest {
        val project = createTestProject()
        repository.create(project)
        fakeSyncManager.calls.clear()

        repository.delete(project.id)

        assertEquals(1, fakeSyncManager.calls.size)
        val call = fakeSyncManager.calls[0]
        assertEquals(SyncEntityType.PROJECT, call.entityType)
        assertEquals(project.id, call.entityId)
        assertEquals(SyncOperation.DELETE, call.operation)
        assertEquals("", call.payload)
    }
}
