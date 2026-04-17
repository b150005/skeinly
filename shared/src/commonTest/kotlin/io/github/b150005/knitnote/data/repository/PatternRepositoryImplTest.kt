package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.local.LocalPatternDataSource
import io.github.b150005.knitnote.data.sync.FakeSyncManager
import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncOperation
import io.github.b150005.knitnote.db.KnitNoteDatabase
import io.github.b150005.knitnote.db.createTestDriver
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.testJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class PatternRepositoryImplTest {
    private lateinit var db: KnitNoteDatabase
    private lateinit var repository: PatternRepositoryImpl
    private lateinit var fakeSyncManager: FakeSyncManager
    private val isOnline = MutableStateFlow(false)
    private val json = testJson

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        db = KnitNoteDatabase(driver)
        fakeSyncManager = FakeSyncManager()
        repository =
            PatternRepositoryImpl(
                local = LocalPatternDataSource(db, Dispatchers.Unconfined),
                remote = null,
                isOnline = isOnline,
                syncManager = fakeSyncManager,
                json = json,
            )
    }

    private fun createTestPattern(
        id: String = "test-pattern-1",
        title: String = "Cable Knit Scarf",
        description: String? = "A classic cable pattern",
        difficulty: Difficulty? = Difficulty.INTERMEDIATE,
        visibility: Visibility = Visibility.PRIVATE,
    ): Pattern =
        Pattern(
            id = id,
            ownerId = "local-user",
            title = title,
            description = description,
            difficulty = difficulty,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = visibility,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )

    @Test
    fun `insert and retrieve pattern by id`() =
        runTest {
            val pattern = createTestPattern()
            repository.create(pattern)

            val retrieved = repository.getById(pattern.id)

            assertNotNull(retrieved)
            assertEquals(pattern.id, retrieved.id)
            assertEquals(pattern.title, retrieved.title)
            assertEquals(pattern.difficulty, retrieved.difficulty)
            assertEquals(pattern.visibility, retrieved.visibility)
        }

    @Test
    fun `getById returns null for non-existent id`() =
        runTest {
            val result = repository.getById("non-existent")
            assertNull(result)
        }

    @Test
    fun `retrieve by owner id returns patterns`() =
        runTest {
            val pattern1 = createTestPattern(id = "p1", title = "Pattern A")
            val pattern2 = createTestPattern(id = "p2", title = "Pattern B")
            repository.create(pattern1)
            repository.create(pattern2)

            val patterns = repository.getByOwnerId("local-user")

            assertEquals(2, patterns.size)
        }

    @Test
    fun `update pattern fields`() =
        runTest {
            val pattern = createTestPattern()
            repository.create(pattern)

            val updated =
                pattern.copy(
                    title = "Updated Cable Scarf",
                    difficulty = Difficulty.ADVANCED,
                    visibility = Visibility.SHARED,
                )
            repository.update(updated)

            val retrieved = repository.getById(pattern.id)
            assertNotNull(retrieved)
            assertEquals("Updated Cable Scarf", retrieved.title)
            assertEquals(Difficulty.ADVANCED, retrieved.difficulty)
            assertEquals(Visibility.SHARED, retrieved.visibility)
        }

    @Test
    fun `delete pattern removes it`() =
        runTest {
            val pattern = createTestPattern()
            repository.create(pattern)

            repository.delete(pattern.id)

            val retrieved = repository.getById(pattern.id)
            assertNull(retrieved)
        }

    @Test
    fun `observeByOwnerId emits current list`() =
        runTest {
            val pattern = createTestPattern()
            repository.create(pattern)

            val patterns = repository.observeByOwnerId("local-user").first()

            assertEquals(1, patterns.size)
            assertEquals(pattern.id, patterns[0].id)
        }

    // --- SyncManager integration tests ---

    @Test
    fun `create calls syncOrEnqueue with insert operation`() =
        runTest {
            val pattern = createTestPattern()
            repository.create(pattern)

            assertEquals(1, fakeSyncManager.calls.size)
            val call = fakeSyncManager.calls[0]
            assertEquals(SyncEntityType.PATTERN, call.entityType)
            assertEquals(pattern.id, call.entityId)
            assertEquals(SyncOperation.INSERT, call.operation)
            assertTrue(call.payload.contains(pattern.id))
        }

    @Test
    fun `update calls syncOrEnqueue with update operation`() =
        runTest {
            val pattern = createTestPattern()
            repository.create(pattern)
            fakeSyncManager.calls.clear()

            val updated = pattern.copy(title = "Updated Title")
            repository.update(updated)

            assertEquals(1, fakeSyncManager.calls.size)
            assertEquals(SyncOperation.UPDATE, fakeSyncManager.calls[0].operation)
        }

    @Test
    fun `delete calls syncOrEnqueue with delete operation`() =
        runTest {
            val pattern = createTestPattern()
            repository.create(pattern)
            fakeSyncManager.calls.clear()

            repository.delete(pattern.id)

            assertEquals(1, fakeSyncManager.calls.size)
            val call = fakeSyncManager.calls[0]
            assertEquals(SyncEntityType.PATTERN, call.entityType)
            assertEquals(pattern.id, call.entityId)
            assertEquals(SyncOperation.DELETE, call.operation)
            assertEquals("", call.payload)
        }
}
