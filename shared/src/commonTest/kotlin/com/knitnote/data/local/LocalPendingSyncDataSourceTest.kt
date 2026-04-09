package com.knitnote.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.knitnote.db.KnitNoteDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class LocalPendingSyncDataSourceTest {

    private lateinit var dataSource: LocalPendingSyncDataSource

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KnitNoteDatabase.Schema.create(driver)
        val db = KnitNoteDatabase(driver)
        dataSource = LocalPendingSyncDataSource(db)
    }

    @Test
    fun `enqueue and retrieve pending entries`() = runTest {
        dataSource.enqueue("project", "p-1", "insert", """{"id":"p-1"}""", now())
        dataSource.enqueue("progress", "pr-1", "insert", """{"id":"pr-1"}""", now())

        val entries = dataSource.getAllPending()

        assertEquals(2, entries.size)
        assertEquals("project", entries[0].entityType)
        assertEquals("p-1", entries[0].entityId)
        assertEquals("insert", entries[0].operation)
        assertEquals(0, entries[0].retryCount)
        assertEquals("pending", entries[0].status)
    }

    @Test
    fun `getAllPending returns entries in created_at ASC order`() = runTest {
        dataSource.enqueue("project", "p-1", "insert", "{}", 1000L)
        dataSource.enqueue("project", "p-2", "insert", "{}", 500L)
        dataSource.enqueue("project", "p-3", "insert", "{}", 2000L)

        val entries = dataSource.getAllPending()

        assertEquals("p-2", entries[0].entityId)
        assertEquals("p-1", entries[1].entityId)
        assertEquals("p-3", entries[2].entityId)
    }

    @Test
    fun `getById returns correct entry`() = runTest {
        dataSource.enqueue("project", "p-1", "insert", """{"id":"p-1"}""", now())

        val entries = dataSource.getAllPending()
        val entry = dataSource.getById(entries[0].id)

        assertNotNull(entry)
        assertEquals("p-1", entry.entityId)
    }

    @Test
    fun `getById returns null for non-existent id`() = runTest {
        val entry = dataSource.getById(999L)
        assertNull(entry)
    }

    @Test
    fun `getByEntityId filters correctly`() = runTest {
        dataSource.enqueue("project", "p-1", "insert", "{}", now())
        dataSource.enqueue("project", "p-2", "update", "{}", now())
        dataSource.enqueue("project", "p-1", "update", "{}", now())

        val entries = dataSource.getByEntityId("p-1")

        assertEquals(2, entries.size)
        assertTrue(entries.all { it.entityId == "p-1" })
    }

    @Test
    fun `incrementRetry increases retry count`() = runTest {
        dataSource.enqueue("project", "p-1", "insert", "{}", now())

        val entry = dataSource.getAllPending()[0]
        assertEquals(0, entry.retryCount)

        dataSource.incrementRetry(entry.id)
        dataSource.incrementRetry(entry.id)

        val updated = dataSource.getById(entry.id)
        assertNotNull(updated)
        assertEquals(2, updated.retryCount)
    }

    @Test
    fun `markFailed changes status and excludes from getAllPending`() = runTest {
        dataSource.enqueue("project", "p-1", "insert", "{}", now())
        dataSource.enqueue("project", "p-2", "insert", "{}", now())

        val entries = dataSource.getAllPending()
        dataSource.markFailed(entries[0].id)

        val pending = dataSource.getAllPending()
        assertEquals(1, pending.size)
        assertEquals("p-2", pending[0].entityId)

        val failed = dataSource.getById(entries[0].id)
        assertNotNull(failed)
        assertEquals("failed", failed.status)
    }

    @Test
    fun `delete removes entry`() = runTest {
        dataSource.enqueue("project", "p-1", "insert", "{}", now())

        val entry = dataSource.getAllPending()[0]
        dataSource.delete(entry.id)

        val remaining = dataSource.getAllPending()
        assertTrue(remaining.isEmpty())
        assertNull(dataSource.getById(entry.id))
    }

    @Test
    fun `countPending returns correct count`() = runTest {
        assertEquals(0, dataSource.countPending())

        dataSource.enqueue("project", "p-1", "insert", "{}", now())
        dataSource.enqueue("project", "p-2", "insert", "{}", now())

        assertEquals(2, dataSource.countPending())

        val entry = dataSource.getAllPending()[0]
        dataSource.markFailed(entry.id)

        assertEquals(1, dataSource.countPending())
    }

    @Test
    fun `updatePayload changes payload for entry`() = runTest {
        dataSource.enqueue("project", "p-1", "insert", """{"v":1}""", now())

        val entry = dataSource.getAllPending()[0]
        dataSource.updatePayload(entry.id, """{"v":2}""")

        val updated = dataSource.getById(entry.id)
        assertNotNull(updated)
        assertEquals("""{"v":2}""", updated.payload)
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
}
