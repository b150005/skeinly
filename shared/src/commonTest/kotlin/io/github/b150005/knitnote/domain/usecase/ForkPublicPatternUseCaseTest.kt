package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.CraftType
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.model.ReadingConvention
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.model.Visibility
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ForkPublicPatternUseCaseTest {
    private val publicPattern =
        Pattern(
            id = "pub-1",
            ownerId = "other-user",
            title = "Cable Knit Sweater",
            description = "A cozy cable knit pattern",
            difficulty = Difficulty.INTERMEDIATE,
            gauge = "20 stitches / 4 inches",
            yarnInfo = "DK weight wool",
            needleSize = "4mm",
            chartImageUrls = listOf("https://example.com/chart.png"),
            visibility = Visibility.PUBLIC,
            createdAt = Instant.fromEpochMilliseconds(1000),
            updatedAt = Instant.fromEpochMilliseconds(2000),
        )

    private val privatePattern = publicPattern.copy(id = "priv-1", visibility = Visibility.PRIVATE)

    private fun chartFor(patternId: String): StructuredChart =
        StructuredChart(
            id = "chart-$patternId",
            patternId = patternId,
            ownerId = "other-user",
            schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 0),
            layers = listOf(ChartLayer(id = "L1", name = "Layer 1")),
            revisionId = "rev-source-1",
            parentRevisionId = null,
            contentHash = "h1-deadbeef",
            createdAt = Instant.fromEpochMilliseconds(500),
            updatedAt = Instant.fromEpochMilliseconds(500),
            craftType = CraftType.KNIT,
            readingConvention = ReadingConvention.KNIT_FLAT,
        )

    private fun createUseCase(
        patternRepo: FakePatternRepository = FakePatternRepository(),
        projectRepo: FakeProjectRepository = FakeProjectRepository(),
        chartRepo: FakeStructuredChartRepository = FakeStructuredChartRepository(),
        authRepo: FakeAuthRepository = FakeAuthRepository(),
    ) = ForkPublicPatternUseCase(patternRepo, projectRepo, chartRepo, authRepo)

    @Test
    fun `returns Validation failure when not authenticated`() =
        runTest {
            val useCase = createUseCase()

            val result = useCase("pub-1")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns NotFound failure when pattern does not exist`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val useCase = createUseCase(authRepo = authRepo)

            val result = useCase("nonexistent")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.NotFound>(result.error)
        }

    @Test
    fun `returns Validation failure when pattern is not public`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(privatePattern)
            val useCase = createUseCase(patternRepo = patternRepo, authRepo = authRepo)

            val result = useCase("priv-1")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns Success with forked pattern and project`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(publicPattern)
            val projectRepo = FakeProjectRepository()
            val useCase = createUseCase(patternRepo = patternRepo, projectRepo = projectRepo, authRepo = authRepo)

            val result = useCase("pub-1")

            assertIs<UseCaseResult.Success<ForkedProject>>(result)
            val forked = result.value

            // Forked pattern has new ID and belongs to current user
            assertNotEquals("pub-1", forked.pattern.id)
            assertEquals("user-1", forked.pattern.ownerId)
            assertEquals(Visibility.PRIVATE, forked.pattern.visibility)

            // Content is preserved
            assertEquals("Cable Knit Sweater", forked.pattern.title)
            assertEquals("A cozy cable knit pattern", forked.pattern.description)
            assertEquals(Difficulty.INTERMEDIATE, forked.pattern.difficulty)
            assertEquals("20 stitches / 4 inches", forked.pattern.gauge)
            assertEquals(listOf("https://example.com/chart.png"), forked.pattern.chartImageUrls)

            // Project is created linked to forked pattern
            assertEquals(forked.pattern.id, forked.project.patternId)
            assertEquals("user-1", forked.project.ownerId)
            assertEquals(ProjectStatus.NOT_STARTED, forked.project.status)
            assertEquals(0, forked.project.currentRow)
        }

    @Test
    fun `forked pattern has different timestamps than source`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(publicPattern)
            val useCase = createUseCase(patternRepo = patternRepo, authRepo = authRepo)

            val result = useCase("pub-1")

            assertIs<UseCaseResult.Success<ForkedProject>>(result)
            assertNotEquals(publicPattern.createdAt, result.value.pattern.createdAt)
        }

    // Phase 36.3 (ADR-012 §1, §3): closes the 36.1 anchor comment by writing
    // `parentPatternId = sourcePattern.id` on the cloned envelope.
    @Test
    fun `forked pattern parentPatternId equals source pattern id`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(publicPattern)
            val useCase = createUseCase(patternRepo = patternRepo, authRepo = authRepo)

            val result = useCase("pub-1")

            assertIs<UseCaseResult.Success<ForkedProject>>(result)
            assertEquals("pub-1", result.value.pattern.parentPatternId)
            assertNull(publicPattern.parentPatternId, "source pattern is not itself a fork — sanity")
        }

    // Phase 36.3 (ADR-012 §3 happy path): source has chart → fork succeeds with
    // `chartCloned == true` and the chart-clone error slot stays null.
    @Test
    fun `fork succeeds with chartCloned true when source has structured chart`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(publicPattern)
            val chartRepo = FakeStructuredChartRepository()
            chartRepo.seed(chartFor("pub-1"))
            val useCase = createUseCase(patternRepo = patternRepo, chartRepo = chartRepo, authRepo = authRepo)

            val result = useCase("pub-1")

            assertIs<UseCaseResult.Success<ForkedProject>>(result)
            assertTrue(result.value.chartCloned)
            assertNull(result.value.chartCloneError)
            // Cloned chart actually landed in the repo under the new pattern id.
            val cloned = chartRepo.getByPatternId(result.value.pattern.id)
            assertNotNull(cloned, "cloned chart must be retrievable by the new pattern id")
            assertEquals("rev-source-1", cloned.parentRevisionId, "lineage parent_revision_id chains to source")
        }

    // Phase 36.3 (ADR-012 §3): source has no chart → fork still succeeds.
    // `chartCloned == false` here means "nothing to clone", NOT a failure;
    // the error slot stays null. The Discovery snackbar copy must distinguish
    // this case from the failure case via the error slot, not the bool alone.
    @Test
    fun `fork succeeds with chartCloned false when source has no structured chart`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(publicPattern)
            val chartRepo = FakeStructuredChartRepository() // no chart seeded
            val useCase = createUseCase(patternRepo = patternRepo, chartRepo = chartRepo, authRepo = authRepo)

            val result = useCase("pub-1")

            assertIs<UseCaseResult.Success<ForkedProject>>(result)
            assertFalse(result.value.chartCloned)
            assertNull(result.value.chartCloneError)
        }

    // Phase 36.3 (ADR-012 §7) best-effort semantics: chart-clone throws → the
    // pattern + project still land, and the error is surfaced via
    // `chartCloneError` so the UX layer can show a fallback snackbar.
    @Test
    fun `fork still succeeds when chart clone throws and surfaces error`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(publicPattern)
            val projectRepo = FakeProjectRepository()
            val chartRepo = FakeStructuredChartRepository()
            chartRepo.seed(chartFor("pub-1"))
            chartRepo.failNext = RuntimeException("transient storage error")
            val useCase =
                createUseCase(
                    patternRepo = patternRepo,
                    projectRepo = projectRepo,
                    chartRepo = chartRepo,
                    authRepo = authRepo,
                )

            val result = useCase("pub-1")

            assertIs<UseCaseResult.Success<ForkedProject>>(result)
            assertFalse(result.value.chartCloned, "chart clone threw → flag must be false")
            assertNotNull(result.value.chartCloneError, "thrown exception must be surfaced")
            // The pattern and project both landed in their respective repos —
            // chart-clone failure does NOT roll back upstream writes.
            assertEquals(result.value.pattern, patternRepo.getById(result.value.pattern.id))
            assertEquals(result.value.project, projectRepo.getById(result.value.project.id))
        }

    // Phase 36.3 (ADR-012 §3): the Project row is the user's actual workspace —
    // verify it landed in the project repo (separate from the success-path test
    // which only asserts the in-memory ForkedProject envelope).
    @Test
    fun `fork creates project row in project repository`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(publicPattern)
            val projectRepo = FakeProjectRepository()
            val useCase = createUseCase(patternRepo = patternRepo, projectRepo = projectRepo, authRepo = authRepo)

            val result = useCase("pub-1")

            assertIs<UseCaseResult.Success<ForkedProject>>(result)
            val byOwner = projectRepo.getByOwnerId("user-1")
            assertEquals(1, byOwner.size, "exactly one project per fork")
            assertEquals(result.value.project.id, byOwner.single().id)
        }
}
