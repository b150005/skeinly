package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.CellChange
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.ChartRevision
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.LayerChange
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.repository.ChartRevisionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Coverage matrix for [GetChartDiffUseCase] + [io.github.b150005.knitnote.domain.chart.ChartDiffAlgorithm]:
 *
 * Repository plumbing:
 *  1. target lookup miss → NotFound
 *  2. base lookup miss → NotFound (distinct from initial-commit case)
 *  3. base null + target present → success with isInitialCommit
 *
 * Algorithm — empty / no-change cases:
 *  4. identical revisions → no changes, hasNoChanges true
 *  5. base null → every target layer is LayerAdded, no cell-level enumeration
 *
 * Algorithm — cell changes within shared layers:
 *  6. cell added in same layer → CellAdded only
 *  7. cell removed in same layer → CellRemoved only
 *  8. cell modified in place (symbol_id swap) → CellModified
 *  9. parametric symbol parameter edit → CellModified (symbolParameters in equality)
 * 10. cell at a new (x, y) AND old (x, y) drained → add+remove (no "moved" pairing)
 *
 * Algorithm — layer-level:
 * 11. layer added → LayerAdded with no cell enumeration even when cells present
 * 12. layer removed → LayerRemoved with no cell enumeration
 * 13. layer renamed → LayerPropertyChanged, cells in-place compared separately
 * 14. visibility / locked toggled → LayerPropertyChanged
 *
 * Algorithm — coordinate system + multi-layer:
 * 15. polar revisions diff identically (cell.x = stitch, cell.y = ring)
 * 16. multi-layer with mixed change types in one diff
 */
class GetChartDiffUseCaseTest {
    private fun makeRevision(
        revisionId: String,
        layers: List<ChartLayer>,
        coordinateSystem: CoordinateSystem = CoordinateSystem.RECT_GRID,
        extents: ChartExtents = ChartExtents.Rect(minX = 0, maxX = 4, minY = 0, maxY = 4),
        parentRevisionId: String? = null,
    ): ChartRevision =
        ChartRevision(
            id = revisionId,
            patternId = "pat-1",
            ownerId = "user-1",
            authorId = "user-1",
            schemaVersion = 2,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = coordinateSystem,
            extents = extents,
            layers = layers,
            revisionId = revisionId,
            parentRevisionId = parentRevisionId,
            contentHash = "h1-$revisionId",
            commitMessage = null,
            createdAt = Instant.parse("2026-04-25T10:00:00Z"),
        )

