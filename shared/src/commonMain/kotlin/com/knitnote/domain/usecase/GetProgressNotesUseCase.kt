package com.knitnote.domain.usecase

import com.knitnote.domain.model.Progress
import com.knitnote.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.Flow

class GetProgressNotesUseCase(private val repository: ProgressRepository) {

    operator fun invoke(projectId: String): Flow<List<Progress>> =
        repository.observeByProjectId(projectId)
}
