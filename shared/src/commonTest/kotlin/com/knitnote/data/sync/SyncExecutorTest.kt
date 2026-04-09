package com.knitnote.data.sync

import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Progress
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.model.Visibility
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import com.knitnote.testJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncExecutorTest {

    private val json = testJson

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
        entityType: SyncEntityType,
        entityId: String,
        operation: SyncOperation,
        payload: String = "",
    ) = PendingSyncEntry(
        id = 1,
        entityType = entityType,
        entityId = entityId,
        operation = operation,
        payload = payload,
        createdAt = 1000L,
        retryCount = 0,
        status = SyncStatus.PENDING,
    )

    // --- Null remote tests ---

    @Test
    fun `null remoteProject returns true`() = runTest {
        val executor = SyncExecutor(remoteProject = null, remoteProgress = null, remotePattern = null, json = json)
        val result = executor.execute(entry(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, json.encodeToString(testProject)))
        assertTrue(result)
    }

    @Test
    fun `null remoteProgress returns true`() = runTest {
        val executor = SyncExecutor(remoteProject = null, remoteProgress = null, remotePattern = null, json = json)
        val result = executor.execute(entry(SyncEntityType.PROGRESS, "pr-1", SyncOperation.INSERT, json.encodeToString(testProgress)))
        assertTrue(result)
    }

    // --- Project dispatch tests (using FakeRemoteProjectDataSource) ---

    @Test
    fun `project insert dispatches to remote`() = runTest {
        val fakeRemote = FakeRemoteProjectDataSource()
        val executor = SyncExecutor(remoteProject = fakeRemote, remoteProgress = null, remotePattern = null, json = json)

        val result = executor.execute(
            entry(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, json.encodeToString(testProject)),
        )

        assertTrue(result)
        assertEquals(1, fakeRemote.insertedProjects.size)
        assertEquals("p-1", fakeRemote.insertedProjects[0].id)
    }

    @Test
    fun `project update dispatches to remote`() = runTest {
        val fakeRemote = FakeRemoteProjectDataSource()
        val executor = SyncExecutor(remoteProject = fakeRemote, remoteProgress = null, remotePattern = null, json = json)

        val result = executor.execute(
            entry(SyncEntityType.PROJECT, "p-1", SyncOperation.UPDATE, json.encodeToString(testProject)),
        )

        assertTrue(result)
        assertEquals(1, fakeRemote.updatedProjects.size)
    }

    @Test
    fun `project delete dispatches to remote`() = runTest {
        val fakeRemote = FakeRemoteProjectDataSource()
        val executor = SyncExecutor(remoteProject = fakeRemote, remoteProgress = null, remotePattern = null, json = json)

        val result = executor.execute(entry(SyncEntityType.PROJECT, "p-1", SyncOperation.DELETE))

        assertTrue(result)
        assertEquals(1, fakeRemote.deletedIds.size)
        assertEquals("p-1", fakeRemote.deletedIds[0])
    }

    @Test
    fun `project insert failure returns false`() = runTest {
        val fakeRemote = FakeRemoteProjectDataSource()
        fakeRemote.shouldFail = true
        val executor = SyncExecutor(remoteProject = fakeRemote, remoteProgress = null, remotePattern = null, json = json)

        val result = try {
            executor.execute(entry(SyncEntityType.PROJECT, "p-1", SyncOperation.INSERT, json.encodeToString(testProject)))
        } catch (_: Exception) {
            false
        }

        assertFalse(result)
    }

    // --- Progress dispatch tests ---

    @Test
    fun `progress insert dispatches to remote`() = runTest {
        val fakeRemote = FakeRemoteProgressDataSource()
        val executor = SyncExecutor(remoteProject = null, remoteProgress = fakeRemote, remotePattern = null, json = json)

        val result = executor.execute(
            entry(SyncEntityType.PROGRESS, "pr-1", SyncOperation.INSERT, json.encodeToString(testProgress)),
        )

        assertTrue(result)
        assertEquals(1, fakeRemote.insertedProgress.size)
        assertEquals("pr-1", fakeRemote.insertedProgress[0].id)
    }

    @Test
    fun `progress delete dispatches to remote`() = runTest {
        val fakeRemote = FakeRemoteProgressDataSource()
        val executor = SyncExecutor(remoteProject = null, remoteProgress = fakeRemote, remotePattern = null, json = json)

        val result = executor.execute(entry(SyncEntityType.PROGRESS, "pr-1", SyncOperation.DELETE))

        assertTrue(result)
        assertEquals(1, fakeRemote.deletedIds.size)
        assertEquals("pr-1", fakeRemote.deletedIds[0])
    }

    // --- Pattern dispatch tests ---

    private val testPattern = Pattern(
        id = "pat-1",
        ownerId = "user-1",
        title = "Test Pattern",
        description = null,
        difficulty = null,
        gauge = null,
        yarnInfo = null,
        needleSize = null,
        chartImageUrls = emptyList(),
        visibility = Visibility.SHARED,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    @Test
    fun `null remotePattern returns true`() = runTest {
        val executor = SyncExecutor(remoteProject = null, remoteProgress = null, remotePattern = null, json = json)
        val result = executor.execute(entry(SyncEntityType.PATTERN, "pat-1", SyncOperation.INSERT, json.encodeToString(testPattern)))
        assertTrue(result)
    }

    @Test
    fun `pattern insert dispatches to remote`() = runTest {
        val fakeRemote = FakeRemotePatternDataSource()
        val executor = SyncExecutor(remoteProject = null, remoteProgress = null, remotePattern = fakeRemote, json = json)

        val result = executor.execute(
            entry(SyncEntityType.PATTERN, "pat-1", SyncOperation.INSERT, json.encodeToString(testPattern)),
        )

        assertTrue(result)
        assertEquals(1, fakeRemote.insertedPatterns.size)
        assertEquals("pat-1", fakeRemote.insertedPatterns[0].id)
    }

    @Test
    fun `pattern update dispatches to remote`() = runTest {
        val fakeRemote = FakeRemotePatternDataSource()
        val executor = SyncExecutor(remoteProject = null, remoteProgress = null, remotePattern = fakeRemote, json = json)

        val result = executor.execute(
            entry(SyncEntityType.PATTERN, "pat-1", SyncOperation.UPDATE, json.encodeToString(testPattern)),
        )

        assertTrue(result)
        assertEquals(1, fakeRemote.updatedPatterns.size)
    }

    @Test
    fun `pattern delete dispatches to remote`() = runTest {
        val fakeRemote = FakeRemotePatternDataSource()
        val executor = SyncExecutor(remoteProject = null, remoteProgress = null, remotePattern = fakeRemote, json = json)

        val result = executor.execute(entry(SyncEntityType.PATTERN, "pat-1", SyncOperation.DELETE))

        assertTrue(result)
        assertEquals(1, fakeRemote.deletedIds.size)
        assertEquals("pat-1", fakeRemote.deletedIds[0])
    }
}
