package com.knitnote.data.local

import com.knitnote.db.KnitNoteDatabase
import com.knitnote.db.createTestDriver
import com.knitnote.domain.model.Difficulty
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Visibility
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalPatternDataSourceTest {

    private lateinit var dataSource: LocalPatternDataSource

    private val testPattern = Pattern(
        id = "pat-1",
        ownerId = "user-1",
        title = "Test Scarf Pattern",
        description = "A warm scarf",
        difficulty = Difficulty.BEGINNER,
        gauge = null,
        yarnInfo = null,
        needleSize = null,
        chartImageUrls = emptyList(),
        visibility = Visibility.PRIVATE,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        val db = KnitNoteDatabase(driver)
        dataSource = LocalPatternDataSource(db)
    }

    @Test
    fun `insert and getById returns pattern`() = runTest {
        dataSource.insert(testPattern)
        val result = dataSource.getById("pat-1")
        assertNotNull(result)
        assertEquals("Test Scarf Pattern", result.title)
        assertEquals(Difficulty.BEGINNER, result.difficulty)
        assertEquals(Visibility.PRIVATE, result.visibility)
    }

    @Test
    fun `getById returns null for non-existent`() = runTest {
        assertNull(dataSource.getById("non-existent"))
    }

    @Test
    fun `getByOwnerId returns patterns for owner`() = runTest {
        dataSource.insert(testPattern)
        dataSource.insert(testPattern.copy(id = "pat-2", title = "Second Pattern"))
        dataSource.insert(testPattern.copy(id = "pat-3", ownerId = "other-user"))

        val results = dataSource.getByOwnerId("user-1")
        assertEquals(2, results.size)
    }

    @Test
    fun `update modifies pattern`() = runTest {
        dataSource.insert(testPattern)
        val updated = testPattern.copy(
            title = "Updated Title",
            visibility = Visibility.SHARED,
            updatedAt = Instant.fromEpochMilliseconds(3000),
        )
        dataSource.update(updated)

        val result = dataSource.getById("pat-1")
        assertNotNull(result)
        assertEquals("Updated Title", result.title)
        assertEquals(Visibility.SHARED, result.visibility)
    }

    @Test
    fun `delete removes pattern`() = runTest {
        dataSource.insert(testPattern)
        dataSource.delete("pat-1")
        assertNull(dataSource.getById("pat-1"))
    }

    @Test
    fun `upsert inserts new pattern`() = runTest {
        dataSource.upsert(testPattern)
        val result = dataSource.getById("pat-1")
        assertNotNull(result)
        assertEquals("Test Scarf Pattern", result.title)
    }

    @Test
    fun `upsert updates existing pattern`() = runTest {
        dataSource.insert(testPattern)
        val updated = testPattern.copy(title = "Upserted Title")
        dataSource.upsert(updated)

        val result = dataSource.getById("pat-1")
        assertNotNull(result)
        assertEquals("Upserted Title", result.title)
    }

    @Test
    fun `upsertAll handles mix of new and existing`() = runTest {
        dataSource.insert(testPattern)
        val patterns = listOf(
            testPattern.copy(title = "Updated First"),
            testPattern.copy(id = "pat-2", title = "New Second"),
        )
        dataSource.upsertAll(patterns)

        assertEquals("Updated First", dataSource.getById("pat-1")?.title)
        assertEquals("New Second", dataSource.getById("pat-2")?.title)
    }

    @Test
    fun `insert with null difficulty`() = runTest {
        val patternNoDifficulty = testPattern.copy(difficulty = null)
        dataSource.insert(patternNoDifficulty)
        val result = dataSource.getById("pat-1")
        assertNotNull(result)
        assertNull(result.difficulty)
    }
}
