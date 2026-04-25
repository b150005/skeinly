package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ChartBranch
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.ChartRevision
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.repository.ChartRevisionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Coverage matrix for the Phase 37.4 use cases (ADR-013 §6, §7):
 *
 * GetChartBranchesUseCase:
 *  1. observe surfaces seeded branches in alphabetical order
 *  2. invoke returns success on a populated repo
 *
 * CreateBranchUseCase:
 *  3. blank or whitespace branch name returns Validation
 *  4. reserved "main" name returns Validation case-insensitively
 *  5. duplicate branch name returns Validation
 *  6. missing chart returns NotFound
 *  7. happy path mints new branch with tip at current chart revision
 *
 * SwitchBranchUseCase:
 *  8. unknown branch returns NotFound
 *  9. branch tip revision missing returns NotFound
 * 10. happy path materializes target revision payload preserving tip row id
 *
 * RestoreRevisionUseCase:
 * 11. unknown revision returns NotFound
 * 12. happy path appends new commit with restored payload, parent = old tip
 */
class ChartBranchUseCasesTest {
    private val now = Instant.parse("2026-04-26T10:00:00Z")

    private fun chart(
        patternId: String = "pat-1",
        revisionId: String = "rev-a",
        layers: List<ChartLayer> = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
        contentHash: String = "h-a",
    ): StructuredChart =
        StructuredChart(
            id = "chart-row-1",
            patternId = patternId,
            ownerId = "user-1",
            schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = ChartExtents.Rect(0, 0, 4, 4),
            layers = layers,
            revisionId = revisionId,
            parentRevisionId = null,
            contentHash = contentHash,
            createdAt = now,
            updatedAt = now,
        )

    private fun branch(
        id: String = "branch-row-1",
        patternId: String = "pat-1",
        branchName: String = ChartBranch.DEFAULT_BRANCH_NAME,
        tipRevisionId: String = "rev-a",
    ): ChartBranch =
        ChartBranch(
            id = id,
            patternId = patternId,
            ownerId = "user-1",
            branchName = branchName,
            tipRevisionId = tipRevisionId,
            createdAt = now,
            updatedAt = now,
        )

