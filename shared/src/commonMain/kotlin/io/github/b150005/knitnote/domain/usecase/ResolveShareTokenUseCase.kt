package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.Share
import io.github.b150005.knitnote.domain.repository.PatternRepository
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import io.github.b150005.knitnote.domain.repository.ShareRepository

data class SharedContent(
    val share: Share,
    val pattern: Pattern,
    val projects: List<Project>,
)

/**
 * Resolves shared content by either a share token (link shares) or a share ID (direct shares).
 * Exactly one of [token] or [shareId] must be provided.
 */
class ResolveShareTokenUseCase(
    private val shareRepository: ShareRepository?,
    private val patternRepository: PatternRepository,
    private val projectRepository: ProjectRepository,
) {
    suspend operator fun invoke(
        token: String? = null,
        shareId: String? = null,
    ): UseCaseResult<SharedContent> {
        if (shareRepository == null) {
            return UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
        }

        val share =
            when {
                token != null && token.isNotBlank() -> {
                    shareRepository.getByToken(token)
                        ?: return UseCaseResult.Failure(UseCaseError.ResourceNotFound)
                }
                shareId != null && shareId.isNotBlank() -> {
                    shareRepository.getById(shareId)
                        ?: return UseCaseResult.Failure(UseCaseError.ResourceNotFound)
                }
                else -> {
                    return UseCaseResult.Failure(UseCaseError.FieldRequired)
                }
            }

        val pattern =
            patternRepository.getById(share.patternId)
                ?: return UseCaseResult.Failure(UseCaseError.ResourceNotFound)

        val projects = projectRepository.getByPatternId(share.patternId)

        return UseCaseResult.Success(SharedContent(share = share, pattern = pattern, projects = projects))
    }
}
