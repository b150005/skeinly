package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.ActivityTargetType
import com.knitnote.domain.model.ActivityType
import com.knitnote.domain.model.Difficulty
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Visibility
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.PatternRepository
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CreatePatternUseCase(
    private val repository: PatternRepository,
    private val authRepository: AuthRepository,
    private val createActivity: CreateActivityUseCase? = null,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        title: String,
        description: String? = null,
        difficulty: Difficulty? = null,
        gauge: String? = null,
        yarnInfo: String? = null,
        needleSize: String? = null,
        visibility: Visibility = Visibility.PRIVATE,
    ): UseCaseResult<Pattern> {
        if (title.isBlank()) {
            return UseCaseResult.Failure(UseCaseError.Validation("Title must not be blank"))
        }
        val now = Clock.System.now()
        val ownerId = authRepository.getCurrentUserId() ?: LocalUser.ID
        val pattern =
            Pattern(
                id = Uuid.random().toString(),
                ownerId = ownerId,
                title = title.trim(),
                description = description?.takeIf { it.isNotBlank() },
                difficulty = difficulty,
                gauge = gauge?.takeIf { it.isNotBlank() },
                yarnInfo = yarnInfo?.takeIf { it.isNotBlank() },
                needleSize = needleSize?.takeIf { it.isNotBlank() },
                chartImageUrls = emptyList(),
                visibility = visibility,
                createdAt = now,
                updatedAt = now,
            )
        val created = repository.create(pattern)

        if (ownerId != LocalUser.ID) {
            createActivity?.invoke(
                userId = ownerId,
                type = ActivityType.CREATED,
                targetType = ActivityTargetType.PATTERN,
                targetId = created.id,
            )
        }

        return UseCaseResult.Success(created)
    }
}
