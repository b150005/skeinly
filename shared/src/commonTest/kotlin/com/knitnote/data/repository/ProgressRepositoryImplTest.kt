package com.knitnote.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.knitnote.db.KnitNoteDatabase
import com.knitnote.domain.model.Progress
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.model.Project
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProgressRepositoryImplTest {

    private lateinit var db: KnitNoteDatabase
    private lateinit var progressRepository: ProgressRepositoryImpl
    private lateinit var projectRepository: ProjectRepositoryImpl

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KnitNoteDatabase.Schema.create(driver)
        db = KnitNoteDatabase(driver)
        progressRepository = ProgressRepositoryImpl(db)
        projectRepository = ProjectRepositoryImpl(db)
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
        projectRepository.create(project)
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
}
