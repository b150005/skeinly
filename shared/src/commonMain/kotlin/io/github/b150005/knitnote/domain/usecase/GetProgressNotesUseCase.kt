package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.Flow

class GetProgressNotesUseCase(
    private val repository: ProgressRepository,
) {
    operator fun invoke(projectId: String): Flow<List<Progress>> = repository.observeByProjectId(projectId)
}
