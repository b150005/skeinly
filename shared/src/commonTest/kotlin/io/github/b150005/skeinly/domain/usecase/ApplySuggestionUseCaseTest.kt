package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.ChartCell
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartLayer
import io.github.b150005.skeinly.domain.model.CoordinateSystem
import io.github.b150005.skeinly.domain.model.Pattern
import io.github.b150005.skeinly.domain.model.PullRequest
import io.github.b150005.skeinly.domain.model.PullRequestStatus
import io.github.b150005.skeinly.domain.model.StorageVariant
import io.github.b150005.skeinly.domain.model.StructuredChart
import io.github.b150005.skeinly.domain.model.Visibility
import io.github.b150005.skeinly.domain.repository.PullRequestMergeOperations
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Phase 38.4 (ADR-014 §5) coverage matrix for [MergePullRequestUseCase]:
 *
 *  1. status not OPEN → Validation
 *  2. unauthenticated → Authentication
 *  3. caller not target owner → Validation (defense-in-depth before RPC)
 *  4. mergeOperations null (offline-only) → Validation
 *  5. happy path → Success carrying merged revision id from the RPC return
 *  6. RPC error "Source tip drifted" → Validation
 *  7. RPC error "PR not open" → Validation
 *  8. RPC error "Caller is not target owner" → Authentication
 *  9. RPC error "PR not found" → NotFound
 * 10. generic RPC failure → Unknown via toUseCaseError
 * 11. fresh revision id minted per call (idempotent across retries)
 */
