package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.local.LocalStructuredChartDataSource
import io.github.b150005.knitnote.data.sync.FakeSyncManager
import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncOperation
import io.github.b150005.knitnote.db.KnitNoteDatabase
import io.github.b150005.knitnote.db.createTestDriver
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
}
