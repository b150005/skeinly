package com.knitnote.domain.usecase

import com.knitnote.domain.model.Progress
import com.knitnote.domain.repository.ProgressRepository
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AddProgressNoteUseCase(private val repository: ProgressRepository) {

    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(projectId: String, rowNumber: Int, note: String): UseCaseResult<Progress> {
        if (note.isBlank()) {
            return UseCaseResult.Failure(UseCaseError.Validation("Note must not be blank"))
        }
        val progress = Progress(
            id = Uuid.random().toString(),
            projectId = projectId,
            rowNumber = rowNumber,
            photoUrl = null,
            note = note,
            createdAt = Clock.System.now(),
        )
        return UseCaseResult.Success(repository.create(progress))
    }
}
