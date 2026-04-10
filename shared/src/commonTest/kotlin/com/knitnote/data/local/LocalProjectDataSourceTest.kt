package com.knitnote.data.local

import com.knitnote.db.KnitNoteDatabase
import com.knitnote.db.createTestDriver
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalProjectDataSourceTest {

    private lateinit var dataSource: LocalProjectDataSource

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        val db = KnitNoteDatabase(driver)
        dataSource = LocalProjectDataSource(db)
    }

    private fun testProject(
        id: String = "p1",
        title: String = "Test Project",
        status: ProjectStatus = ProjectStatus.NOT_STARTED,
        currentRow: Int = 0,
    ) = Project(
        id = id,
        ownerId = "owner-1",
        patternId = "pattern-1",
        title = title,
        status = status,
        currentRow = currentRow,
        totalRows = 100,
        startedAt = null,
        completedAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `upsert inserts new project`() = runTest {
        val project = testProject()
        dataSource.upsert(project)

        val result = dataSource.getById("p1")
        assertNotNull(result)
        assertEquals("Test Project", result.title)
    }

    @Test
    fun `upsert replaces existing project`() = runTest {
        val original = testProject()
        dataSource.insert(original)

        val updated = original.copy(
            title = "Updated Title",
            currentRow = 42,
            status = ProjectStatus.IN_PROGRESS,
        )
        dataSource.upsert(updated)

        val result = dataSource.getById("p1")
        assertNotNull(result)
        assertEquals("Updated Title", result.title)
        assertEquals(42, result.currentRow)
        assertEquals(ProjectStatus.IN_PROGRESS, result.status)
    }

    @Test
    fun `upsertAll inserts multiple projects`() = runTest {
        val projects = listOf(
            testProject(id = "p1", title = "First"),
            testProject(id = "p2", title = "Second"),
        )
        dataSource.upsertAll(projects)

        assertEquals(2, dataSource.getByOwnerId("owner-1").size)
    }

    @Test
    fun `upsertAll replaces existing projects`() = runTest {
        dataSource.insert(testProject(id = "p1", title = "Original"))

        dataSource.upsertAll(
            listOf(testProject(id = "p1", title = "Replaced")),
        )

        val result = dataSource.getById("p1")
        assertNotNull(result)
        assertEquals("Replaced", result.title)
    }

    @Test
    fun `delete removes project`() = runTest {
        dataSource.insert(testProject())
        dataSource.delete("p1")

        assertNull(dataSource.getById("p1"))
    }
}
