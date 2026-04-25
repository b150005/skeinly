package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.local.LocalChartRevisionDataSource
import io.github.b150005.knitnote.data.sync.FakeSyncManager
import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncOperation
import io.github.b150005.knitnote.db.KnitNoteDatabase
import io.github.b150005.knitnote.db.createTestDriver
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.ChartRevision
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.StorageVariant
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
import kotlin.time.Instant

class ChartRevisionRepositoryImplTest {
    private lateinit var db: KnitNoteDatabase
    private lateinit var local: LocalChartRevisionDataSource
    private lateinit var repository: ChartRevisionRepositoryImpl
    private lateinit var fakeSyncManager: FakeSyncManager
    private val isOnline = MutableStateFlow(false)

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        db = KnitNoteDatabase(driver)
        local = LocalChartRevisionDataSource(db, Dispatchers.Unconfined, testJson)
        fakeSyncManager = FakeSyncManager()
        repository =
            ChartRevisionRepositoryImpl(
                local = local,
                remote = null,
                isOnline = isOnline,
                syncManager = fakeSyncManager,
                json = testJson,
            )
    }

    private fun testRevision(
        id: String = "rev-row-1",
        revisionId: String = "rev-1",
        patternId: String = "pat-1",
        parentRevisionId: String? = null,
        commitMessage: String? = null,
        createdAtIso: String = "2026-04-25T10:00:00Z",
        cells: List<ChartCell> = listOf(ChartCell(symbolId = "jis.k1", x = 0, y = 0)),
    ): ChartRevision {
        val createdAt = Instant.parse(createdAtIso)
        val extents = ChartExtents.Rect(0, 0, 0, 0)
        val layers = listOf(ChartLayer(id = "L1", name = "Main", cells = cells))
        return ChartRevision(
            id = id,
            patternId = patternId,
            ownerId = "user-1",
            authorId = "user-1",
            schemaVersion = 2,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = extents,
            layers = layers,
            revisionId = revisionId,
            parentRevisionId = parentRevisionId,
            contentHash = "h1-test",
            commitMessage = commitMessage,
            createdAt = createdAt,
        )
    }

    // ---- append ----

    @Test
    fun `append persists revision and enqueues INSERT sync`() =
        runTest {
            val revision = testRevision()
            val returned = repository.append(revision)

            assertEquals(revision, returned)
            assertEquals(revision, repository.getRevision(revision.revisionId))

            assertEquals(1, fakeSyncManager.calls.size)
            val call = fakeSyncManager.calls.first()
            assertEquals(SyncEntityType.CHART_REVISION, call.entityType)
            assertEquals(revision.id, call.entityId)
            assertEquals(SyncOperation.INSERT, call.operation)
            assertTrue(call.payload.isNotEmpty())
        }

    @Test
    fun `append preserves commitMessage and authorId round-trip`() =
        runTest {
            val revision =
                testRevision(
                    commitMessage = "Reworked the cuff",
                )
            repository.append(revision)

            val retrieved = repository.getRevision(revision.revisionId)
            assertNotNull(retrieved)
            assertEquals("Reworked the cuff", retrieved.commitMessage)
            assertEquals("user-1", retrieved.authorId)
        }

    @Test
    fun `append preserves null commitMessage for auto-saves`() =
        runTest {
            val revision = testRevision(commitMessage = null)
            repository.append(revision)

            val retrieved = repository.getRevision(revision.revisionId)
            assertNotNull(retrieved)
            assertNull(retrieved.commitMessage)
        }

    @Test
    fun `append two revisions with chained parent_revision_id preserves lineage`() =
        runTest {
            val first = testRevision(id = "row-1", revisionId = "rev-1", parentRevisionId = null)
            val second = testRevision(id = "row-2", revisionId = "rev-2", parentRevisionId = "rev-1")
            repository.append(first)
            repository.append(second)

            val retrieved = repository.getRevision("rev-2")
            assertNotNull(retrieved)
            assertEquals("rev-1", retrieved.parentRevisionId)
        }

    // ---- getRevision ----

    @Test
    fun `getRevision returns null when revision is not found locally`() =
        runTest {
            assertNull(repository.getRevision("rev-nonexistent"))
        }

    @Test
    fun `getRevision is case-sensitive on revisionId`() =
        runTest {
            repository.append(testRevision(revisionId = "rev-Lower"))
            assertNull(repository.getRevision("REV-LOWER"))
            assertNotNull(repository.getRevision("rev-Lower"))
        }

    // ---- getHistoryForPattern ordering + pagination ----

    @Test
    fun `getHistoryForPattern returns rows newest first by created_at`() =
        runTest {
            repository.append(testRevision(id = "row-1", revisionId = "rev-1", createdAtIso = "2026-04-20T10:00:00Z"))
            repository.append(testRevision(id = "row-2", revisionId = "rev-2", createdAtIso = "2026-04-25T10:00:00Z"))
            repository.append(testRevision(id = "row-3", revisionId = "rev-3", createdAtIso = "2026-04-22T10:00:00Z"))

            val rows = repository.getHistoryForPattern("pat-1", limit = 50, offset = 0)

            assertEquals(listOf("rev-2", "rev-3", "rev-1"), rows.map { it.revisionId })
        }

    @Test
    fun `getHistoryForPattern paginates via limit and offset`() =
        runTest {
            repeat(5) { i ->
                repository.append(
                    testRevision(
                        id = "row-$i",
                        revisionId = "rev-$i",
                        createdAtIso = "2026-04-2${i}T10:00:00Z",
                    ),
                )
            }

            val firstPage = repository.getHistoryForPattern("pat-1", limit = 2, offset = 0)
            assertEquals(listOf("rev-4", "rev-3"), firstPage.map { it.revisionId })

            val secondPage = repository.getHistoryForPattern("pat-1", limit = 2, offset = 2)
            assertEquals(listOf("rev-2", "rev-1"), secondPage.map { it.revisionId })

            val thirdPage = repository.getHistoryForPattern("pat-1", limit = 2, offset = 4)
            assertEquals(listOf("rev-0"), thirdPage.map { it.revisionId })
        }

    @Test
    fun `getHistoryForPattern returns empty list when no revisions exist`() =
        runTest {
            val rows = repository.getHistoryForPattern("pat-empty", limit = 50, offset = 0)
            assertTrue(rows.isEmpty())
        }

    @Test
    fun `getHistoryForPattern scopes results to the requested patternId`() =
        runTest {
            repository.append(testRevision(id = "row-1", revisionId = "rev-1", patternId = "pat-1"))
            repository.append(testRevision(id = "row-2", revisionId = "rev-2", patternId = "pat-2"))

            val rowsForOne = repository.getHistoryForPattern("pat-1", limit = 50, offset = 0)
            val rowsForTwo = repository.getHistoryForPattern("pat-2", limit = 50, offset = 0)

            assertEquals(listOf("rev-1"), rowsForOne.map { it.revisionId })
            assertEquals(listOf("rev-2"), rowsForTwo.map { it.revisionId })
        }

    // ---- observeHistoryForPattern ----

    @Test
    fun `observeHistoryForPattern emits current snapshot from local`() =
        runTest {
            repository.append(testRevision(id = "row-1", revisionId = "rev-1"))
            repository.append(testRevision(id = "row-2", revisionId = "rev-2", createdAtIso = "2026-04-26T10:00:00Z"))

            val snapshot = repository.observeHistoryForPattern("pat-1").first()

            assertEquals(listOf("rev-2", "rev-1"), snapshot.map { it.revisionId })
        }

    // ---- richer round-trip ----

    @Test
    fun `append round-trips parametric symbol parameters through document json`() =
        runTest {
            val revision =
                testRevision(
                    cells =
                        listOf(
                            ChartCell(symbolId = "jis.k1", x = 0, y = 0),
                            ChartCell(
                                symbolId = "jis.crochet.ch-space",
                                x = 1,
                                y = 0,
                                symbolParameters = mapOf("n" to "5"),
                            ),
                        ),
                )
            repository.append(revision)

            val retrieved = repository.getRevision(revision.revisionId)
            assertNotNull(retrieved)
            assertEquals(revision.layers, retrieved.layers)
            assertEquals(mapOf("n" to "5"), retrieved.layers[0].cells[1].symbolParameters)
        }

    // ---- reverse-failure recovery (ADR-013 Consequences→Negative §2) ----
    //
    // Simulates the "remote tip UPDATE landed but remote revision INSERT failed"
    // path. PendingSync coalescing retries the INSERT next round; once it lands,
    // the local cache reflects the recovery via getRevision returning the row.
    //
    // We can't observe the cross-table inconsistency window here without remote
    // wiring (the local DS has no FK between chart_documents.revision_id and
    // chart_revisions.revision_id — both are independent local tables). What
    // this asserts is that a re-append of the same revisionId after a transient
    // failure is idempotent at the (pattern_id, revision_id) UNIQUE constraint:
    // INSERT OR IGNORE in the local upsert path silently no-ops, and getRevision
    // continues to return the canonical row.

    @Test
    fun `append followed by a re-append of the same revisionId is idempotent`() =
        runTest {
            val revision = testRevision()
            repository.append(revision)
            // Simulate the Realtime backfill arriving with the same revision_id
            // after the local insert already landed. INSERT OR IGNORE in
            // ChartRevisionEntity makes this a silent no-op rather than a
            // constraint violation.
            local.upsert(revision)

            val retrieved = repository.getRevision(revision.revisionId)
            assertNotNull(retrieved)
            assertEquals(revision, retrieved)
            // Both the original append and a subsequent direct local upsert
            // count exactly once in the history.
            val rows = repository.getHistoryForPattern("pat-1", limit = 50, offset = 0)
            assertEquals(1, rows.size)
        }

    @Test
    fun `forward-failure recovery — reappending an enqueued revision is idempotent on local`() =
        runTest {
            // Simulates: the first append succeeded locally + enqueued INSERT, but
            // PendingSync then re-fires the same payload (network drop + retry).
            // The local row must remain canonical and the second append is a no-op.
            val revision = testRevision()
            repository.append(revision)
            // Pretend the remote round-trip echoes the same revision back through
            // the LocalChartRevisionDataSource.upsert path used by RealtimeSyncManager.
            local.upsert(revision)

            assertEquals(1, local.countForPattern("pat-1"))
        }
}
