package com.knitnote.data.sync

import com.knitnote.domain.model.Progress
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val testProject = Project(
        id = "p-1",
        ownerId = "user-1",
        patternId = "no-pattern",
        title = "Test Scarf",
        status = ProjectStatus.IN_PROGRESS,
        currentRow = 10,
        totalRows = 100,
        startedAt = null,
        completedAt = null,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    private val testProgress = Progress(
        id = "pr-1",
        projectId = "p-1",
        rowNumber = 10,
        photoUrl = null,
        note = "Row 10 done",
        createdAt = Instant.fromEpochMilliseconds(1000),
    )

    private fun entry(
        entityType: String,
        entityId: String,
        operation: String,
        payload: String = "",
    ) = PendingSyncEntry(
        id = 1,
        entityType = entityType,
        entityId = entityId,
        operation = operation,
        payload = payload,
        createdAt = 1000L,
        retryCount = 0,
        status = "pending",
    )

    // --- Null remote tests ---

    @Test
    fun `null remoteProject returns true`() = runTest {
        val executor = SyncExecutor(remoteProject = null, remoteProgress = null, json = json)
        val result = executor.execute(entry("project", "p-1", "insert", json.encodeToString(testProject)))
        assertTrue(result)
    }

    @Test
    fun `null remoteProgress returns true`() = runTest {
        val executor = SyncExecutor(remoteProject = null, remoteProgress = null, json = json)
        val result = executor.execute(entry("progress", "pr-1", "insert", json.encodeToString(testProgress)))
        assertTrue(result)
    }

    @Test
    fun `unknown entity type returns true`() = runTest {
        val executor = SyncExecutor(remoteProject = null, remoteProgress = null, json = json)
        val result = executor.execute(entry("unknown", "x-1", "insert"))
        assertTrue(result)
    }

    // --- Project dispatch tests (using FakeRemoteProjectDataSource) ---

    @Test
    fun `project insert dispatches to remote`() = runTest {
        val fakeRemote = FakeRemoteProjectDataSource()
        val executor = SyncExecutor(remoteProject = fakeRemote, remoteProgress = null, json = json)

        val result = executor.execute(
            entry("project", "p-1", "insert", json.encodeToString(testProject)),
        )

        assertTrue(result)
        assertEquals(1, fakeRemote.insertedProjects.size)
        assertEquals("p-1", fakeRemote.insertedProjects[0].id)
    }

    @Test
    fun `project update dispatches to remote`() = runTest {
        val fakeRemote = FakeRemoteProjectDataSource()
        val executor = SyncExecutor(remoteProject = fakeRemote, remoteProgress = null, json = json)

        val result = executor.execute(
            entry("project", "p-1", "update", json.encodeToString(testProject)),
        )

        assertTrue(result)
        assertEquals(1, fakeRemote.updatedProjects.size)
    }

    @Test
    fun `project delete dispatches to remote`() = runTest {
        val fakeRemote = FakeRemoteProjectDataSource()
        val executor = SyncExecutor(remoteProject = fakeRemote, remoteProgress = null, json = json)

        val result = executor.execute(entry("project", "p-1", "delete"))

        assertTrue(result)
        assertEquals(1, fakeRemote.deletedIds.size)
        assertEquals("p-1", fakeRemote.deletedIds[0])
    }

    @Test
    fun `project insert failure returns false`() = runTest {
        val fakeRemote = FakeRemoteProjectDataSource()
        fakeRemote.shouldFail = true
        val executor = SyncExecutor(remoteProject = fakeRemote, remoteProgress = null, json = json)

        val result = try {
            executor.execute(entry("project", "p-1", "insert", json.encodeToString(testProject)))
        } catch (_: Exception) {
            false
        }

        assertFalse(result)
    }

    // --- Progress dispatch tests ---

    @Test
    fun `progress insert dispatches to remote`() = runTest {
        val fakeRemote = FakeRemoteProgressDataSource()
        val executor = SyncExecutor(remoteProject = null, remoteProgress = fakeRemote, json = json)

        val result = executor.execute(
            entry("progress", "pr-1", "insert", json.encodeToString(testProgress)),
        )

        assertTrue(result)
        assertEquals(1, fakeRemote.insertedProgress.size)
        assertEquals("pr-1", fakeRemote.insertedProgress[0].id)
    }

    @Test
    fun `progress delete dispatches to remote`() = runTest {
        val fakeRemote = FakeRemoteProgressDataSource()
        val executor = SyncExecutor(remoteProject = null, remoteProgress = fakeRemote, json = json)

        val result = executor.execute(entry("progress", "pr-1", "delete"))

        assertTrue(result)
        assertEquals(1, fakeRemote.deletedIds.size)
        assertEquals("pr-1", fakeRemote.deletedIds[0])
    }
}
