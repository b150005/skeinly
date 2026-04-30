package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.Progress
import io.github.b150005.skeinly.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.Flow

class GetProgressNotesUseCase(
    private val repository: ProgressRepository,
) {
    operator fun invoke(projectId: String): Flow<List<Progress>> = repository.observeByProjectId(projectId)
}