    private fun revision(
        revisionId: String = "rev-a",
        patternId: String = "pat-1",
        layers: List<ChartLayer> = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 0, 0)))),
        contentHash: String = "h-restored",
    ): ChartRevision =
        ChartRevision(
            id = "rev-row-$revisionId",
            patternId = patternId,
            ownerId = "user-1",
            authorId = "user-1",
            schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = ChartExtents.Rect(0, 0, 4, 4),
            layers = layers,
            revisionId = revisionId,
            parentRevisionId = null,
            contentHash = contentHash,
            commitMessage = null,
            createdAt = now,
        )

    // ---- GetChartBranchesUseCase ----

    @Test
    fun `GetChartBranchesUseCase invoke returns Success with seeded branches`() =
        runTest {
            val branchRepo = FakeChartBranchRepository()
            branchRepo.seed(branch(branchName = "main"))
            branchRepo.seed(branch(id = "branch-row-2", branchName = "feature"))
            val useCase = GetChartBranchesUseCase(branchRepo)

            val result = useCase("pat-1")

            assertTrue(result is UseCaseResult.Success)
            assertEquals(2, result.value.size)
        }

    // ---- CreateBranchUseCase ----

    @Test
    fun `CreateBranchUseCase rejects blank branch name`() =
        runTest {
            val branchRepo = FakeChartBranchRepository()
            val chartRepo = FakeStructuredChartRepository().apply { seed(chart()) }
            val useCase = CreateBranchUseCase(branchRepo, chartRepo)

            val result = useCase("pat-1", "   ", ownerId = "user-1")

            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.Validation)
        }

    @Test
    fun `CreateBranchUseCase rejects reserved name main case-insensitively`() =
        runTest {
            val branchRepo = FakeChartBranchRepository()
            val chartRepo = FakeStructuredChartRepository().apply { seed(chart()) }
            val useCase = CreateBranchUseCase(branchRepo, chartRepo)

            val result = useCase("pat-1", "MAIN", ownerId = "user-1")

            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.Validation)
        }

    @Test
    fun `CreateBranchUseCase rejects duplicate branch name`() =
        runTest {
            val branchRepo =
                FakeChartBranchRepository().apply {
                    seed(branch(branchName = "feature"))
                }
            val chartRepo = FakeStructuredChartRepository().apply { seed(chart()) }
            val useCase = CreateBranchUseCase(branchRepo, chartRepo)

            val result = useCase("pat-1", "feature", ownerId = "user-1")

            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.Validation)
        }

    @Test
    fun `CreateBranchUseCase returns NotFound when chart is absent`() =
        runTest {
            val branchRepo = FakeChartBranchRepository()
            val chartRepo = FakeStructuredChartRepository()
            val useCase = CreateBranchUseCase(branchRepo, chartRepo)

            val result = useCase("pat-1", "feature", ownerId = "user-1")

            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.NotFound)
        }

    @Test
    fun `CreateBranchUseCase happy path mints branch tip at current chart revision`() =
        runTest {
            val branchRepo = FakeChartBranchRepository()
            val chartRepo = FakeStructuredChartRepository().apply { seed(chart(revisionId = "rev-current")) }
            val useCase = CreateBranchUseCase(branchRepo, chartRepo)

            val result = useCase("pat-1", " feature ", ownerId = "user-1")

            assertTrue(result is UseCaseResult.Success)
            assertEquals("feature", result.value.branchName)
            assertEquals("rev-current", result.value.tipRevisionId)
            assertEquals("user-1", result.value.ownerId)
            // Branch landed in the repo.
            assertEquals(1, branchRepo.getByPatternId("pat-1").size)
        }

    // ---- SwitchBranchUseCase ----

    @Test
    fun `SwitchBranchUseCase returns NotFound for unknown branch`() =
        runTest {
            val branchRepo = FakeChartBranchRepository()
            val revisionRepo = InMemoryRevisionRepo()
            val chartRepo = FakeStructuredChartRepository().apply { seed(chart()) }
            val useCase = SwitchBranchUseCase(branchRepo, revisionRepo, chartRepo)

            val result = useCase("pat-1", "feature")

            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.NotFound)
        }

    @Test
    fun `SwitchBranchUseCase returns NotFound when branch tip revision is missing`() =
        runTest {
            val branchRepo =
                FakeChartBranchRepository().apply {
                    seed(branch(branchName = "feature", tipRevisionId = "rev-dangling"))
                }
            val revisionRepo = InMemoryRevisionRepo()
            val chartRepo = FakeStructuredChartRepository().apply { seed(chart()) }
            val useCase = SwitchBranchUseCase(branchRepo, revisionRepo, chartRepo)

            val result = useCase("pat-1", "feature")

            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.NotFound)
        }

    @Test
    fun `SwitchBranchUseCase materializes target revision and preserves tip row id`() =
        runTest {
            val targetRev =
                revision(
                    revisionId = "rev-feature-tip",
                    layers = listOf(ChartLayer(id = "L1", name = "Cabled", cells = listOf(ChartCell("jis.cable", 1, 1)))),
                    contentHash = "h-feature",
                )
            val branchRepo =
                FakeChartBranchRepository().apply {
                    seed(branch(branchName = "feature", tipRevisionId = "rev-feature-tip"))
                }
            val revisionRepo = InMemoryRevisionRepo().apply { add(targetRev) }
            val current = chart(revisionId = "rev-main")
            val chartRepo = FakeStructuredChartRepository().apply { seed(current) }
            val useCase = SwitchBranchUseCase(branchRepo, revisionRepo, chartRepo)

            val result = useCase("pat-1", "feature")

            assertTrue(result is UseCaseResult.Success)
            // Tip row id preserved (rewrites in place).
            assertEquals(current.id, result.value.id)
            // Drawing payload now mirrors the target revision.
            assertEquals("rev-feature-tip", result.value.revisionId)
            assertEquals("h-feature", result.value.contentHash)
            assertEquals(
                "Cabled",
                result.value.layers
                    .first()
                    .name,
            )
        }

    @Test
    fun `SwitchBranchUseCase returns NotFound when chart row is absent`() =
        runTest {
            val targetRev = revision(revisionId = "rev-feature-tip")
            val branchRepo =
                FakeChartBranchRepository().apply {
                    seed(branch(branchName = "feature", tipRevisionId = "rev-feature-tip"))
                }
            val revisionRepo = InMemoryRevisionRepo().apply { add(targetRev) }
            // No chart seeded — setTip returns null.
            val chartRepo = FakeStructuredChartRepository()
            val useCase = SwitchBranchUseCase(branchRepo, revisionRepo, chartRepo)

            val result = useCase("pat-1", "feature")

            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.NotFound)
        }

    // ---- RestoreRevisionUseCase ----

    @Test
    fun `RestoreRevisionUseCase returns NotFound for unknown revision`() =
        runTest {
            val revisionRepo = InMemoryRevisionRepo()
            val chartRepo = FakeStructuredChartRepository().apply { seed(chart()) }
            val useCase = RestoreRevisionUseCase(revisionRepo, chartRepo)

            val result = useCase("pat-1", "rev-ghost")

            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.NotFound)
        }

    @Test
    fun `RestoreRevisionUseCase appends new commit with restored payload and old tip as parent`() =
        runTest {
            val oldRev =
                revision(
                    revisionId = "rev-old",
                    layers = listOf(ChartLayer(id = "L1", name = "Stockinette", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                    contentHash = "h-old",
                )
            val current = chart(revisionId = "rev-current")
            val revisionRepo = InMemoryRevisionRepo().apply { add(oldRev) }
            val chartRepo = FakeStructuredChartRepository().apply { seed(current) }
            val useCase = RestoreRevisionUseCase(revisionRepo, chartRepo)

            val result = useCase("pat-1", "rev-old")

            assertTrue(result is UseCaseResult.Success)
            val restored = result.value
            // New revision id minted (restoration is additive).
            assertNotNull(restored.revisionId)
            assertTrue(restored.revisionId != "rev-old")
            assertTrue(restored.revisionId != "rev-current")
            // Parent points at the prior tip — restoration is a forward commit.
            assertEquals("rev-current", restored.parentRevisionId)
            // Drawing payload mirrors the restored revision.
            assertEquals("h-old", restored.contentHash)
            assertEquals("Stockinette", restored.layers.first().name)
        }
}

/**
 * Minimal in-memory revision repo. Only the diff / restore / switch use cases
 * call `getRevision`; the other methods are unused and surface an `error()`
 * to fail loudly if a future refactor reaches them through this fake.
 */
private class InMemoryRevisionRepo : ChartRevisionRepository {
    private val store = mutableMapOf<String, ChartRevision>()

    fun add(revision: ChartRevision) {
        store[revision.revisionId] = revision
    }

    override suspend fun getRevision(revisionId: String): ChartRevision? = store[revisionId]

    override suspend fun getHistoryForPattern(
        patternId: String,
        limit: Int,
        offset: Int,
    ): List<ChartRevision> = error("Not used by branch / restore use cases")

    override fun observeHistoryForPattern(patternId: String): Flow<List<ChartRevision>> = flowOf(emptyList())

    override suspend fun append(revision: ChartRevision): ChartRevision = error("Not used by branch / restore use cases")
}
