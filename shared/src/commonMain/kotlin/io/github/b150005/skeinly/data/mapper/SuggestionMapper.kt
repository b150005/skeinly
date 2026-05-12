package io.github.b150005.skeinly.data.mapper

import io.github.b150005.skeinly.db.SuggestionCommentEntity
import io.github.b150005.skeinly.db.SuggestionEntity
import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionComment
import io.github.b150005.skeinly.domain.model.SuggestionStatus
import kotlin.time.Instant

internal fun SuggestionEntity.toDomain(): Suggestion =
    Suggestion(
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
        status = status.toSuggestionStatus(),
        appliedVersionId = merged_revision_id,
        appliedAt = merged_at?.let { Instant.parse(it) },
        closedAt = closed_at?.let { Instant.parse(it) },
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )

internal fun SuggestionCommentEntity.toDomain(): SuggestionComment =
    SuggestionComment(
        id = id,
        suggestionId = pull_request_id,
        authorId = author_id,
        body = body,
        createdAt = Instant.parse(created_at),
    )

internal fun String.toSuggestionStatus(): SuggestionStatus =
    when (this) {
        "open" -> SuggestionStatus.OPEN
        "applied" -> SuggestionStatus.APPLIED
        "closed" -> SuggestionStatus.CLOSED
        else -> error("Unknown SuggestionStatus wire value: '$this'")
    }

internal fun SuggestionStatus.toDbString(): String =
    when (this) {
        SuggestionStatus.OPEN -> "open"
        SuggestionStatus.APPLIED -> "applied"
        SuggestionStatus.CLOSED -> "closed"
    }
