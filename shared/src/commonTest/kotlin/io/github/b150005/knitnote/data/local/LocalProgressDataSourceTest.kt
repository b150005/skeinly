package io.github.b150005.knitnote.data.local

import io.github.b150005.knitnote.db.KnitNoteDatabase
import io.github.b150005.knitnote.db.createTestDriver
import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class LocalProgressDataSourceTest {
    private lateinit var progressDataSource: LocalProgressDataSource
    private lateinit var projectDataSource: LocalProjectDataSource

    private val testDispatcher = Dispatchers.Unconfined

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        val db = KnitNoteDatabase(driver)
        progressDataSource = LocalProgressDataSource(db, testDispatcher)
        projectDataSource = LocalProjectDataSource(db, testDispatcher)
    }

    private suspend fun insertParentProject(id: String = "proj-1") {
        projectDataSource.insert(
            Project(
                id = id,
                ownerId = "owner-1",
                patternId = "pattern-1",
                title = "Test",
                status = ProjectStatus.IN_PROGRESS,
                currentRow = 5,
                totalRows = 100,
                startedAt = null,
                completedAt = null,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
    }

    private fun testProgress(
        id: String = "prog-1",
        projectId: String = "proj-1",
        rowNumber: Int = 5,
        note: String = "Test note",
    ) = Progress(
        id = id,
        projectId = projectId,
        rowNumber = rowNumber,
        photoUrl = null,
        note = note,
        createdAt = Instant.parse("2026-01-01T12:00:00Z"),
    )

    @Test
    fun `upsert inserts new progress`() =
        runTest {
            insertParentProject()
            val progress = testProgress()
            progressDataSource.upsert(progress)

            val result = progressDataSource.getById("prog-1")
            assertNotNull(result)
            assertEquals("Test note", result.note)
            assertEquals(5, result.rowNumber)
        }

    @Test
    fun `upsert replaces existing progress`() =
        runTest {
            insertParentProject()
            progressDataSource.insert(testProgress())

            val updated = testProgress(note = "Updated note", rowNumber = 10)
            progressDataSource.upsert(updated)

            val result = progressDataSource.getById("prog-1")
            assertNotNull(result)
            assertEquals("Updated note", result.note)
            assertEquals(10, result.rowNumber)
        }

    @Test
    fun `delete removes progress`() =
        runTest {
            insertParentProject()
            progressDataSource.insert(testProgress())
            progressDataSource.delete("prog-1")

            assertNull(progressDataSource.getById("prog-1"))
        }

    @Test
    fun `getByProjectId returns entries ordered by created_at desc`() =
        runTest {
            insertParentProject()
            progressDataSource.insert(
                testProgress(id = "prog-1", note = "First").copy(
                    createdAt = Instant.parse("2026-01-01T10:00:00Z"),
                ),
            )
            progressDataSource.insert(
                testProgress(id = "prog-2", note = "Second").copy(
                    createdAt = Instant.parse("2026-01-01T12:00:00Z"),
                ),
            )

            val results = progressDataSource.getByProjectId("proj-1")
            assertEquals(2, results.size)
            assertEquals("Second", results[0].note)
            assertEquals("First", results[1].note)
        }
}
