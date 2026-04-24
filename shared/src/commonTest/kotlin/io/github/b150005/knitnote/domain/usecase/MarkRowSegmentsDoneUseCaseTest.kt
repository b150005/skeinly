package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.CraftType
import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.model.ReadingConvention
import io.github.b150005.knitnote.domain.model.SegmentState
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.testJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class MarkRowSegmentsDoneUseCaseTest {
    private class FixedClock(
        val fixed: Instant,
    ) : Clock {
        override fun now(): Instant = fixed
    }

    private val now = Instant.parse("2026-04-25T00:00:00Z")

    private fun newUseCase(
        segments: FakeProjectSegmentRepository,
        charts: FakeStructuredChartRepository,
    ): MarkRowSegmentsDoneUseCase =
        MarkRowSegmentsDoneUseCase(
            repository = segments,
            getStructuredChart = GetStructuredChartByPatternIdUseCase(charts),
            authRepository = FakeAuthRepository(),
            clock = FixedClock(now),
        )

    private fun rectChartWith(layers: List<ChartLayer>): StructuredChart =
        StructuredChart(
            id = "chart-1",
            patternId = "pat-1",
            ownerId = "owner",
            schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = ChartExtents.Rect(minX = 0, maxX = 7, minY = 0, maxY = 7),
            layers = layers,
            revisionId = "rev-0",
            parentRevisionId = null,
            contentHash =
                StructuredChart.computeContentHash(
                    ChartExtents.Rect(minX = 0, maxX = 7, minY = 0, maxY = 7),
                    layers,
                    testJson,
                ),
            createdAt = now,
            updatedAt = now,
            craftType = CraftType.KNIT,
            readingConvention = ReadingConvention.KNIT_FLAT,
        )

    private fun polarChartWith(layers: List<ChartLayer>): StructuredChart {
        val extents = ChartExtents.Polar(rings = 3, stitchesPerRing = listOf(6, 12, 18))
        return StructuredChart(
            id = "chart-polar",
            patternId = "pat-1",
            ownerId = "owner",
            schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.POLAR_ROUND,
            extents = extents,
            layers = layers,
            revisionId = "rev-0",
            parentRevisionId = null,
            contentHash = StructuredChart.computeContentHash(extents, layers, testJson),
            createdAt = now,
            updatedAt = now,
            craftType = CraftType.CROCHET,
            readingConvention = ReadingConvention.ROUND,
        )
    }

    @Test
    fun `marks every cell on the target row as done across visible layers`() =
        runTest {
            val segments = FakeProjectSegmentRepository()
            val charts = FakeStructuredChartRepository()
            charts.seed(
                rectChartWith(
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells =
                                listOf(
                                    ChartCell(symbolId = "jis.knit.k", x = 0, y = 3),
                                    ChartCell(symbolId = "jis.knit.k", x = 1, y = 3),
                                    ChartCell(symbolId = "jis.knit.k", x = 2, y = 3),
                                    ChartCell(symbolId = "jis.knit.p", x = 0, y = 2),
                                ),
                        ),
                    ),
                ),
            )

            val result = newUseCase(segments, charts).invoke("pat-1", "proj-1", row = 3)

            assertTrue(result is UseCaseResult.Success)
            // Three y=3 cells across the single visible layer.
            assertNotNull(segments.getById(ProjectSegment.buildId("proj-1", "L1", 0, 3)))
            assertNotNull(segments.getById(ProjectSegment.buildId("proj-1", "L1", 1, 3)))
            assertNotNull(segments.getById(ProjectSegment.buildId("proj-1", "L1", 2, 3)))
            // y=2 cell is off-row and must remain untouched (absence = implicit todo).
            assertNull(segments.getById(ProjectSegment.buildId("proj-1", "L1", 0, 2)))
            // State on every touched segment is DONE.
            val done =
                listOf(0, 1, 2).map { x ->
                    segments.getById(ProjectSegment.buildId("proj-1", "L1", x, 3))
                }
            done.forEach { seg ->
                assertNotNull(seg)
                assertEquals(SegmentState.DONE, seg.state)
            }
        }

    @Test
    fun `skips cells on invisible layers so toggled-off reference layers stay untouched`() =
        runTest {
            val segments = FakeProjectSegmentRepository()
            val charts = FakeStructuredChartRepository()
            charts.seed(
                rectChartWith(
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells = listOf(ChartCell(symbolId = "jis.knit.k", x = 0, y = 4)),
                        ),
                        ChartLayer(
                            id = "L2",
                            name = "Reference",
                            cells = listOf(ChartCell(symbolId = "jis.knit.k", x = 1, y = 4)),
                            visible = false,
                        ),
                    ),
                ),
            )

            newUseCase(segments, charts).invoke("pat-1", "proj-1", row = 4)

            assertNotNull(segments.getById(ProjectSegment.buildId("proj-1", "L1", 0, 4)))
            // L2 is invisible — its cell MUST NOT have been upserted.
            assertNull(segments.getById(ProjectSegment.buildId("proj-1", "L2", 1, 4)))
        }

    @Test
    fun `empty row is a no-op`() =
        runTest {
            val segments = FakeProjectSegmentRepository()
            val charts = FakeStructuredChartRepository()
            charts.seed(
                rectChartWith(
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells = listOf(ChartCell(symbolId = "jis.knit.k", x = 0, y = 2)),
                        ),
                    ),
                ),
            )

            val result = newUseCase(segments, charts).invoke("pat-1", "proj-1", row = 5)

            assertTrue(result is UseCaseResult.Success)
            // No segments should have been written.
            assertEquals(emptyList<ProjectSegment>(), segments.getByProjectId("proj-1"))
        }

    @Test
    fun `polar ring maps cell y to ring index and upserts each stitch on that ring`() =
        runTest {
            val segments = FakeProjectSegmentRepository()
            val charts = FakeStructuredChartRepository()
            charts.seed(
                polarChartWith(
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells =
                                listOf(
                                    // Ring 1 (stitchesPerRing[1] = 12) — should be marked.
                                    ChartCell(symbolId = "jis.crochet.ch", x = 0, y = 1),
                                    ChartCell(symbolId = "jis.crochet.ch", x = 5, y = 1),
                                    // Ring 0 — must NOT be marked.
                                    ChartCell(symbolId = "jis.crochet.ch", x = 0, y = 0),
                                ),
                        ),
                    ),
                ),
            )

            newUseCase(segments, charts).invoke("pat-1", "proj-1", row = 1)

            assertNotNull(segments.getById(ProjectSegment.buildId("proj-1", "L1", 0, 1)))
            assertNotNull(segments.getById(ProjectSegment.buildId("proj-1", "L1", 5, 1)))
            assertNull(segments.getById(ProjectSegment.buildId("proj-1", "L1", 0, 0)))
        }

    @Test
    fun `missing chart yields success no-op`() =
        runTest {
            val segments = FakeProjectSegmentRepository()
            val charts = FakeStructuredChartRepository()
            // No chart seeded.

            val result = newUseCase(segments, charts).invoke("pat-missing", "proj-1", row = 0)

            assertTrue(result is UseCaseResult.Success)
            assertEquals(emptyList<ProjectSegment>(), segments.getByProjectId("proj-1"))
        }

    @Test
    fun `repository upsert failure is wrapped in UseCaseResult Failure`() =
        runTest {
            val segments =
                object : io.github.b150005.knitnote.domain.repository.ProjectSegmentRepository {
                    override fun observeByProjectId(projectId: String): kotlinx.coroutines.flow.Flow<List<ProjectSegment>> =
                        kotlinx.coroutines.flow.flowOf(emptyList())

                    override suspend fun getById(id: String): ProjectSegment? = null

                    override suspend fun getByProjectId(projectId: String): List<ProjectSegment> = emptyList()

                    override suspend fun upsert(segment: ProjectSegment): ProjectSegment = throw RuntimeException("db offline")

                    override suspend fun resetSegment(id: String) {}

                    override suspend fun resetProject(projectId: String) {}
                }
            val charts = FakeStructuredChartRepository()
            charts.seed(
                rectChartWith(
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells = listOf(ChartCell(symbolId = "jis.knit.k", x = 0, y = 3)),
                        ),
                    ),
                ),
            )
            val useCase =
                MarkRowSegmentsDoneUseCase(
                    repository = segments,
                    getStructuredChart = GetStructuredChartByPatternIdUseCase(charts),
                    authRepository = FakeAuthRepository(),
                    clock = FixedClock(now),
                )

            val result = useCase("pat-1", "proj-1", row = 3)

            assertTrue(result is UseCaseResult.Failure)
        }

    @Test
    fun `existing segment state is replaced with done and updatedAt advances`() =
        runTest {
            val segments = FakeProjectSegmentRepository()
            val older = Instant.parse("2026-04-24T00:00:00Z")
            val id = ProjectSegment.buildId("proj-1", "L1", 0, 3)
            segments.seed(
                ProjectSegment(
                    id = id,
                    projectId = "proj-1",
                    layerId = "L1",
                    cellX = 0,
                    cellY = 3,
                    state = SegmentState.WIP,
                    ownerId = "owner",
                    updatedAt = older,
                ),
            )
            val charts = FakeStructuredChartRepository()
            charts.seed(
                rectChartWith(
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells = listOf(ChartCell(symbolId = "jis.knit.k", x = 0, y = 3)),
                        ),
                    ),
                ),
            )

            newUseCase(segments, charts).invoke("pat-1", "proj-1", row = 3)

            val after = segments.getById(id)
            assertNotNull(after)
            assertEquals(SegmentState.DONE, after.state)
            assertEquals(now, after.updatedAt)
        }
}
