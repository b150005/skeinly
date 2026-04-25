package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.local.LocalChartBranchDataSource
import io.github.b150005.knitnote.data.local.LocalChartRevisionDataSource
import io.github.b150005.knitnote.data.local.LocalStructuredChartDataSource
import io.github.b150005.knitnote.data.sync.FakeSyncManager
import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncOperation
import io.github.b150005.knitnote.db.KnitNoteDatabase
import io.github.b150005.knitnote.db.createTestDriver
import io.github.b150005.knitnote.domain.model.ChartBranch
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.testJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StructuredChartRepositoryImplTest {
    private lateinit var db: KnitNoteDatabase
    private lateinit var repository: StructuredChartRepositoryImpl
    private lateinit var fakeSyncManager: FakeSyncManager
    private val isOnline = MutableStateFlow(false)

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        db = KnitNoteDatabase(driver)
        fakeSyncManager = FakeSyncManager()
        repository =
            StructuredChartRepositoryImpl(
                local = LocalStructuredChartDataSource(db, Dispatchers.Unconfined, testJson),
                remote = null,
                isOnline = isOnline,
                syncManager = fakeSyncManager,
                json = testJson,
            )
    }

    private fun testChart(
        id: String = "chart-1",
        patternId: String = "pat-1",
        layers: List<ChartLayer> =
            listOf(
                ChartLayer(
                    id = "L1",
                    name = "Main",
                    cells = listOf(ChartCell(symbolId = "jis.k1", x = 0, y = 0)),
                ),
            ),
    ): StructuredChart {
        val now = Instant.parse("2026-04-17T10:00:00Z")
        val extents = ChartExtents.Rect(0, 0, 0, 0)
        return StructuredChart(
            id = id,
            patternId = patternId,
            ownerId = "user-1",
            schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = extents,
            layers = layers,
            revisionId = "rev-a",
            parentRevisionId = null,
            contentHash = StructuredChart.computeContentHash(extents, layers, testJson),
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `create persists chart and enqueues INSERT sync`() =
        runTest {
            val chart = testChart()
            repository.create(chart)

            val retrieved = repository.getByPatternId(chart.patternId)
            assertNotNull(retrieved)
            assertEquals(chart, retrieved)

            assertEquals(1, fakeSyncManager.calls.size)
            val call = fakeSyncManager.calls.first()
            assertEquals(SyncEntityType.STRUCTURED_CHART, call.entityType)
            assertEquals(chart.id, call.entityId)
            assertEquals(SyncOperation.INSERT, call.operation)
            assertTrue(call.payload.isNotEmpty())
        }

    @Test
    fun `update writes back and enqueues UPDATE sync`() =
        runTest {
            val chart = testChart()
            repository.create(chart)
            val updated =
                chart.copy(
                    revisionId = "rev-b",
                    parentRevisionId = chart.revisionId,
                    contentHash = "new-hash",
                )

            repository.update(updated)

            val retrieved = repository.getByPatternId(chart.patternId)
            assertNotNull(retrieved)
            assertEquals("rev-b", retrieved.revisionId)
            assertEquals(chart.revisionId, retrieved.parentRevisionId)

            val updateCall = fakeSyncManager.calls.last()
            assertEquals(SyncOperation.UPDATE, updateCall.operation)
        }

    @Test
    fun `delete removes row and enqueues DELETE sync`() =
        runTest {
            val chart = testChart()
            repository.create(chart)

            repository.delete(chart.id)

            assertNull(repository.getByPatternId(chart.patternId))
            val deleteCall = fakeSyncManager.calls.last()
            assertEquals(SyncOperation.DELETE, deleteCall.operation)
            assertEquals(chart.id, deleteCall.entityId)
        }

    @Test
    fun `existsByPatternId reflects current state`() =
        runTest {
            assertFalse(repository.existsByPatternId("pat-1"))
            repository.create(testChart())
            assertTrue(repository.existsByPatternId("pat-1"))
        }

    @Test
    fun `observeByPatternId emits current snapshot from local`() =
        runTest {
            val chart = testChart()
            repository.create(chart)

            val emitted = repository.observeByPatternId(chart.patternId).first()
            assertEquals(chart, emitted)
        }

    @Test
    fun `round-trip through local preserves layers and cells`() =
        runTest {
            val chart =
                testChart(
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                visible = false,
                                locked = true,
                                cells =
                                    listOf(
                                        ChartCell(symbolId = "jis.k1", x = 0, y = 0),
                                        ChartCell(symbolId = "std.yo", x = 1, y = 0, width = 2, height = 1, rotation = 90, colorId = "red"),
                                    ),
                            ),
                            ChartLayer(id = "L2", name = "Overlay"),
                        ),
                )
            repository.create(chart)

            val retrieved = repository.getByPatternId(chart.patternId)

            assertNotNull(retrieved)
            assertEquals(chart, retrieved)
            val firstLayer = retrieved.layers[0]
            assertEquals("Main", firstLayer.name)
            assertEquals(false, firstLayer.visible)
            assertEquals(true, firstLayer.locked)
            assertEquals(2, firstLayer.cells.size)
            assertEquals(90, firstLayer.cells[1].rotation)
            assertEquals("red", firstLayer.cells[1].colorId)
        }

    @Test
    fun `corrupt document in local yields null without throwing`() =
        runTest {
            val chart = testChart()
            repository.create(chart)
            // Simulate a corrupt document column by direct SQLite update
            db.structuredChartQueries.update(
                schema_version = 1L,
                storage_variant = "inline",
                coordinate_system = "rect_grid",
                document = "{ not valid json",
                revision_id = chart.revisionId,
                parent_revision_id = null,
                content_hash = chart.contentHash,
                updated_at = chart.updatedAt.toString(),
                id = chart.id,
            )

            // Read path must not throw; returns null so caller treats as "no chart"
            val observed = repository.observeByPatternId(chart.patternId).first()
            assertNull(observed)
            assertNull(repository.getByPatternId(chart.patternId))
        }

    @Test
    fun `upsert by patternId replaces a row with a different id`() =
        runTest {
            val originalChart = testChart(id = "local-uuid", patternId = "pat-1")
            repository.create(originalChart)

            // Remote returns the same logical chart with a different id
            val remoteChart = testChart(id = "remote-uuid", patternId = "pat-1")
            // upsert is an internal DS method — exercise via the local DS directly
            LocalStructuredChartDataSource(db, Dispatchers.Unconfined, testJson).upsert(remoteChart)

            val retrieved = repository.getByPatternId("pat-1")
            assertNotNull(retrieved)
            assertEquals("remote-uuid", retrieved.id)
        }

    // Phase 36.2 (ADR-012 §2): forkFor data-spine.

    @Test
    fun `forkFor returns null when source pattern has no chart`() =
        runTest {
            val result =
                repository.forkFor(
                    sourcePatternId = "pat-missing",
                    newPatternId = "pat-fork",
                    newOwnerId = "user-2",
                )
            assertNull(result)
            assertEquals(0, fakeSyncManager.calls.size)
        }

    @Test
    fun `forkFor mints fresh envelope ids and rewrites pattern_id and owner_id`() =
        runTest {
            val source = testChart()
            repository.create(source)
            fakeSyncManager.calls.clear()

            val cloned =
                repository.forkFor(
                    sourcePatternId = source.patternId,
                    newPatternId = "pat-fork",
                    newOwnerId = "user-2",
                )

            assertNotNull(cloned)
            assertNotEquals(source.id, cloned.id)
            assertNotEquals(source.revisionId, cloned.revisionId)
            assertEquals("pat-fork", cloned.patternId)
            assertEquals("user-2", cloned.ownerId)
        }

    @Test
    fun `forkFor sets parentRevisionId to source revisionId`() =
        runTest {
            val source = testChart()
            repository.create(source)

            val cloned = repository.forkFor(source.patternId, "pat-fork", "user-2")
            assertNotNull(cloned)
            assertEquals(source.revisionId, cloned.parentRevisionId)
        }

    @Test
    fun `forkFor preserves contentHash from source per ADR-008`() =
        runTest {
            val source = testChart()
            repository.create(source)

            val cloned = repository.forkFor(source.patternId, "pat-fork", "user-2")
            assertNotNull(cloned)
            // ADR-008 §7: content_hash describes drawable content, not lineage.
            // Byte-for-byte clone must round-trip the same hash.
            assertEquals(source.contentHash, cloned.contentHash)
        }

    @Test
    fun `forkFor preserves document body byte-equal across rich layers`() =
        runTest {
            val source =
                testChart(
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                visible = false,
                                locked = true,
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
                            ),
                            ChartLayer(id = "L2", name = "Overlay"),
                        ),
                )
            repository.create(source)

            val cloned = repository.forkFor(source.patternId, "pat-fork", "user-2")
            assertNotNull(cloned)
            assertEquals(source.extents, cloned.extents)
            assertEquals(source.layers, cloned.layers)
            assertEquals(source.schemaVersion, cloned.schemaVersion)
            assertEquals(source.storageVariant, cloned.storageVariant)
            assertEquals(source.coordinateSystem, cloned.coordinateSystem)
            assertEquals(source.craftType, cloned.craftType)
            assertEquals(source.readingConvention, cloned.readingConvention)
        }

    @Test
    fun `forkFor enqueues INSERT sync for the cloned envelope`() =
        runTest {
            val source = testChart()
            repository.create(source)
            fakeSyncManager.calls.clear()

            val cloned = repository.forkFor(source.patternId, "pat-fork", "user-2")
            assertNotNull(cloned)

            assertEquals(1, fakeSyncManager.calls.size)
            val call = fakeSyncManager.calls.first()
            assertEquals(SyncEntityType.STRUCTURED_CHART, call.entityType)
            assertEquals(cloned.id, call.entityId)
            assertEquals(SyncOperation.INSERT, call.operation)
            assertTrue(call.payload.isNotEmpty())
        }

    @Test
    fun `forkFor cloned chart is retrievable by newPatternId without disturbing source`() =
        runTest {
            val source = testChart()
            repository.create(source)
            val cloned = repository.forkFor(source.patternId, "pat-fork", "user-2")
            assertNotNull(cloned)

            // Both equality checks rely on the same SQLite round-trip semantics that
            // the existing `round-trip through local preserves layers and cells` test
            // exercises — `Instant.toString()` is round-trip stable for the test
            // timestamps used here, so equality holds field-by-field.
            assertEquals(cloned, repository.getByPatternId("pat-fork"))
            assertEquals(source, repository.getByPatternId(source.patternId))
        }

    @Test
    fun `forkFor of an already-forked chart chains parentRevisionId to immediate predecessor`() =
        runTest {
            val original = testChart(id = "chart-orig", patternId = "pat-orig")
            repository.create(original)

            val fork1 = repository.forkFor("pat-orig", "pat-fork-1", "user-2")
            assertNotNull(fork1)
            val fork2 = repository.forkFor("pat-fork-1", "pat-fork-2", "user-3")
            assertNotNull(fork2)

            // Each clone chains to its immediate predecessor — the fork's
            // own revisionId, not the original source's. Phase 37 collaboration
            // walks the chain to recover full ancestry.
            assertEquals(original.revisionId, fork1.parentRevisionId)
            assertEquals(fork1.revisionId, fork2.parentRevisionId)
            assertNotEquals(original.revisionId, fork2.parentRevisionId)
        }

    @Test
    fun `polar chart round-trips through sqlite`() =
        runTest {
            val now = Instant.parse("2026-04-17T10:00:00Z")
            val extents = ChartExtents.Polar(rings = 3, stitchesPerRing = listOf(6, 12, 18))
            val layers = listOf(ChartLayer(id = "L", name = "L"))
            val chart =
                StructuredChart(
                    id = "chart-polar",
                    patternId = "pat-polar",
                    ownerId = "user-1",
                    schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
                    storageVariant = StorageVariant.INLINE,
                    coordinateSystem = CoordinateSystem.POLAR_ROUND,
                    extents = extents,
                    layers = layers,
                    revisionId = "rev-polar",
                    parentRevisionId = null,
                    contentHash = StructuredChart.computeContentHash(extents, layers, testJson),
                    createdAt = now,
                    updatedAt = now,
                )
            repository.create(chart)
            val retrieved = repository.getByPatternId("pat-polar")
            assertNotNull(retrieved)
            assertEquals(CoordinateSystem.POLAR_ROUND, retrieved.coordinateSystem)
            assertTrue(retrieved.extents is ChartExtents.Polar)
            assertEquals(listOf(6, 12, 18), (retrieved.extents as ChartExtents.Polar).stitchesPerRing)
        }

    // ---- Phase 37.1 (ADR-013 §1, §7): commit history + ensureDefaultBranch ----
    //
    // The base setUp() configures the repo without revision/branch deps to keep
    // the existing pre-37.1 expectations intact. These tests build a separate
    // repo with the new dependencies wired so the append + branch behavior
    // surfaces in isolation.

    private fun setUpWithHistory(): Triple<StructuredChartRepositoryImpl, LocalChartRevisionDataSource, LocalChartBranchDataSource> {
        val revisionLocal = LocalChartRevisionDataSource(db, Dispatchers.Unconfined, testJson)
        val branchLocal = LocalChartBranchDataSource(db, Dispatchers.Unconfined)
        val revisionRepo =
            ChartRevisionRepositoryImpl(
                local = revisionLocal,
                remote = null,
                isOnline = isOnline,
                syncManager = fakeSyncManager,
                json = testJson,
            )
        val repoWithHistory =
            StructuredChartRepositoryImpl(
                local = LocalStructuredChartDataSource(db, Dispatchers.Unconfined, testJson),
                remote = null,
                isOnline = isOnline,
                syncManager = fakeSyncManager,
                json = testJson,
                chartRevisionRepository = revisionRepo,
                localChartBranch = branchLocal,
            )
        return Triple(repoWithHistory, revisionLocal, branchLocal)
    }

    @Test
    fun `create appends initial revision row matching tip`() =
        runTest {
            val (repo, revisionLocal, _) = setUpWithHistory()
            val chart = testChart()
            repo.create(chart)

            val history = revisionLocal.getHistoryForPattern(chart.patternId, limit = 50, offset = 0)
            assertEquals(1, history.size)
            val initial = history.first()
            assertEquals(chart.revisionId, initial.revisionId)
            assertEquals(chart.patternId, initial.patternId)
            assertEquals(chart.contentHash, initial.contentHash)
            assertEquals(chart.layers, initial.layers)
            assertNull(initial.parentRevisionId)
            // Phase 37 always writes ownerId == authorId.
            assertEquals(chart.ownerId, initial.authorId)
        }

    @Test
    fun `create bootstraps a default main branch pointing at the initial revision`() =
        runTest {
            val (repo, _, branchLocal) = setUpWithHistory()
            val chart = testChart()
            repo.create(chart)

            val branches = branchLocal.getByPatternId(chart.patternId)
            assertEquals(1, branches.size)
            val main = branches.first()
            assertEquals(ChartBranch.DEFAULT_BRANCH_NAME, main.branchName)
            assertEquals(chart.revisionId, main.tipRevisionId)
            assertEquals(chart.ownerId, main.ownerId)
        }

    @Test
    fun `create enqueues CHART_REVISION INSERT and CHART_BRANCH INSERT alongside the tip INSERT`() =
        runTest {
            val (repo, _, _) = setUpWithHistory()
            val chart = testChart()
            repo.create(chart)

            val types = fakeSyncManager.calls.map { it.entityType }
            assertTrue(SyncEntityType.STRUCTURED_CHART in types)
            assertTrue(SyncEntityType.CHART_REVISION in types)
            assertTrue(SyncEntityType.CHART_BRANCH in types)
            assertEquals(
                SyncOperation.INSERT,
                fakeSyncManager.calls.first { it.entityType == SyncEntityType.CHART_REVISION }.operation,
            )
            assertEquals(
                SyncOperation.INSERT,
                fakeSyncManager.calls.first { it.entityType == SyncEntityType.CHART_BRANCH }.operation,
            )
        }

    @Test
    fun `update appends a new revision linking parent to previous tip`() =
        runTest {
            val (repo, revisionLocal, _) = setUpWithHistory()
            val initial = testChart()
            repo.create(initial)
            // Edit timestamp must be strictly newer than initial.updatedAt so the
            // ORDER BY created_at DESC index resolves a deterministic ordering.
            // Two revisions sharing the same created_at would be a tie that
            // SQLite resolves by insertion order, but asserting on that is
            // brittle across SQLite implementations.
            val edited =
                initial.copy(
                    revisionId = "rev-b",
                    contentHash = "h1-edited",
                    parentRevisionId = initial.revisionId,
                    updatedAt = Instant.parse("2026-04-18T10:00:00Z"),
                )
            repo.update(edited)

            val history = revisionLocal.getHistoryForPattern(initial.patternId, limit = 50, offset = 0)
            // Two revisions in append order: rev-b is most recent.
            assertEquals(2, history.size)
            assertEquals("rev-b", history[0].revisionId)
            assertEquals(initial.revisionId, history[0].parentRevisionId)
            assertEquals(initial.revisionId, history[1].revisionId)
            assertNull(history[1].parentRevisionId)
        }

    @Test
    fun `update enqueues a CHART_REVISION INSERT in addition to the STRUCTURED_CHART UPDATE`() =
        runTest {
            val (repo, _, _) = setUpWithHistory()
            val initial = testChart()
            repo.create(initial)
            fakeSyncManager.calls.clear()
            val edited =
                initial.copy(
                    revisionId = "rev-b",
                    contentHash = "h1-edited",
                    parentRevisionId = initial.revisionId,
                    updatedAt = Instant.parse("2026-04-18T10:00:00Z"),
                )
            repo.update(edited)

            val revisionInserts =
                fakeSyncManager.calls.count {
                    it.entityType == SyncEntityType.CHART_REVISION && it.operation == SyncOperation.INSERT
                }
            val tipUpdates =
                fakeSyncManager.calls.count {
                    it.entityType == SyncEntityType.STRUCTURED_CHART && it.operation == SyncOperation.UPDATE
                }
            assertEquals(1, revisionInserts)
            assertEquals(1, tipUpdates)
        }

    @Test
    fun `forkFor appends a revision and ensures a default branch on the fork`() =
        runTest {
            val (repo, revisionLocal, branchLocal) = setUpWithHistory()
            val source = testChart()
            repo.create(source)
            fakeSyncManager.calls.clear()

            val cloned = repo.forkFor(source.patternId, "pat-fork", "user-2")

            assertNotNull(cloned)
            // Fork's revision row exists and points back at the source's revisionId.
            val forkHistory = revisionLocal.getHistoryForPattern("pat-fork", limit = 50, offset = 0)
            assertEquals(1, forkHistory.size)
            assertEquals(cloned.revisionId, forkHistory.first().revisionId)
            assertEquals(source.revisionId, forkHistory.first().parentRevisionId)

            // Fork's main branch points at the cloned revision.
            val forkBranches = branchLocal.getByPatternId("pat-fork")
            assertEquals(1, forkBranches.size)
            assertEquals(ChartBranch.DEFAULT_BRANCH_NAME, forkBranches.first().branchName)
            assertEquals(cloned.revisionId, forkBranches.first().tipRevisionId)
        }

    @Test
    fun `forkFor without history dependency wired still creates the fork tip`() =
        runTest {
            // Sanity that the existing forkFor data-spine path (no revision repo
            // or branch DS) is unchanged — confirms the new wiring is additive
            // and the optional ctor params honor a null default.
            val source = testChart()
            repository.create(source)
            val cloned = repository.forkFor(source.patternId, "pat-fork", "user-2")
            assertNotNull(cloned)
            assertEquals("pat-fork", cloned.patternId)
        }
}
