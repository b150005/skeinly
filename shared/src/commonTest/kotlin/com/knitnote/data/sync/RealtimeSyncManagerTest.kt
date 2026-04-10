package com.knitnote.data.sync

import com.knitnote.data.local.LocalProgressDataSource
import com.knitnote.data.local.LocalProjectDataSource
import com.knitnote.db.KnitNoteDatabase
import com.knitnote.db.createTestDriver
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.model.Progress
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for the local data operations that RealtimeSyncManager uses.
 *
 * RealtimeSyncManager itself is glue code wiring Supabase Realtime events
 * to local data source upsert/delete calls. The Realtime subscription
 * lifecycle requires a live SupabaseClient and is tested manually.
 *
 * These tests verify the data layer contracts that RealtimeSyncManager depends on:
 * - upsert inserts new records and replaces existing ones (idempotent)
 * - delete removes records
 * - isKnownProject check works for progress filtering
 */
class RealtimeSyncManagerTest {

    private lateinit var localProject: LocalProjectDataSource
    private lateinit var localProgress: LocalProgressDataSource

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        val db = KnitNoteDatabase(driver)
        localProject = LocalProjectDataSource(db)
        localProgress = LocalProgressDataSource(db)
    }

    private fun testProject(id: String = "p1") = Project(
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

    private fun testProgress(id: String = "prog-1", projectId: String = "p1") = Progress(
        id = id,
        projectId = projectId,
        rowNumber = 5,
        photoUrl = null,
        note = "Row 5 done",
        createdAt = Instant.parse("2026-01-01T12:00:00Z"),
    )

    // -- Simulating INSERT event: upsert a new project --

    @Test
    fun `project INSERT event upserts into local db`() = runTest {
        val project = testProject()
        localProject.upsert(project)

        val result = localProject.getById("p1")
        assertNotNull(result)
        assertEquals("Test Project", result.title)
    }

    // -- Simulating UPDATE event: upsert overwrites existing project --

    @Test
    fun `project UPDATE event upserts updated fields`() = runTest {
        localProject.upsert(testProject())

        val updated = testProject().copy(title = "Updated via Realtime", currentRow = 42)
        localProject.upsert(updated)

        val result = localProject.getById("p1")
        assertNotNull(result)
        assertEquals("Updated via Realtime", result.title)
        assertEquals(42, result.currentRow)
    }

    // -- Simulating DELETE event --

    @Test
    fun `project DELETE event removes from local db`() = runTest {
        localProject.upsert(testProject())
        localProject.delete("p1")

        assertNull(localProject.getById("p1"))
    }

    // -- Simulating own-write loop: upsert is idempotent --

    @Test
    fun `duplicate upsert is idempotent`() = runTest {
        val project = testProject()
        localProject.upsert(project)
        localProject.upsert(project)

        val results = localProject.getByOwnerId("owner-1")
        assertEquals(1, results.size)
    }

    // -- Progress events with known project check --

    @Test
    fun `progress INSERT for known project upserts locally`() = runTest {
        localProject.upsert(testProject())
        val progress = testProgress()

        // Simulate: check isKnownProject, then upsert
        val isKnown = localProject.getById(progress.projectId) != null
        assertEquals(true, isKnown)

        localProgress.upsert(progress)

        val result = localProgress.getById("prog-1")
        assertNotNull(result)
        assertEquals("Row 5 done", result.note)
    }

    @Test
    fun `progress INSERT for unknown project is skipped`() = runTest {
        // No project in local DB
        val progress = testProgress(projectId = "unknown-project")

        val isKnown = localProject.getById(progress.projectId) != null
        assertEquals(false, isKnown)

        // In RealtimeSyncManager, this would skip the upsert
    }

    @Test
    fun `progress DELETE removes from local db`() = runTest {
        localProject.upsert(testProject())
        localProgress.upsert(testProgress())

        localProgress.delete("prog-1")

        assertNull(localProgress.getById("prog-1"))
    }

    // -- Cascade safety: upserting a project must NOT delete its progress --

    @Test
    fun `project upsert does not cascade-delete child progress entries`() = runTest {
        localProject.upsert(testProject())
        localProgress.upsert(testProgress(id = "prog-1"))
        localProgress.upsert(testProgress(id = "prog-2", projectId = "p1"))

        // Upsert the same project with updated fields
        val updated = testProject().copy(title = "Updated Title", currentRow = 50)
        localProject.upsert(updated)

        // Progress entries must still exist
        val progressList = localProgress.getByProjectId("p1")
        assertEquals(2, progressList.size)
    }

    @Test
    fun `progress upsert replaces existing entry`() = runTest {
        localProject.upsert(testProject())
        localProgress.upsert(testProgress())

        val updated = testProgress().copy(note = "Updated note", rowNumber = 10)
        localProgress.upsert(updated)

        val result = localProgress.getById("prog-1")
        assertNotNull(result)
        assertEquals("Updated note", result.note)
        assertEquals(10, result.rowNumber)
    }
}
