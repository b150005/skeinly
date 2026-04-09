package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.ActivityTargetType
import com.knitnote.domain.model.ActivityType
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Share
import com.knitnote.domain.model.ShareLink
import com.knitnote.domain.model.SharePermission
import com.knitnote.domain.model.ShareStatus
import com.knitnote.domain.model.Visibility
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.PatternRepository
import com.knitnote.domain.repository.ProjectRepository
import com.knitnote.domain.repository.ShareRepository
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ShareProjectUseCase(
    private val projectRepository: ProjectRepository,
    private val patternRepository: PatternRepository,
    private val shareRepository: ShareRepository?,
    private val authRepository: AuthRepository,
    private val createActivity: CreateActivityUseCase? = null,
) {

    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        projectId: String,
        toUserId: String? = null,
        permission: SharePermission = SharePermission.VIEW,
    ): UseCaseResult<ShareLink> {
        if (shareRepository == null) {
            return UseCaseResult.Failure(UseCaseError.Validation("Sharing requires cloud connectivity"))
        }

        val userId = authRepository.getCurrentUserId()
            ?: return UseCaseResult.Failure(UseCaseError.Validation("Must be signed in to share"))

        val project = projectRepository.getById(projectId)
            ?: return UseCaseResult.Failure(UseCaseError.NotFound("Project not found"))

        if (project.ownerId != userId) {
            return UseCaseResult.Failure(UseCaseError.Validation("Can only share your own projects"))
        }

        if (toUserId == userId) {
            return UseCaseResult.Failure(UseCaseError.Validation("Cannot share with yourself"))
        }

        val now = Clock.System.now()

        // Get or create a Pattern for this project
        val patternId = if (project.patternId == LocalUser.DEFAULT_PATTERN_ID) {
            val pattern = Pattern(
                id = Uuid.random().toString(),
                ownerId = userId,
                title = project.title,
                description = null,
                difficulty = null,
                gauge = null,
                yarnInfo = null,
                needleSize = null,
                chartImageUrls = emptyList(),
                visibility = Visibility.SHARED,
                createdAt = now,
                updatedAt = now,
            )
            patternRepository.create(pattern)

            // Update the project's pattern_id to the new pattern
            projectRepository.update(project.copy(patternId = pattern.id, updatedAt = now))

            pattern.id
        } else {
            project.patternId
        }

        val isDirectShare = toUserId != null
        val shareToken = if (isDirectShare) null else Uuid.random().toString()
        val share = Share(
            id = Uuid.random().toString(),
            patternId = patternId,
            fromUserId = userId,
            toUserId = toUserId,
            permission = permission,
            status = if (isDirectShare) ShareStatus.PENDING else ShareStatus.ACCEPTED,
            shareToken = shareToken,
            sharedAt = now,
        )
        shareRepository.create(share)

        createActivity?.invoke(
            userId = userId,
            type = ActivityType.SHARED,
            targetType = ActivityTargetType.PATTERN,
            targetId = patternId,
        )

        return UseCaseResult.Success(
            ShareLink(
                shareId = share.id,
                shareToken = shareToken,
                patternId = patternId,
            )
        )
    }
}
