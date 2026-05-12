package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.local.LocalPatternDataSource
import io.github.b150005.skeinly.data.local.LocalSuggestionDataSource
import io.github.b150005.skeinly.data.sync.FakeSyncManager
import io.github.b150005.skeinly.data.sync.SyncEntityType
import io.github.b150005.skeinly.data.sync.SyncOperation
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.db.createTestDriver
import io.github.b150005.skeinly.domain.model.Difficulty
import io.github.b150005.skeinly.domain.model.Pattern
import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionComment
import io.github.b150005.skeinly.domain.model.SuggestionStatus
import io.github.b150005.skeinly.domain.model.Visibility
import io.github.b150005.skeinly.testJson
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

class SuggestionRepositoryImplTest {
    private lateinit var db: SkeinlyDatabase
    private lateinit var local: LocalSuggestionDataSource
    private lateinit var localPattern: LocalPatternDataSource
    private lateinit var repository: SuggestionRepositoryImpl
    private lateinit var fakeSyncManager: FakeSyncManager
    private val isOnline = MutableStateFlow(false)

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        db = SkeinlyDatabase(driver)
        local = LocalSuggestionDataSource(db, Dispatchers.Unconfined)
        localPattern = LocalPatternDataSource(db, Dispatchers.Unconfined)
        fakeSyncManager = FakeSyncManager()
        repository =
            SuggestionRepositoryImpl(
                local = local,
                remote = null,
                isOnline = isOnline,
                syncManager = fakeSyncManager,
                json = testJson,
            )
    }

    private fun testPattern(
        id: String = "pat-1",
        ownerId: String = "user-1",
    ): Pattern =
        Pattern(
            id = id,
            ownerId = ownerId,
            title = "Test pattern",
            description = null,
            difficulty = Difficulty.BEGINNER,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = Visibility.PRIVATE,
            createdAt = Instant.parse("2026-04-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-04-01T00:00:00Z"),
        )

    private fun testPr(
        id: String = "pr-1",
        sourcePatternId: String = "pat-fork",
        targetPatternId: String = "pat-upstream",
        authorId: String? = "user-fork",
        title: String = "Reworked the cuff",
        description: String? = null,
        status: SuggestionStatus = SuggestionStatus.OPEN,
        createdAtIso: String = "2026-04-25T10:00:00Z",
        appliedVersionId: String? = null,
        mergedAtIso: String? = null,
        closedAtIso: String? = null,
    ): Suggestion =
        Suggestion(
            id = id,
            sourcePatternId = sourcePatternId,
            sourceBranchId = "branch-fork-main",
            sourceTipRevisionId = "rev-source-tip",
            targetPatternId = targetPatternId,
            targetBranchId = "branch-upstream-main",
            commonAncestorRevisionId = "rev-ancestor",
            authorId = authorId,
            title = title,
            description = description,
            status = status,
            appliedVersionId = appliedVersionId,
            appliedAt = mergedAtIso?.let { Instant.parse(it) },
            closedAt = closedAtIso?.let { Instant.parse(it) },
            createdAt = Instant.parse(createdAtIso),
            updatedAt = Instant.parse(createdAtIso),
        )

    private fun testComment(
        id: String = "cmt-1",
        suggestionId: String = "pr-1",
        authorId: String? = "user-1",
        body: String = "Looks great",
        createdAtIso: String = "2026-04-25T11:00:00Z",
    ): SuggestionComment =
        SuggestionComment(
            id = id,
            suggestionId = suggestionId,
            authorId = authorId,
            body = body,
            createdAt = Instant.parse(createdAtIso),
        )

    // ---- openSuggestion ----

    @Test
    fun `openSuggestion persists locally and enqueues INSERT sync`() =
        runTest {
            val pr = testPr()
            val returned = repository.openSuggestion(pr)

            assertEquals(pr, returned)
            assertEquals(pr, repository.getById(pr.id))

            assertEquals(1, fakeSyncManager.calls.size)
            val call = fakeSyncManager.calls.first()
            assertEquals(SyncEntityType.PULL_REQUEST, call.entityType)
            assertEquals(pr.id, call.entityId)
            assertEquals(SyncOperation.INSERT, call.operation)
            assertTrue(call.payload.isNotEmpty())
        }

    @Test
    fun `openSuggestion preserves description and authorId round-trip`() =
        runTest {
            val pr = testPr(description = "I rewrote rows 12-20 to use jis.k1 instead of jis.p1")
            repository.openSuggestion(pr)

            val retrieved = repository.getById(pr.id)
            assertNotNull(retrieved)
            assertEquals("I rewrote rows 12-20 to use jis.k1 instead of jis.p1", retrieved.description)
            assertEquals("user-fork", retrieved.authorId)
        }

    @Test
    fun `openSuggestion preserves null description for blank PR opens`() =
        runTest {
            val pr = testPr(description = null)
            repository.openSuggestion(pr)

            val retrieved = repository.getById(pr.id)
            assertNotNull(retrieved)
            assertNull(retrieved.description)
        }

    // ---- closeSuggestion ----

    @Test
    fun `closeSuggestion flips status to CLOSED and stamps closedAt`() =
        runTest {
            val open = testPr()
            repository.openSuggestion(open)

            val closed = repository.closeSuggestion(open)

            assertEquals(SuggestionStatus.CLOSED, closed.status)
            assertNotNull(closed.closedAt)
            // updatedAt advances on close — must not equal the createdAt of the
            // original row (closedAt and updatedAt both stamped at close time).
            assertTrue(closed.updatedAt >= open.updatedAt)
        }

    @Test
    fun `closeSuggestion enqueues an UPDATE sync entry`() =
        runTest {
            val open = testPr()
            repository.openSuggestion(open)
            // First call is the INSERT from openSuggestion above
            assertEquals(1, fakeSyncManager.calls.size)

            repository.closeSuggestion(open)

            assertEquals(2, fakeSyncManager.calls.size)
            val closeCall = fakeSyncManager.calls.last()
            assertEquals(SyncEntityType.PULL_REQUEST, closeCall.entityType)
            assertEquals(open.id, closeCall.entityId)
            assertEquals(SyncOperation.UPDATE, closeCall.operation)
        }

    @Test
    fun `closeSuggestion preserves caller-supplied closedAt when present`() =
        runTest {
            val open = testPr()
            repository.openSuggestion(open)
            val explicitCloseTime = Instant.parse("2026-04-26T08:00:00Z")

            val closed = repository.closeSuggestion(open.copy(closedAt = explicitCloseTime))

            assertEquals(explicitCloseTime, closed.closedAt)
        }

    // ---- getById remote-fallback ----

    @Test
    fun `getById returns null when PR is not found locally`() =
        runTest {
            assertNull(repository.getById("pr-nonexistent"))
        }

    @Test
    fun `getById is case-sensitive on id`() =
        runTest {
            repository.openSuggestion(testPr(id = "pr-CaseSensitive"))
            assertNull(repository.getById("PR-CASESENSITIVE"))
            assertNotNull(repository.getById("pr-CaseSensitive"))
        }

    // ---- incoming / outgoing scoping ----

    @Test
    fun `getIncomingForOwner returns PRs targeting patterns the owner owns`() =
        runTest {
            // Seed pattern owned by upstream-owner.
            localPattern.upsert(testPattern(id = "pat-upstream", ownerId = "upstream-owner"))
            // Seed PR targeting the upstream pattern.
            repository.openSuggestion(
                testPr(id = "pr-incoming", targetPatternId = "pat-upstream", authorId = "fork-author"),
            )
            // Seed unrelated PR targeting a different upstream.
            localPattern.upsert(testPattern(id = "pat-other", ownerId = "stranger"))
            repository.openSuggestion(
                testPr(id = "pr-other", targetPatternId = "pat-other", authorId = "fork-author"),
            )

            val incoming = repository.getIncomingForOwner("upstream-owner")

            assertEquals(listOf("pr-incoming"), incoming.map { it.id })
        }

    @Test
    fun `getOutgoingForOwner returns PRs the owner authored regardless of target`() =
        runTest {
            repository.openSuggestion(testPr(id = "pr-1", authorId = "fork-author"))
            repository.openSuggestion(testPr(id = "pr-2", authorId = "fork-author", createdAtIso = "2026-04-26T10:00:00Z"))
            repository.openSuggestion(testPr(id = "pr-3", authorId = "stranger"))

            val outgoing = repository.getOutgoingForOwner("fork-author")

            assertEquals(setOf("pr-1", "pr-2"), outgoing.map { it.id }.toSet())
        }

    @Test
    fun `observeIncomingForOwner emits current snapshot from local`() =
        runTest {
            localPattern.upsert(testPattern(id = "pat-upstream", ownerId = "upstream-owner"))
            repository.openSuggestion(testPr(id = "pr-1", targetPatternId = "pat-upstream"))
            repository.openSuggestion(
                testPr(
                    id = "pr-2",
                    targetPatternId = "pat-upstream",
                    createdAtIso = "2026-04-26T10:00:00Z",
                ),
            )

            val snapshot = repository.observeIncomingForOwner("upstream-owner").first()

            // newest-first per the SQLDelight query ORDER BY created_at DESC
            assertEquals(listOf("pr-2", "pr-1"), snapshot.map { it.id })
        }

    // ---- comments ----

    @Test
    fun `postComment persists locally and enqueues INSERT sync`() =
        runTest {
            val comment = testComment()
            val returned = repository.postComment(comment)

            assertEquals(comment, returned)
            val cached = repository.getCommentsForSuggestion(comment.suggestionId)
            assertEquals(1, cached.size)
            assertEquals(comment, cached.first())

            assertEquals(1, fakeSyncManager.calls.size)
            val call = fakeSyncManager.calls.first()
            assertEquals(SyncEntityType.PULL_REQUEST_COMMENT, call.entityType)
            assertEquals(comment.id, call.entityId)
            assertEquals(SyncOperation.INSERT, call.operation)
        }

    @Test
    fun `getCommentsForSuggestion scopes results to the requested suggestionId and orders ascending`() =
        runTest {
            repository.postComment(testComment(id = "c1", suggestionId = "pr-1", createdAtIso = "2026-04-25T11:00:00Z"))
            repository.postComment(testComment(id = "c2", suggestionId = "pr-1", createdAtIso = "2026-04-25T12:00:00Z"))
            repository.postComment(testComment(id = "c3", suggestionId = "pr-2", createdAtIso = "2026-04-25T11:30:00Z"))

            val pr1Comments = repository.getCommentsForSuggestion("pr-1")
            val pr2Comments = repository.getCommentsForSuggestion("pr-2")

            assertEquals(listOf("c1", "c2"), pr1Comments.map { it.id })
            assertEquals(listOf("c3"), pr2Comments.map { it.id })
        }

    @Test
    fun `comment INSERT-OR-IGNORE upsert is idempotent on Realtime echo`() =
        runTest {
            val comment = testComment()
            repository.postComment(comment)
            // Simulate a Realtime backfill arriving with the same comment id
            // after the local insert already landed. INSERT OR IGNORE makes
            // this a silent no-op rather than overwriting a slightly-different
            // copy or surfacing a constraint violation.
            local.upsertComment(comment)

            assertEquals(1, local.countCommentsForSuggestion(comment.suggestionId))
        }

    // ---- merged-PR round-trip via local upsert (Realtime echo simulation) ----

    @Test
    fun `merged PR round-trip preserves appliedVersionId and appliedAt`() =
        runTest {
            val appliedAt = "2026-04-26T15:30:00Z"
            // Simulate a Realtime echo of an apply transaction that landed
            // server-side via the apply_suggestion RPC. The local upsert
            // path is the only consumer of APPLIED rows in Phase 38.1 —
            // there is no client-side write path to APPLIED.
            val merged =
                testPr(
                    status = SuggestionStatus.APPLIED,
                    appliedVersionId = "rev-merged",
                    mergedAtIso = appliedAt,
                )
            local.upsert(merged)

            val retrieved = repository.getById(merged.id)
            assertNotNull(retrieved)
            assertEquals(SuggestionStatus.APPLIED, retrieved.status)
            assertEquals("rev-merged", retrieved.appliedVersionId)
            assertEquals(Instant.parse(appliedAt), retrieved.appliedAt)
        }
}
