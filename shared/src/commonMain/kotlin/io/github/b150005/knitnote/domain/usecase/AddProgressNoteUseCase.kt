package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.ProgressRepository
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AddProgressNoteUseCase(
    private val repository: ProgressRepository,
    private val authRepository: AuthRepository,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        projectId: String,
        rowNumber: Int,
        note: String,
        photoUrl: String? = null,
    ): UseCaseResult<Progress> {
        if (note.isBlank()) {
            return UseCaseResult.Failure(UseCaseError.Validation("Note must not be blank"))
        }
        val ownerId = authRepository.getCurrentUserId() ?: LocalUser.ID
        val progress =
            Progress(
                id = Uuid.random().toString(),
                projectId = projectId,
                rowNumber = rowNumber,
                photoUrl = photoUrl,
                note = note,
                createdAt = Clock.System.now(),
                ownerId = ownerId,
            )
        return UseCaseResult.Success(repository.create(progress))
    }
}