class MergePullRequestUseCaseTest {
    private val now = Instant.parse("2026-04-26T10:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }

    private fun openPr(): PullRequest =
        PullRequest(
            id = "pr-1",
            sourcePatternId = "pat-fork",
            sourceBranchId = "branch-source",
            sourceTipRevisionId = "rev-source-tip",
            targetPatternId = "pat-upstream",
            targetBranchId = "branch-target",
            commonAncestorRevisionId = "rev-ancestor",
            authorId = "contributor-id",
            title = "Add cable section",
            description = null,
            status = PullRequestStatus.OPEN,
            mergedRevisionId = null,
            mergedAt = null,
            closedAt = null,
            createdAt = now,
            updatedAt = now,
        )

    private fun targetPattern(ownerId: String): Pattern =
        Pattern(
            id = "pat-upstream",
            ownerId = ownerId,
            title = "Upstream pattern",
            description = null,
            difficulty = null,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = Visibility.PUBLIC,
            createdAt = now,
            updatedAt = now,
        )

    private fun resolvedChart(): StructuredChart =
        StructuredChart(
            id = "chart-1",
            patternId = "pat-upstream",
            ownerId = "owner-id",
            schemaVersion = 2,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = ChartExtents.Rect(minX = 0, maxX = 4, minY = 0, maxY = 4),
            layers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
            revisionId = "rev-mine-tip",
            parentRevisionId = "rev-ancestor",
            contentHash = "h1-foo",
            createdAt = now,
            updatedAt = now,
        )

    private class FakeMergeOps(
        var nextError: Throwable? = null,
    ) : PullRequestMergeOperations {
        var lastPrId: String? = null
            private set
        var lastStrategy: String? = null
            private set
        var lastRevisionId: String? = null
            private set
        var lastContentHash: String? = null
            private set
        var lastDocument: JsonElement? = null
            private set
        var callCount: Int = 0
            private set

        override suspend fun merge(
            pullRequestId: String,
            strategy: String,
            mergedDocument: JsonElement,
            mergedContentHash: String,
            resolvedRevisionId: String,
        ): String {
            callCount += 1
            lastPrId = pullRequestId
            lastStrategy = strategy
            lastRevisionId = resolvedRevisionId
            lastContentHash = mergedContentHash
            lastDocument = mergedDocument
            nextError?.let {
                nextError = null
                throw it
            }
            return resolvedRevisionId
        }
    }

    @Test
    fun `merging a non open pull request returns OperationNotAllowed`() =
        runTest {
            val auth =
                FakeAuthRepository().apply {
                    setAuthState(
                        io.github.b150005.skeinly.domain.model.AuthState
                            .Authenticated("owner-id", "u@x"),
                    )
                }
            val patterns = FakePatternRepository().apply { seed(targetPattern("owner-id")) }
            val ops = FakeMergeOps()
            val useCase = MergePullRequestUseCase(ops, patterns, auth, json)
            val merged = openPr().copy(status = PullRequestStatus.MERGED)

            val result = useCase(merged, resolvedChart())

            assertTrue(result is UseCaseResult.Failure)
            assertEquals(UseCaseError.OperationNotAllowed, result.error)
            assertEquals(0, ops.callCount)
        }

    @Test
    fun `merging while signed out returns SignInRequired`() =
        runTest {
            val auth = FakeAuthRepository() // unauthenticated by default
            val patterns = FakePatternRepository().apply { seed(targetPattern("owner-id")) }
            val ops = FakeMergeOps()
            val useCase = MergePullRequestUseCase(ops, patterns, auth, json)

            val result = useCase(openPr(), resolvedChart())

            assertTrue(result is UseCaseResult.Failure)
            assertEquals(UseCaseError.SignInRequired, result.error)
            assertEquals(0, ops.callCount)
        }

    @Test
    fun `caller not the target owner returns PermissionDenied before calling RPC`() =
        runTest {
            val auth =
                FakeAuthRepository().apply {
                    setAuthState(
                        io.github.b150005.skeinly.domain.model.AuthState
                            .Authenticated("not-owner", "u@x"),
                    )
                }
            val patterns = FakePatternRepository().apply { seed(targetPattern("owner-id")) }
            val ops = FakeMergeOps()
            val useCase = MergePullRequestUseCase(ops, patterns, auth, json)

            val result = useCase(openPr(), resolvedChart())

            assertTrue(result is UseCaseResult.Failure)
            assertEquals(UseCaseError.PermissionDenied, result.error)
            assertEquals(0, ops.callCount)
        }

    @Test
    fun `offline only mode with null mergeOperations returns RequiresConnectivity`() =
        runTest {
            val auth =
                FakeAuthRepository().apply {
                    setAuthState(
                        io.github.b150005.skeinly.domain.model.AuthState
                            .Authenticated("owner-id", "u@x"),
                    )
                }
            val patterns = FakePatternRepository().apply { seed(targetPattern("owner-id")) }
            val useCase = MergePullRequestUseCase(null, patterns, auth, json)

            val result = useCase(openPr(), resolvedChart())

            assertTrue(result is UseCaseResult.Failure)
            assertEquals(UseCaseError.RequiresConnectivity, result.error)
        }

    @Test
    fun `happy path returns Success carrying merged revision id`() =
        runTest {
            val auth =
                FakeAuthRepository().apply {
                    setAuthState(
                        io.github.b150005.skeinly.domain.model.AuthState
                            .Authenticated("owner-id", "u@x"),
                    )
                }
            val patterns = FakePatternRepository().apply { seed(targetPattern("owner-id")) }
            val ops = FakeMergeOps()
            val useCase = MergePullRequestUseCase(ops, patterns, auth, json)

            val result = useCase(openPr(), resolvedChart())

            assertTrue(result is UseCaseResult.Success)
            assertEquals("pr-1", result.value.pullRequestId)
            assertEquals("squash", ops.lastStrategy)
            assertEquals(ops.lastRevisionId, result.value.mergedRevisionId)
            assertEquals(1, ops.callCount)
        }

    @Test
    fun `Source tip drifted RPC error maps to OperationNotAllowed`() =
        runTest {
            val auth =
                FakeAuthRepository().apply {
                    setAuthState(
                        io.github.b150005.skeinly.domain.model.AuthState
                            .Authenticated("owner-id", "u@x"),
                    )
                }
            val patterns = FakePatternRepository().apply { seed(targetPattern("owner-id")) }
            val ops = FakeMergeOps(nextError = RuntimeException("Source tip drifted; re-resolve required"))
            val useCase = MergePullRequestUseCase(ops, patterns, auth, json)

            val result = useCase(openPr(), resolvedChart())

            assertTrue(result is UseCaseResult.Failure)
            assertEquals(UseCaseError.OperationNotAllowed, result.error)
        }

    @Test
    fun `PR not open RPC error maps to OperationNotAllowed`() =
        runTest {
            val auth =
                FakeAuthRepository().apply {
                    setAuthState(
                        io.github.b150005.skeinly.domain.model.AuthState
                            .Authenticated("owner-id", "u@x"),
                    )
                }
            val patterns = FakePatternRepository().apply { seed(targetPattern("owner-id")) }
            val ops = FakeMergeOps(nextError = RuntimeException("PR not open"))
            val useCase = MergePullRequestUseCase(ops, patterns, auth, json)

            val result = useCase(openPr(), resolvedChart())

            assertTrue(result is UseCaseResult.Failure)
            assertEquals(UseCaseError.OperationNotAllowed, result.error)
        }

    @Test
    fun `Caller is not target owner RPC error maps to PermissionDenied`() =
        runTest {
            val auth =
                FakeAuthRepository().apply {
                    setAuthState(
                        io.github.b150005.skeinly.domain.model.AuthState
                            .Authenticated("owner-id", "u@x"),
                    )
                }
            val patterns = FakePatternRepository().apply { seed(targetPattern("owner-id")) }
            val ops = FakeMergeOps(nextError = RuntimeException("Caller is not target owner"))
            val useCase = MergePullRequestUseCase(ops, patterns, auth, json)

            val result = useCase(openPr(), resolvedChart())

            assertTrue(result is UseCaseResult.Failure)
            assertEquals(UseCaseError.PermissionDenied, result.error)
        }

    @Test
    fun `PR not found RPC error maps to NotFound`() =
        runTest {
            val auth =
                FakeAuthRepository().apply {
                    setAuthState(
                        io.github.b150005.skeinly.domain.model.AuthState
                            .Authenticated("owner-id", "u@x"),
                    )
                }
            val patterns = FakePatternRepository().apply { seed(targetPattern("owner-id")) }
            val ops = FakeMergeOps(nextError = RuntimeException("PR not found"))
            val useCase = MergePullRequestUseCase(ops, patterns, auth, json)

            val result = useCase(openPr(), resolvedChart())

            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.ResourceNotFound)
        }

