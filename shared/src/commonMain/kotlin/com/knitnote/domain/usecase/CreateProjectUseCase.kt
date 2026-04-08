package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.repository.ProjectRepository
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CreateProjectUseCase(private val repository: ProjectRepository) {

    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(title: String, totalRows: Int?): Project {
        val project = Project(
            id = Uuid.random().toString(),
            ownerId = LocalUser.ID,
            patternId = LocalUser.DEFAULT_PATTERN_ID,
            title = title,
            status = ProjectStatus.NOT_STARTED,
            currentRow = 0,
            totalRows = totalRows,
            startedAt = null,
            completedAt = null,
            createdAt = Clock.System.now(),
        )
        return repository.create(project)
    }
}
