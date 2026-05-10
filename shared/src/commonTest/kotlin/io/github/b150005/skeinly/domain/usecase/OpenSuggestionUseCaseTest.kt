package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartVersion
import io.github.b150005.skeinly.domain.model.CoordinateSystem
import io.github.b150005.skeinly.domain.model.CraftType
import io.github.b150005.skeinly.domain.model.ReadingConvention
import io.github.b150005.skeinly.domain.model.StorageVariant
import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.repository.ChartVersionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant

class OpenSuggestionUseCaseTest {
    private lateinit var prRepo: FakeSuggestionRepository
    private lateinit var revRepo: FakeRevisionRepoForOpenPr
    private lateinit var authRepo: FakeAuthRepository
    private lateinit var useCase: OpenSuggestionUseCase

    @BeforeTest
    fun setUp() {
        prRepo = FakeSuggestionRepository()
        revRepo = FakeRevisionRepoForOpenPr()
        authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated(userId = "user-fork", email = "f@example.com"))
        useCase = OpenSuggestionUseCase(prRepo, revRepo, authRepo)
    }

    private fun makeRevision(
        id: String,
        patternId: String,
        parentId: String?,
    ) = ChartVersion(
        id = "$id-row",
        revisionId = id,
        patternId = patternId,
        ownerId = "owner-x",
        authorId = "user-fork",
        schemaVersion = 2,
        storageVariant = StorageVariant.INLINE,
        coordinateSystem = CoordinateSystem.RECT_GRID,
        extents = ChartExtents.Rect(minX = 0, maxX = 7, minY = 0, maxY = 7),
        layers = emptyList(),
        contentHash = "hash-$id",
        parentRevisionId = parentId,
        commitMessage = null,
        craftType = CraftType.KNIT,
        readingConvention = ReadingConvention.KNIT_FLAT,
        createdAt = Instant.parse("2026-04-25T10:00:00Z"),
    )

    @Test
    fun `invoke writes a PR pointing the common ancestor at the fork point when source has no commits since fork`() =
        runTest {
            // Target has revs [t1, t2, t3]; source forked from t2 and made no
            // edits — sourceTip == t2. The walk should resolve t2 immediately.
            revRepo.setHistory(
                "pat-upstream",
                listOf(
                    makeRevision("t1", "pat-upstream", null),
                    makeRevision("t2", "pat-upstream", "t1"),
                    makeRevision("t3", "pat-upstream", "t2"),
                ),
            )
            revRepo.put(makeRevision("t2", "pat-fork", "t1"))

            val result =
                useCase(
                    sourcePatternId = "pat-fork",
                    sourceBranchId = "br-fork-main",
                    sourceTipRevisionId = "t2",
                    targetPatternId = "pat-upstream",
                    targetBranchId = "br-upstream-main",
                    title = "First suggestion",
                    description = null,
                )

            assertIs<UseCaseResult.Success<Suggestion>>(result)
            assertEquals("t2", result.value.commonAncestorRevisionId)
            assertEquals("user-fork", result.value.authorId)
        }

    @Test
    fun `invoke walks back through the source chain until it finds an ancestor that exists on target`() =
        runTest {
            // Target history contains t1, t2; source forked from t2 then added f1, f2.
            revRepo.setHistory(
                "pat-upstream",
                listOf(makeRevision("t1", "pat-upstream", null), makeRevision("t2", "pat-upstream", "t1")),
            )
            // Source-side chain: f2 → f1 → t2.
            revRepo.put(makeRevision("f2", "pat-fork", "f1"))
            revRepo.put(makeRevision("f1", "pat-fork", "t2"))

            val result =
                useCase(
                    sourcePatternId = "pat-fork",
                    sourceBranchId = "br-fork-main",
                    sourceTipRevisionId = "f2",
                    targetPatternId = "pat-upstream",
                    targetBranchId = "br-upstream-main",
                    title = "Second suggestion",
                    description = "with notes",
                )

            assertIs<UseCaseResult.Success<Suggestion>>(result)
            assertEquals("t2", result.value.commonAncestorRevisionId)
            assertEquals("with notes", result.value.description)
        }

    @Test
    fun `invoke fails OperationNotAllowed when no ancestor is found`() =
        runTest {
            // Target history has only t-other; source chain has f1 → f0
            // — no overlap.
            revRepo.setHistory(
                "pat-upstream",
                listOf(makeRevision("t-other", "pat-upstream", null)),
            )
            revRepo.put(makeRevision("f1", "pat-fork", "f0"))
            revRepo.put(makeRevision("f0", "pat-fork", null))

            val result =
                useCase(
                    sourcePatternId = "pat-fork",
                    sourceBranchId = "br-fork-main",
                    sourceTipRevisionId = "f1",
                    targetPatternId = "pat-upstream",
                    targetBranchId = "br-upstream-main",
                    title = "Disjoint chain",
                    description = null,
                )

            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.OperationNotAllowed, result.error)
        }

    @Test
    fun `invoke fails OperationNotAllowed when target pattern has empty history`() =
        runTest {
            // Target has no rows at all — can't even compute an ancestor set.
            revRepo.put(makeRevision("f1", "pat-fork", null))

            val result =
                useCase(
                    sourcePatternId = "pat-fork",
                    sourceBranchId = "br-fork-main",
                    sourceTipRevisionId = "f1",
                    targetPatternId = "pat-upstream-empty",
                    targetBranchId = "br-upstream-main",
                    title = "Will fail",
                    description = null,
                )

            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.OperationNotAllowed, result.error)
        }

    @Test
    fun `invoke rejects an empty title`() =
        runTest {
            val result =
                useCase(
                    sourcePatternId = "pat-fork",
                    sourceBranchId = "br-fork-main",
                    sourceTipRevisionId = "rev-x",
                    targetPatternId = "pat-upstream",
                    targetBranchId = "br-upstream-main",
                    title = "   ",
                    description = null,
                )

            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.FieldRequired, result.error)
        }

    @Test
    fun `invoke rejects a title longer than the 200 char limit`() =
        runTest {
            val tooLong = "t".repeat(OpenSuggestionUseCase.MAX_TITLE_LENGTH + 1)

            val result =
                useCase(
                    sourcePatternId = "pat-fork",
                    sourceBranchId = "br-fork-main",
                    sourceTipRevisionId = "rev-x",
                    targetPatternId = "pat-upstream",
                    targetBranchId = "br-upstream-main",
                    title = tooLong,
                    description = null,
                )

            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.FieldTooLong, result.error)
        }

    @Test
    fun `invoke fails SignInRequired when no user is signed in`() =
        runTest {
            authRepo.setAuthState(AuthState.Unauthenticated)
            revRepo.setHistory(
                "pat-upstream",
                listOf(makeRevision("t1", "pat-upstream", null)),
            )
            revRepo.put(makeRevision("f1", "pat-fork", "t1"))

            val result =
                useCase(
                    sourcePatternId = "pat-fork",
                    sourceBranchId = "br-fork-main",
                    sourceTipRevisionId = "f1",
                    targetPatternId = "pat-upstream",
                    targetBranchId = "br-upstream-main",
                    title = "Anonymous attempt",
                    description = null,
                )

            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.SignInRequired, result.error)
        }

    @Test
    fun `invoke trims description and stores null for blank descriptions`() =
        runTest {
            revRepo.setHistory(
                "pat-upstream",
                listOf(makeRevision("t1", "pat-upstream", null)),
            )
            revRepo.put(makeRevision("f1", "pat-fork", "t1"))

            val withBlank =
                useCase(
                    sourcePatternId = "pat-fork",
                    sourceBranchId = "br-fork-main",
                    sourceTipRevisionId = "f1",
                    targetPatternId = "pat-upstream",
                    targetBranchId = "br-upstream-main",
                    title = "T",
                    description = "   ",
                )

            val pr = (withBlank as UseCaseResult.Success).value
            assertEquals(null, pr.description)
        }

    @Test
    fun `invoke records the PR through the repository write path`() =
        runTest {
            revRepo.setHistory(
                "pat-upstream",
                listOf(makeRevision("t1", "pat-upstream", null)),
            )
            revRepo.put(makeRevision("f1", "pat-fork", "t1"))

            useCase(
                sourcePatternId = "pat-fork",
                sourceBranchId = "br-fork-main",
                sourceTipRevisionId = "f1",
                targetPatternId = "pat-upstream",
                targetBranchId = "br-upstream-main",
                title = "Persisted",
                description = null,
            )

            assertNotNull(prRepo.lastOpened)
            assertEquals("Persisted", prRepo.lastOpened?.title)
        }
}

/**
 * Local fake — only `getRevision` and `getHistoryForPattern` are exercised by
 * `OpenSuggestionUseCase`. Other methods throw to surface accidental reach.
 */
private class FakeRevisionRepoForOpenPr : ChartVersionRepository {
    private val history = mutableMapOf<String, List<ChartVersion>>()
    private val byRevisionId = mutableMapOf<String, ChartVersion>()

    fun setHistory(
        patternId: String,
        revisions: List<ChartVersion>,
    ) {
        history[patternId] = revisions
        revisions.forEach { byRevisionId[it.revisionId] = it }
    }

    fun put(revision: ChartVersion) {
        byRevisionId[revision.revisionId] = revision
    }

    override suspend fun getRevision(revisionId: String): ChartVersion? = byRevisionId[revisionId]

    override suspend fun getHistoryForPattern(
        patternId: String,
        limit: Int,
        offset: Int,
    ): List<ChartVersion> = history[patternId].orEmpty()

    override fun observeHistoryForPattern(patternId: String): Flow<List<ChartVersion>> = flowOf(emptyList())

    override suspend fun append(revision: ChartVersion): ChartVersion = error("Not used by OpenSuggestionUseCase")
}
