package io.github.b150005.knitnote.data.mapper

import io.github.b150005.knitnote.db.ProjectEntity
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import kotlin.time.Instant

fun ProjectEntity.toDomain(): Project =
    Project(
        id = id,
        ownerId = owner_id,
        patternId = pattern_id,
        title = title,
        status = status.toProjectStatus(),
        currentRow = current_row.toInt(),
        totalRows = total_rows?.toInt(),
        startedAt = started_at?.let { Instant.parse(it) },
        completedAt = completed_at?.let { Instant.parse(it) },
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )

private fun String.toProjectStatus(): ProjectStatus =
    when (this) {
        "not_started" -> ProjectStatus.NOT_STARTED
        "in_progress" -> ProjectStatus.IN_PROGRESS
        "completed" -> ProjectStatus.COMPLETED
        else -> throw IllegalStateException("Unknown ProjectStatus in database: '$this'")
    }

fun ProjectStatus.toDbString(): String =
    when (this) {
        ProjectStatus.NOT_STARTED -> "not_started"
        ProjectStatus.IN_PROGRESS -> "in_progress"
        ProjectStatus.COMPLETED -> "completed"
    }
