package com.knitnote.data.mapper

import com.knitnote.db.ProgressEntity
import com.knitnote.domain.model.Progress
import kotlin.time.Instant

fun ProgressEntity.toDomain(): Progress = Progress(
    id = id,
    projectId = project_id,
    rowNumber = row_number.toInt(),
    photoUrl = photo_url,
    note = note.orEmpty(),
    createdAt = Instant.parse(created_at),
)