    @Test
    fun `generic RPC error wraps as Unknown via toUseCaseError`() =
        runTest {
            val auth =
                FakeAuthRepository().apply {
                    setAuthState(
                        io.github.b150005.skeinly.domain.model.AuthState
                            .Authenticated("owner-id", "u@x"),
                    )
                }
            val patterns = FakePatternRepository().apply { seed(targetPattern("owner-id")) }
            val ops = FakeMergeOps(nextError = RuntimeException("totally unrelated database error"))
            val useCase = MergePullRequestUseCase(ops, patterns, auth, json)

            val result = useCase(openPr(), resolvedChart())

            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.Unknown)
        }

    @Test
    fun `applyResolutions auto-clean preserves target-side autoFromMine edits`() =
        runTest {
            // Code review HIGH-1 regression: a clean merge where mine made
            // target-only edits AND theirs made source-only edits must
            // produce a document carrying BOTH sets of changes.
            val ancestor =
                StructuredChart(
                    id = "a",
                    patternId = "pat",
                    ownerId = "owner",
                    schemaVersion = 2,
                    storageVariant = StorageVariant.INLINE,
                    coordinateSystem = CoordinateSystem.RECT_GRID,
                    extents = ChartExtents.Rect(minX = 0, maxX = 4, minY = 0, maxY = 4),
                    layers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                    revisionId = "a",
                    parentRevisionId = null,
                    contentHash = "h-a",
                    createdAt = now,
                    updatedAt = now,
                )
            // Theirs adds (1, 0); mine adds (2, 0) — disjoint, both auto.
            val theirs =
                ancestor.copy(
                    revisionId = "t",
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                cells = listOf(ChartCell("jis.k1", 0, 0), ChartCell("jis.p1", 1, 0)),
                            ),
                        ),
                )
            val mine =
                ancestor.copy(
                    revisionId = "m",
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                cells = listOf(ChartCell("jis.k1", 0, 0), ChartCell("jis.yo", 2, 0)),
                            ),
                        ),
                )
            val report =
                io.github.b150005.skeinly.domain.chart.ConflictDetector.detect(
                    ancestor = ancestor,
                    theirs = theirs,
                    mine = mine,
                )
            assertTrue(report.isClean)

            val resolved =
                applyResolutions(
                    mine = mine,
                    autoFromTheirs = report.autoFromTheirs,
                    conflictPicks = emptyMap(),
                    autoLayerFromTheirs = report.autoLayerFromTheirs,
                    layerConflictPicks = emptyMap(),
                    theirs = theirs,
                    ancestor = ancestor,
                )
            // Resolved should carry: ancestor's (0,0) k1 + theirs' (1,0) p1 + mine's (2,0) yo.
            val cells =
                resolved.layers
                    .first { it.id == "L1" }
                    .cells
                    .associateBy { it.x to it.y }
            assertEquals("jis.k1", cells[0 to 0]?.symbolId)
            assertEquals("jis.p1", cells[1 to 0]?.symbolId)
            assertEquals("jis.yo", cells[2 to 0]?.symbolId)
        }

    @Test
    fun `applyResolutions SKIP on cell whose layer was auto-removed drops the cell`() =
        runTest {
            // Code review HIGH-2 regression: when a layer is auto-removed by
            // theirs and a cell-level conflict exists in that layer, the
            // SKIP picker option (restore ancestor) must NOT silently
            // recreate an orphan cell — the layer is gone, the cell is gone.
            val ancestor =
                StructuredChart(
                    id = "a",
                    patternId = "pat",
                    ownerId = "owner",
                    schemaVersion = 2,
                    storageVariant = StorageVariant.INLINE,
                    coordinateSystem = CoordinateSystem.RECT_GRID,
                    extents = ChartExtents.Rect(minX = 0, maxX = 4, minY = 0, maxY = 4),
                    layers =
                        listOf(
                            ChartLayer(id = "L1", name = "Main"),
                            ChartLayer(id = "L2", name = "Cable", cells = listOf(ChartCell("jis.k1", 0, 0))),
                        ),
                    revisionId = "a",
                    parentRevisionId = null,
                    contentHash = "h-a",
                    createdAt = now,
                    updatedAt = now,
                )
            // Theirs removes L2 entirely.
            val theirs =
                ancestor.copy(
                    revisionId = "t",
                    layers = listOf(ChartLayer(id = "L1", name = "Main")),
                )
            // Mine modifies the cell in L2.
            val mine =
                ancestor.copy(
                    revisionId = "m",
                    layers =
                        listOf(
                            ChartLayer(id = "L1", name = "Main"),
                            ChartLayer(id = "L2", name = "Cable", cells = listOf(ChartCell("jis.p1", 0, 0))),
                        ),
                )
            // Apply with a phantom SKIP pick on the L2 cell. The layer is
            // auto-removed by theirs; the SKIP must not bring the cell back.
            val resolved =
                applyResolutions(
                    mine = mine,
                    autoFromTheirs = emptyList(),
                    conflictPicks =
                        mapOf(
                            io.github.b150005.skeinly.domain.chart
                                .CellCoordinate("L2", 0, 0)
                                to ConflictResolution.SKIP,
                        ),
                    autoLayerFromTheirs =
                        listOf(
                            io.github.b150005.skeinly.domain.model.LayerChange.Removed(
                                ancestor.layers.first { it.id == "L2" },
                            ),
                        ),
                    layerConflictPicks = emptyMap(),
                    theirs = theirs,
                    ancestor = ancestor,
                )
            // L2 should be absent from the resolved layers — the layer-removal
            // is honored and the SKIP cell does not silently recreate the layer.
            assertTrue(resolved.layers.none { it.id == "L2" })
        }

    @Test
    fun `fresh revision id minted on every call`() =
        runTest {
            val auth =
                FakeAuthRepository().apply {
                    setAuthState(
                        io.github.b150005.skeinly.domain.model.AuthState
                            .Authenticated("owner-id", "u@x"),
                    )
                }
            val patterns = FakePatternRepository().apply { seed(targetPattern("owner-id")) }
            val ops = FakeMergeOps()
            val useCase = MergePullRequestUseCase(ops, patterns, auth, json)

            val r1 = useCase(openPr(), resolvedChart())
            val firstId = (r1 as UseCaseResult.Success).value.mergedRevisionId
            val r2 = useCase(openPr(), resolvedChart())
            val secondId = (r2 as UseCaseResult.Success).value.mergedRevisionId

            assertTrue(firstId != secondId, "Each merge should mint a distinct revision id (was '$firstId')")
            assertEquals(2, ops.callCount)
        }
}
