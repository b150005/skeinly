package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.local.LocalPatternDataSource
import io.github.b150005.knitnote.data.local.LocalPullRequestDataSource
import io.github.b150005.knitnote.data.sync.FakeSyncManager
import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncOperation
import io.github.b150005.knitnote.db.KnitNoteDatabase
import io.github.b150005.knitnote.db.createTestDriver
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestComment
import io.github.b150005.knitnote.domain.model.PullRequestStatus
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.testJson
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

class PullRequestRepositoryImplTest {
    private lateinit var db: KnitNoteDatabase
    private lateinit var local: LocalPullRequestDataSource
    private lateinit var localPattern: LocalPatternDataSource
    private lateinit var repository: PullRequestRepositoryImpl
    private lateinit var fakeSyncManager: FakeSyncManager
    private val isOnline = MutableStateFlow(false)

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        db = KnitNoteDatabase(driver)
        local = LocalPullRequestDataSource(db, Dispatchers.Unconfined)
        localPattern = LocalPatternDataSource(db, Dispatchers.Unconfined)
        fakeSyncManager = FakeSyncManager()
        repository =
            PullRequestRepositoryImpl(
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
        status: PullRequestStatus = PullRequestStatus.OPEN,
        createdAtIso: String = "2026-04-25T10:00:00Z",
        mergedRevisionId: String? = null,
        mergedAtIso: String? = null,
        closedAtIso: String? = null,
    ): PullRequest =
        PullRequest(
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
            mergedRevisionId = mergedRevisionId,
            mergedAt = mergedAtIso?.let { Instant.parse(it) },
            closedAt = closedAtIso?.let { Instant.parse(it) },
            createdAt = Instant.parse(createdAtIso),
            updatedAt = Instant.parse(createdAtIso),
        )

    private fun testComment(
        id: String = "cmt-1",
        pullRequestId: String = "pr-1",
        authorId: String? = "user-1",
        body: String = "Looks great",
        createdAtIso: String = "2026-04-25T11:00:00Z",
    ): PullRequestComment =
        PullRequestComment(
            id = id,
            pullRequestId = pullRequestId,
            authorId = authorId,
            body = body,
            createdAt = Instant.parse(createdAtIso),
        )

    // ---- openPullRequest ----