    @Test
    fun `target lookup miss returns NotFound`() =
        runTest {
            val repo = FakeRepo()
            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = null, targetRevisionId = "missing")

            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.NotFound)
        }

    @Test
    fun `base lookup miss returns NotFound distinct from initial commit`() =
        runTest {
            val repo = FakeRepo()
            repo.add(makeRevision("target", layers = listOf(ChartLayer(id = "L1", name = "Main"))))
            val result =
                GetChartDiffUseCase(repo).invoke(baseRevisionId = "missing-base", targetRevisionId = "target")

            // Distinct from baseRevisionId == null case which would succeed with isInitialCommit.
            assertTrue(result is UseCaseResult.Failure)
            assertTrue(result.error is UseCaseError.NotFound)
        }

    @Test
    fun `null base produces initial commit diff with every layer added`() =
        runTest {
            val repo = FakeRepo()
            val target =
                makeRevision(
                    "target",
                    layers =
                        listOf(
                            ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0))),
                            ChartLayer(id = "L2", name = "Cable", cells = listOf(ChartCell("jis.k1", 1, 0))),
                        ),
                )
            repo.add(target)

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = null, targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            assertTrue(diff.isInitialCommit)
            assertNull(diff.base)
            assertEquals(2, diff.layerChanges.size)
            assertTrue(diff.layerChanges.all { it is LayerChange.Added })
            // Cells in added layers are NOT enumerated separately per ADR-013 §5.
            assertTrue(diff.cellChanges.isEmpty())
        }

    @Test
    fun `identical revisions produce empty diff with hasNoChanges true`() =
        runTest {
            val repo = FakeRepo()
            val layers =
                listOf(
                    ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0))),
                )
            repo.add(makeRevision("base", layers = layers))
            repo.add(makeRevision("target", layers = layers, parentRevisionId = "base"))

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            assertTrue(diff.hasNoChanges)
            assertEquals(0, diff.cellChanges.size)
            assertEquals(0, diff.layerChanges.size)
        }

    @Test
    fun `cell added in same layer surfaces CellAdded only`() =
        runTest {
            val repo = FakeRepo()
            repo.add(
                makeRevision(
                    "base",
                    layers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                ),
            )
            repo.add(
                makeRevision(
                    "target",
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                cells =
                                    listOf(
                                        ChartCell("jis.k1", 0, 0),
                                        ChartCell("jis.k1", 1, 0),
                                    ),
                            ),
                        ),
                ),
            )

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            assertEquals(1, diff.addedCellCount)
            assertEquals(0, diff.modifiedCellCount)
            assertEquals(0, diff.removedCellCount)
            val added = diff.cellChanges.first() as CellChange.Added
            assertEquals("L1", added.layerId)
            assertEquals(1, added.x)
            assertEquals(0, added.y)
        }

    @Test
    fun `cell removed in same layer surfaces CellRemoved only`() =
        runTest {
            val repo = FakeRepo()
            repo.add(
                makeRevision(
                    "base",
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                cells =
                                    listOf(
                                        ChartCell("jis.k1", 0, 0),
                                        ChartCell("jis.k1", 1, 0),
                                    ),
                            ),
                        ),
                ),
            )
            repo.add(
                makeRevision(
                    "target",
                    layers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                ),
            )

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            assertEquals(1, diff.removedCellCount)
            assertEquals(0, diff.addedCellCount)
            assertEquals(0, diff.modifiedCellCount)
        }

    @Test
    fun `symbol id swap in place surfaces CellModified`() =
        runTest {
            val repo = FakeRepo()
            repo.add(
                makeRevision(
                    "base",
                    layers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 2, 3)))),
                ),
            )
            repo.add(
                makeRevision(
                    "target",
                    layers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 2, 3)))),
                ),
            )

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            assertEquals(1, diff.modifiedCellCount)
            val mod = diff.cellChanges.first() as CellChange.Modified
            assertEquals("jis.k1", mod.before.symbolId)
            assertEquals("jis.p1", mod.after.symbolId)
            assertEquals(2, mod.x)
            assertEquals(3, mod.y)
        }

    @Test
    fun `parametric symbol parameter edit surfaces CellModified`() =
        runTest {
            val repo = FakeRepo()
            repo.add(
                makeRevision(
                    "base",
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                cells = listOf(ChartCell("jis.crochet.ch-space", 0, 0, symbolParameters = mapOf("n" to "3"))),
                            ),
                        ),
                ),
            )
            repo.add(
                makeRevision(
                    "target",
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                cells = listOf(ChartCell("jis.crochet.ch-space", 0, 0, symbolParameters = mapOf("n" to "5"))),
                            ),
                        ),
                ),
            )

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            // ChartCell.symbolParameters participates in data class equality, so a
            // parameter-only edit surfaces here even though symbolId is identical.
            assertEquals(1, diff.modifiedCellCount)
        }

    @Test
    fun `cell move surfaces as add and remove not as a moved category`() =
        runTest {
            val repo = FakeRepo()
            repo.add(
                makeRevision(
                    "base",
                    layers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                ),
            )
            repo.add(
                makeRevision(
                    "target",
                    layers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 1, 0)))),
                ),
            )

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            assertEquals(1, diff.addedCellCount)
            assertEquals(1, diff.removedCellCount)
            assertEquals(0, diff.modifiedCellCount)
        }

    @Test
    fun `layer added surfaces LayerAdded with no cell enumeration`() =
        runTest {
            val repo = FakeRepo()
            repo.add(
                makeRevision(
                    "base",
                    layers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                ),
            )
            repo.add(
                makeRevision(
                    "target",
                    layers =
                        listOf(
                            ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0))),
                            ChartLayer(
                                id = "L2",
                                name = "Cable",
                                cells = listOf(ChartCell("jis.k1", 0, 0), ChartCell("jis.k1", 1, 0)),
                            ),
                        ),
                ),
            )

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            assertEquals(1, diff.layerChanges.size)
            val added = diff.layerChanges.first() as LayerChange.Added
            assertEquals("L2", added.layer.id)
            // 2 cells exist in the new layer but the algorithm does NOT enumerate them.
            assertEquals(0, diff.cellChanges.size)
        }

    @Test
    fun `layer removed surfaces LayerRemoved with no cell enumeration`() =
        runTest {
            val repo = FakeRepo()
            repo.add(
                makeRevision(
                    "base",
                    layers =
                        listOf(
                            ChartLayer(id = "L1", name = "Main"),
                            ChartLayer(
                                id = "L2",
                                name = "Cable",
                                cells = listOf(ChartCell("jis.k1", 0, 0), ChartCell("jis.k1", 1, 0)),
                            ),
                        ),
                ),
            )
            repo.add(
                makeRevision(
                    "target",
                    layers = listOf(ChartLayer(id = "L1", name = "Main")),
                ),
            )

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            assertEquals(1, diff.layerChanges.size)
            assertTrue(diff.layerChanges.first() is LayerChange.Removed)
            assertEquals(0, diff.cellChanges.size)
        }

    @Test
    fun `layer rename surfaces LayerPropertyChanged and cells diff in place`() =
        runTest {
            val repo = FakeRepo()
            repo.add(
                makeRevision(
                    "base",
                    layers =
                        listOf(
                            ChartLayer(id = "L1", name = "Background", cells = listOf(ChartCell("jis.k1", 0, 0))),
                        ),
                ),
            )
            repo.add(
                makeRevision(
                    "target",
                    layers =
                        listOf(
                            ChartLayer(id = "L1", name = "Cable Panel", cells = listOf(ChartCell("jis.p1", 0, 0))),
                        ),
                ),
            )

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            // Layer id stable across rename → property change AND cell-level change in same layer.
            assertEquals(1, diff.layerChanges.size)
            val renamed = diff.layerChanges.first() as LayerChange.PropertyChanged
            assertEquals("Background", renamed.before.name)
            assertEquals("Cable Panel", renamed.after.name)
            assertEquals(1, diff.modifiedCellCount)
        }

    @Test
    fun `visibility and locked toggles surface as LayerPropertyChanged`() =
        runTest {
            val repo = FakeRepo()
            repo.add(
                makeRevision(
                    "base",
                    layers = listOf(ChartLayer(id = "L1", name = "Main", visible = true, locked = false)),
                ),
            )
            repo.add(
                makeRevision(
                    "target",
                    layers = listOf(ChartLayer(id = "L1", name = "Main", visible = false, locked = true)),
                ),
            )

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            assertEquals(1, diff.layerChanges.size)
            val changed = diff.layerChanges.first() as LayerChange.PropertyChanged
            assertEquals(true, changed.before.visible)
            assertEquals(false, changed.after.visible)
            assertEquals(false, changed.before.locked)
            assertEquals(true, changed.after.locked)
        }

    @Test
    fun `polar revisions diff identically with stitch ring keying`() =
        runTest {
            val repo = FakeRepo()
            val polarExtents = ChartExtents.Polar(rings = 4, stitchesPerRing = listOf(8, 8, 8, 8))
            repo.add(
                makeRevision(
                    "base",
                    coordinateSystem = CoordinateSystem.POLAR_ROUND,
                    extents = polarExtents,
                    layers =
                        listOf(
                            ChartLayer(id = "L1", name = "Round", cells = listOf(ChartCell("jis.crochet.dc", 0, 1))),
                        ),
                ),
            )
            repo.add(
                makeRevision(
                    "target",
                    coordinateSystem = CoordinateSystem.POLAR_ROUND,
                    extents = polarExtents,
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Round",
                                cells =
                                    listOf(
                                        ChartCell("jis.crochet.dc", 0, 1),
                                        ChartCell("jis.crochet.sc", 3, 2),
                                    ),
                            ),
                        ),
                ),
            )

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            // (x, y) keying = (stitch, ring) on polar — algorithm is the same shape.
            assertEquals(1, diff.addedCellCount)
            val added = diff.cellChanges.first() as CellChange.Added
            assertEquals(3, added.x)
            assertEquals(2, added.y)
        }

    @Test
    fun `multi layer mixed changes are all surfaced in one diff`() =
        runTest {
            val repo = FakeRepo()
            repo.add(
                makeRevision(
                    "base",
                    layers =
                        listOf(
                            ChartLayer(id = "L-stable", name = "Stable", cells = listOf(ChartCell("jis.k1", 0, 0))),
                            ChartLayer(id = "L-renamed", name = "Old name", cells = listOf(ChartCell("jis.k1", 1, 0))),
                            ChartLayer(id = "L-removed", name = "Gone", cells = listOf(ChartCell("jis.k1", 2, 0))),
                        ),
                ),
            )
            repo.add(
                makeRevision(
                    "target",
                    layers =
                        listOf(
                            // Stable: cell modified in place (symbol_id swap).
                            ChartLayer(id = "L-stable", name = "Stable", cells = listOf(ChartCell("jis.p1", 0, 0))),
                            // Renamed AND a new cell.
                            ChartLayer(
                                id = "L-renamed",
                                name = "New name",
                                cells = listOf(ChartCell("jis.k1", 1, 0), ChartCell("jis.k1", 1, 1)),
                            ),
                            // Layer "L-removed" gone; layer "L-added" new.
                            ChartLayer(id = "L-added", name = "Added", cells = listOf(ChartCell("jis.k1", 3, 3))),
                        ),
                ),
            )

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            // Layer-level: 1 added, 1 removed, 1 renamed → 3 LayerChanges.
            assertEquals(3, diff.layerChanges.size)
            assertEquals(1, diff.layerChanges.count { it is LayerChange.Added })
            assertEquals(1, diff.layerChanges.count { it is LayerChange.Removed })
            assertEquals(1, diff.layerChanges.count { it is LayerChange.PropertyChanged })

            // Cell-level inside shared layers only: stable CellModified (1) +
            // renamed CellAdded (1). Cells in the removed/added layers are NOT enumerated.
            assertEquals(1, diff.modifiedCellCount)
            assertEquals(1, diff.addedCellCount)
            assertEquals(0, diff.removedCellCount)
        }

    @Test
    fun `diff envelope carries base and target charts for renderer reuse`() =
        runTest {
            val repo = FakeRepo()
            repo.add(makeRevision("base", layers = listOf(ChartLayer(id = "L1", name = "Main"))))
            repo.add(makeRevision("target", layers = listOf(ChartLayer(id = "L1", name = "Main"))))

            val result = GetChartDiffUseCase(repo).invoke(baseRevisionId = "base", targetRevisionId = "target")
            assertTrue(result is UseCaseResult.Success)
            val diff = result.value

            val base = diff.base
            assertNotNull(base)
            assertEquals("base", base.revisionId)
            assertEquals("target", diff.target.revisionId)
        }
}

/**
 * In-memory fake. Diff use case only calls `getRevision` — the observe / append
 * paths surface as `error()` to fail loudly if a future refactor accidentally
 * reaches them through this fake.
 */
private class FakeRepo : ChartRevisionRepository {
    private val store = mutableMapOf<String, ChartRevision>()

    fun add(revision: ChartRevision) {
        store[revision.revisionId] = revision
    }

    override suspend fun getRevision(revisionId: String): ChartRevision? = store[revisionId]

    override suspend fun getHistoryForPattern(
        patternId: String,
        limit: Int,
        offset: Int,
    ): List<ChartRevision> = error("Not used by GetChartDiffUseCase")

    override fun observeHistoryForPattern(patternId: String): Flow<List<ChartRevision>> = flowOf(emptyList())

    override suspend fun append(revision: ChartRevision): ChartRevision = error("Not used by GetChartDiffUseCase")
}
