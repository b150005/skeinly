package com.knitnote.domain.usecase

import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Project
import com.knitnote.domain.repository.PatternRepository
import com.knitnote.domain.repository.ProjectRepository
import com.knitnote.domain.repository.ShareRepository

data class SharedContent(
    val pattern: Pattern,
    val projects: List<Project>,
)

class ResolveShareTokenUseCase(
    private val shareRepository: ShareRepository?,
    private val patternRepository: PatternRepository,
    private val projectRepository: ProjectRepository,
) {

    suspend operator fun invoke(token: String): UseCaseResult<SharedContent> {
        if (shareRepository == null) {
            return UseCaseResult.Failure(UseCaseError.Validation("Sharing requires cloud connectivity"))
        }

        if (token.isBlank()) {
            return UseCaseResult.Failure(UseCaseError.Validation("Share token must not be blank"))
        }

        val share = shareRepository.getByToken(token)
            ?: return UseCaseResult.Failure(UseCaseError.NotFound("Share not found or expired"))

        val pattern = patternRepository.getById(share.patternId)
            ?: return UseCaseResult.Failure(UseCaseError.NotFound("Shared pattern not found"))

        val projects = projectRepository.getByPatternId(share.patternId)

        return UseCaseResult.Success(SharedContent(pattern = pattern, projects = projects))
    }
}
