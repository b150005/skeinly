package io.github.b150005.skeinly.data.mapper

import io.github.b150005.skeinly.db.ProgressEntity
import io.github.b150005.skeinly.domain.model.Progress
import kotlin.time.Instant

fun ProgressEntity.toDomain(): Progress =
    Progress(
        id = id,
        projectId = project_id,
        rowNumber = row_number.toInt(),
        photoUrl = photo_url,
        note = note.orEmpty(),
        createdAt = Instant.parse(created_at),
        ownerId = owner_id,
    )
