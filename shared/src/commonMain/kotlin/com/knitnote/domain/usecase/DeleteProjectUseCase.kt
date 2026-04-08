package com.knitnote.domain.usecase

import com.knitnote.domain.repository.ProjectRepository

class DeleteProjectUseCase(private val repository: ProjectRepository) {
    suspend operator fun invoke(id: String) = repository.delete(id)
}
