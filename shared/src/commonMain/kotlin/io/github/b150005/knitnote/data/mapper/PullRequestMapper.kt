package io.github.b150005.knitnote.data.mapper

import io.github.b150005.knitnote.db.PullRequestCommentEntity
import io.github.b150005.knitnote.db.PullRequestEntity
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestComment
import io.github.b150005.knitnote.domain.model.PullRequestStatus
import kotlin.time.Instant

internal fun PullRequestEntity.toDomain(): PullRequest =
    PullRequest(
        id = id,
        sourcePatternId = source_pattern_id,
        sourceBranchId = source_branch_id,
        sourceTipRevisionId = source_tip_revision_id,
        targetPatternId = target_pattern_id,
        targetBranchId = target_branch_id,
        commonAncestorRevisionId = common_ancestor_revision_id,
        authorId = author_id,
        title = title,
        description = description,
        status = status.toPullRequestStatus(),
        mergedRevisionId = merged_revision_id,
        mergedAt = merged_at?.let { Instant.parse(it) },
        closedAt = closed_at?.let { Instant.parse(it) },
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )

internal fun PullRequestCommentEntity.toDomain(): PullRequestComment =
    PullRequestComment(
        id = id,
        pullRequestId = pull_request_id,
        authorId = author_id,
        body = body,
        createdAt = Instant.parse(created_at),
    )

internal fun String.toPullRequestStatus(): PullRequestStatus =
    when (this) {
        "open" -> PullRequestStatus.OPEN
        "merged" -> PullRequestStatus.MERGED
        "closed" -> PullRequestStatus.CLOSED
        else -> error("Unknown PullRequestStatus wire value: '$this'")
    }

internal fun PullRequestStatus.toDbString(): String =
    when (this) {
        PullRequestStatus.OPEN -> "open"
        PullRequestStatus.MERGED -> "merged"
        PullRequestStatus.CLOSED -> "closed"
    }
