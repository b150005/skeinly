package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.repository.PatternRepository
import kotlin.time.Clock

class UpdatePatternUseCase(
    private val repository: PatternRepository,
) {
    suspend operator fun invoke(
        patternId: String,
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
        val existing =
            repository.getById(patternId)
                ?: return UseCaseResult.Failure(UseCaseError.NotFound("Pattern not found: $patternId"))

        val updated =
            existing.copy(
                title = title.trim(),
                description = description?.takeIf { it.isNotBlank() },
                difficulty = difficulty,
                gauge = gauge?.takeIf { it.isNotBlank() },
                yarnInfo = yarnInfo?.takeIf { it.isNotBlank() },
                needleSize = needleSize?.takeIf { it.isNotBlank() },
                visibility = visibility,
                updatedAt = Clock.System.now(),
            )
        return UseCaseResult.Success(repository.update(updated))
    }
}
