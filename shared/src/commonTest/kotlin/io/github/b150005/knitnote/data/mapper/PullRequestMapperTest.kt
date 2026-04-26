package io.github.b150005.knitnote.data.mapper

import io.github.b150005.knitnote.db.PullRequestEntity
import io.github.b150005.knitnote.domain.model.PullRequestStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class PullRequestMapperTest {
    private fun entity(
        status: String = "open",
        mergedRevisionId: String? = null,
        mergedAt: String? = null,
        closedAt: String? = null,
    ): PullRequestEntity =
        PullRequestEntity(
            id = "pr-1",
            source_pattern_id = "pat-fork",
            source_branch_id = "branch-fork",
            source_tip_revision_id = "rev-source",
            target_pattern_id = "pat-upstream",
            target_branch_id = "branch-upstream",
            common_ancestor_revision_id = "rev-ancestor",
            author_id = "user-fork",
            title = "Reworked rows 12-20",
            description = "details",
            status = status,
            merged_revision_id = mergedRevisionId,
            merged_at = mergedAt,
            closed_at = closedAt,
            created_at = "2026-04-25T10:00:00Z",
            updated_at = "2026-04-25T10:00:00Z",
        )

    @Test
    fun `entity with status=open round-trips to OPEN with null lifecycle timestamps`() {
        val pr = entity().toDomain()

        assertEquals(PullRequestStatus.OPEN, pr.status)
        assertNull(pr.mergedRevisionId)
        assertNull(pr.mergedAt)
        assertNull(pr.closedAt)
    }

    @Test
    fun `entity with status=merged round-trips with mergedRevisionId and mergedAt`() {
        val pr =
            entity(
                status = "merged",
                mergedRevisionId = "rev-merged",
                mergedAt = "2026-04-26T15:30:00Z",
            ).toDomain()

        assertEquals(PullRequestStatus.MERGED, pr.status)
        assertEquals("rev-merged", pr.mergedRevisionId)
        assertEquals(Instant.parse("2026-04-26T15:30:00Z"), pr.mergedAt)
        assertNull(pr.closedAt)
    }

    @Test
    fun `entity with status=closed round-trips with closedAt`() {
        val pr = entity(status = "closed", closedAt = "2026-04-26T16:00:00Z").toDomain()

        assertEquals(PullRequestStatus.CLOSED, pr.status)
        assertEquals(Instant.parse("2026-04-26T16:00:00Z"), pr.closedAt)
        assertNull(pr.mergedRevisionId)
    }

    @Test
    fun `unknown status string raises`() {
        // Defensive: prevents silent drift if a future migration adds a status
        // value the Kotlin enum doesn't yet know about — hard fail beats
        // silent fall-through to OPEN.
        assertFailsWith<IllegalStateException> {
            entity(status = "draft").toDomain()
        }
    }

    @Test
    fun `enum to db string round-trip is exhaustive`() {
        assertEquals("open", PullRequestStatus.OPEN.toDbString())
        assertEquals("merged", PullRequestStatus.MERGED.toDbString())
        assertEquals("closed", PullRequestStatus.CLOSED.toDbString())
    }

    @Test
    fun `canMerge returns true only when caller is target owner and PR is open`() {
        val open =
            entity().toDomain().copy(
                status = PullRequestStatus.OPEN,
                authorId = "fork-author",
            )

        assertEquals(true, open.canMerge(currentUserId = "upstream-owner", targetOwnerId = "upstream-owner"))
        // Author cannot merge their own PR.
        assertEquals(false, open.canMerge(currentUserId = "fork-author", targetOwnerId = "upstream-owner"))
        // Stranger cannot merge.
        assertEquals(false, open.canMerge(currentUserId = "stranger", targetOwnerId = "upstream-owner"))
        // Already-merged PR cannot be re-merged.
        val merged = open.copy(status = PullRequestStatus.MERGED)
        assertEquals(false, merged.canMerge(currentUserId = "upstream-owner", targetOwnerId = "upstream-owner"))
        // Already-closed PR cannot be merged.
        val closed = open.copy(status = PullRequestStatus.CLOSED)
        assertEquals(false, closed.canMerge(currentUserId = "upstream-owner", targetOwnerId = "upstream-owner"))
    }
}
