package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.testJson
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StructuredChartUseCasesTest {
    private lateinit var repo: FakeStructuredChartRepository
    private lateinit var auth: FakeAuthRepository

    private val now = Instant.parse("2026-04-17T10:00:00Z")

    @BeforeTest
    fun setUp() {
        repo = FakeStructuredChartRepository()
        auth = FakeAuthRepository()
        auth.setAuthState(AuthState.Authenticated(userId = "user-1", email = "t@example.com"))
    }

    private fun seededChart(
        patternId: String = "pat-1",
        layers: List<ChartLayer> = listOf(ChartLayer(id = "L1", name = "Main")),
    ): StructuredChart =
        StructuredChart(
            id = "chart-seed",
            patternId = patternId,
            ownerId = "user-1",
            schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = ChartExtents.Rect.EMPTY,
            layers = layers,
            revisionId = "rev-0",
            parentRevisionId = null,
            contentHash = "seed-hash",
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun `create chart succeeds for a pattern without existing chart`() =
        runTest {
            val useCase = CreateStructuredChartUseCase(repo, auth, testJson)

            val result = useCase(patternId = "pat-1")

            assertTrue(result is UseCaseResult.Success)
            assertEquals("pat-1", result.value.patternId)
            assertEquals("user-1", result.value.ownerId)
            assertEquals(StructuredChart.CURRENT_SCHEMA_VERSION, result.value.schemaVersion)
            assertEquals(StorageVariant.INLINE, result.value.storageVariant)
            assertNull(result.value.parentRevisionId)
            assertTrue(result.value.contentHash.isNotEmpty())
        }

    @Test
    fun `create chart fails when pattern already has a chart`() =
        runTest {
            repo.seed(seededChart(patternId = "pat-1"))
            val useCase = CreateStructuredChartUseCase(repo, auth, testJson)

            val result = useCase(patternId = "pat-1")

            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.Validation)
        }

    @Test
    fun `create chart rejects blank patternId`() =
        runTest {
            val useCase = CreateStructuredChartUseCase(repo, auth, testJson)
            val result = useCase(patternId = "   ")
            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.Validation)
        }

    @Test
    fun `create chart rejects mismatched coordinate system and extents`() =
        runTest {
            val useCase = CreateStructuredChartUseCase(repo, auth, testJson)
            val result =
                useCase(
                    patternId = "pat-1",
                    coordinateSystem = CoordinateSystem.POLAR_ROUND,
                    extents = ChartExtents.Rect(0, 1, 0, 1),
                )
            assertTrue(result is UseCaseResult.Failure)
        }

    @Test
    fun `create chart uses polar defaults for polar coordinate system`() =
        runTest {
            val useCase = CreateStructuredChartUseCase(repo, auth, testJson)
            val result = useCase(patternId = "pat-1", coordinateSystem = CoordinateSystem.POLAR_ROUND)
            assertTrue(result is UseCaseResult.Success)
            assertTrue(result.value.extents is ChartExtents.Polar)
        }

    @Test
    fun `update chart changes revision id and parent revision`() =
        runTest {
            val seeded = seededChart()
            repo.seed(seeded)
            val useCase = UpdateStructuredChartUseCase(repo, testJson)

            val newLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = emptyList()), ChartLayer(id = "L2", name = "Top"))
            val result = useCase(current = seeded, layers = newLayers)

            assertTrue(result is UseCaseResult.Success)
            assertEquals(2, result.value.layers.size)
            assertEquals(seeded.revisionId, result.value.parentRevisionId)
            assertNotEquals(seeded.revisionId, result.value.revisionId)
            assertNotEquals(seeded.contentHash, result.value.contentHash)
        }

    @Test
    fun `update chart is a noop when content unchanged`() =
        runTest {
            val seeded = seededChart()
            val sameHash = StructuredChart.computeContentHash(seeded.extents, seeded.layers, testJson)
            val withValidHash = seeded.copy(contentHash = sameHash)
            repo.seed(withValidHash)
            val useCase = UpdateStructuredChartUseCase(repo, testJson)

            val result = useCase(current = withValidHash)

            assertTrue(result is UseCaseResult.Success)
            assertEquals(withValidHash.revisionId, result.value.revisionId)
        }

    @Test
    fun `update promotes legacy schemaVersion 1 to CURRENT on any save`() =
        runTest {
            val legacy =
                seededChart().copy(
                    schemaVersion = 1,
                    contentHash =
                        StructuredChart.computeContentHash(
                            seededChart().extents,
                            seededChart().layers,
                            testJson,
                        ),
                )
            repo.seed(legacy)
            val useCase = UpdateStructuredChartUseCase(repo, testJson)

            val result = useCase(current = legacy)

            assertTrue(result is UseCaseResult.Success)
            assertEquals(StructuredChart.CURRENT_SCHEMA_VERSION, result.value.schemaVersion)
            assertNotEquals(
                legacy.revisionId,
                result.value.revisionId,
                "legacy schema promotion must produce a new revision",
            )
        }

    @Test
    fun `update with craft change bypasses noop shortcut`() =
        runTest {
            val seeded = seededChart()
            val matchingHash = StructuredChart.computeContentHash(seeded.extents, seeded.layers, testJson)
            val current = seeded.copy(contentHash = matchingHash)
            repo.seed(current)
            val useCase = UpdateStructuredChartUseCase(repo, testJson)

            val result =
                useCase(
                    current = current,
                    craftType = io.github.b150005.knitnote.domain.model.CraftType.CROCHET,
                )

            assertTrue(result is UseCaseResult.Success)
            assertEquals(
                io.github.b150005.knitnote.domain.model.CraftType.CROCHET,
                result.value.craftType,
            )
            assertNotEquals(current.revisionId, result.value.revisionId)
        }

    @Test
    fun `delete chart succeeds and removes from repository`() =
        runTest {
            val seeded = seededChart()
            repo.seed(seeded)
            val useCase = DeleteStructuredChartUseCase(repo)

            val result = useCase(seeded.id)

            assertTrue(result is UseCaseResult.Success)
            assertNull(repo.getByPatternId(seeded.patternId))
        }

    @Test
    fun `get by pattern id returns chart when present`() =
        runTest {
            val seeded = seededChart()
            repo.seed(seeded)
            val useCase = GetStructuredChartByPatternIdUseCase(repo)

            val result = useCase(seeded.patternId)

            assertTrue(result is UseCaseResult.Success)
            assertNotNull(result.value)
            assertEquals(seeded.id, result.value.id)
        }

    @Test
    fun `get by pattern id returns null when absent`() =
        runTest {
            val useCase = GetStructuredChartByPatternIdUseCase(repo)
            val result = useCase("missing")

            assertTrue(result is UseCaseResult.Success)
            assertNull(result.value)
        }

    @Test
    fun `content hash is deterministic for identical input`() {
        val extents = ChartExtents.Rect(0, 5, 0, 5)
        val layers = listOf(ChartLayer(id = "L1", name = "Main"))

        val h1 = StructuredChart.computeContentHash(extents, layers, testJson)
        val h2 = StructuredChart.computeContentHash(extents, layers, testJson)

        assertEquals(h1, h2)
    }

    @Test
    fun `content hash changes when layers change`() {
        val extents = ChartExtents.Rect.EMPTY
        val h1 = StructuredChart.computeContentHash(extents, emptyList(), testJson)
        val h2 = StructuredChart.computeContentHash(extents, listOf(ChartLayer(id = "X", name = "X")), testJson)
        assertNotEquals(h1, h2)
    }
}