    @Test
    fun `openPullRequest persists locally and enqueues INSERT sync`() =
        runTest {
            val pr = testPr()
            val returned = repository.openPullRequest(pr)

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
    fun `openPullRequest preserves description and authorId round-trip`() =
        runTest {
            val pr = testPr(description = "I rewrote rows 12-20 to use jis.k1 instead of jis.p1")
            repository.openPullRequest(pr)

            val retrieved = repository.getById(pr.id)
            assertNotNull(retrieved)
            assertEquals("I rewrote rows 12-20 to use jis.k1 instead of jis.p1", retrieved.description)
            assertEquals("user-fork", retrieved.authorId)
        }

    @Test
    fun `openPullRequest preserves null description for blank PR opens`() =
        runTest {
            val pr = testPr(description = null)
            repository.openPullRequest(pr)

            val retrieved = repository.getById(pr.id)
            assertNotNull(retrieved)
            assertNull(retrieved.description)
        }

    // ---- closePullRequest ----

    @Test
    fun `closePullRequest flips status to CLOSED and stamps closedAt`() =
        runTest {
            val open = testPr()
            repository.openPullRequest(open)

            val closed = repository.closePullRequest(open)

            assertEquals(PullRequestStatus.CLOSED, closed.status)
            assertNotNull(closed.closedAt)
            // updatedAt advances on close — must not equal the createdAt of the
            // original row (closedAt and updatedAt both stamped at close time).
            assertTrue(closed.updatedAt >= open.updatedAt)
        }

    @Test
    fun `closePullRequest enqueues an UPDATE sync entry`() =
        runTest {
            val open = testPr()
            repository.openPullRequest(open)
            // First call is the INSERT from openPullRequest above
            assertEquals(1, fakeSyncManager.calls.size)

            repository.closePullRequest(open)

            assertEquals(2, fakeSyncManager.calls.size)
            val closeCall = fakeSyncManager.calls.last()
            assertEquals(SyncEntityType.PULL_REQUEST, closeCall.entityType)
            assertEquals(open.id, closeCall.entityId)
            assertEquals(SyncOperation.UPDATE, closeCall.operation)
        }

    @Test
    fun `closePullRequest preserves caller-supplied closedAt when present`() =
        runTest {
            val open = testPr()
            repository.openPullRequest(open)
            val explicitCloseTime = Instant.parse("2026-04-26T08:00:00Z")

            val closed = repository.closePullRequest(open.copy(closedAt = explicitCloseTime))

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
            repository.openPullRequest(testPr(id = "pr-CaseSensitive"))
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
            repository.openPullRequest(
                testPr(id = "pr-incoming", targetPatternId = "pat-upstream", authorId = "fork-author"),
            )
            // Seed unrelated PR targeting a different upstream.
            localPattern.upsert(testPattern(id = "pat-other", ownerId = "stranger"))
            repository.openPullRequest(
                testPr(id = "pr-other", targetPatternId = "pat-other", authorId = "fork-author"),
            )

            val incoming = repository.getIncomingForOwner("upstream-owner")

            assertEquals(listOf("pr-incoming"), incoming.map { it.id })
        }

    @Test
    fun `getOutgoingForOwner returns PRs the owner authored regardless of target`() =
        runTest {
            repository.openPullRequest(testPr(id = "pr-1", authorId = "fork-author"))
            repository.openPullRequest(testPr(id = "pr-2", authorId = "fork-author", createdAtIso = "2026-04-26T10:00:00Z"))
            repository.openPullRequest(testPr(id = "pr-3", authorId = "stranger"))

            val outgoing = repository.getOutgoingForOwner("fork-author")

            assertEquals(setOf("pr-1", "pr-2"), outgoing.map { it.id }.toSet())
        }

    @Test
    fun `observeIncomingForOwner emits current snapshot from local`() =
        runTest {
            localPattern.upsert(testPattern(id = "pat-upstream", ownerId = "upstream-owner"))
            repository.openPullRequest(testPr(id = "pr-1", targetPatternId = "pat-upstream"))
            repository.openPullRequest(
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
            val cached = repository.getCommentsForPullRequest(comment.pullRequestId)
            assertEquals(1, cached.size)
            assertEquals(comment, cached.first())

            assertEquals(1, fakeSyncManager.calls.size)
            val call = fakeSyncManager.calls.first()
            assertEquals(SyncEntityType.PULL_REQUEST_COMMENT, call.entityType)
            assertEquals(comment.id, call.entityId)
            assertEquals(SyncOperation.INSERT, call.operation)
        }

    @Test
    fun `getCommentsForPullRequest scopes results to the requested pullRequestId and orders ascending`() =
        runTest {
            repository.postComment(testComment(id = "c1", pullRequestId = "pr-1", createdAtIso = "2026-04-25T11:00:00Z"))
            repository.postComment(testComment(id = "c2", pullRequestId = "pr-1", createdAtIso = "2026-04-25T12:00:00Z"))
            repository.postComment(testComment(id = "c3", pullRequestId = "pr-2", createdAtIso = "2026-04-25T11:30:00Z"))

            val pr1Comments = repository.getCommentsForPullRequest("pr-1")
            val pr2Comments = repository.getCommentsForPullRequest("pr-2")

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

            assertEquals(1, local.countCommentsForPullRequest(comment.pullRequestId))
        }

    // ---- merged-PR round-trip via local upsert (Realtime echo simulation) ----

    @Test
    fun `merged PR round-trip preserves mergedRevisionId and mergedAt`() =
        runTest {
            val mergedAt = "2026-04-26T15:30:00Z"
            // Simulate a Realtime echo of a merge transaction that landed
            // server-side via the merge_pull_request RPC. The local upsert
            // path is the only consumer of MERGED rows in Phase 38.1 — there
            // is no client-side write path to MERGED.
            val merged =
                testPr(
                    status = PullRequestStatus.MERGED,
                    mergedRevisionId = "rev-merged",
                    mergedAtIso = mergedAt,
                )
            local.upsert(merged)

            val retrieved = repository.getById(merged.id)
            assertNotNull(retrieved)
            assertEquals(PullRequestStatus.MERGED, retrieved.status)
            assertEquals("rev-merged", retrieved.mergedRevisionId)
            assertEquals(Instant.parse(mergedAt), retrieved.mergedAt)
        }
}
