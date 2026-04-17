package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.repository.PatternRepository
import io.github.b150005.knitnote.domain.repository.StorageOperations
import kotlin.time.Clock

class DeleteChartImageUseCase(
    private val patternRepository: PatternRepository,
    private val remoteStorage: StorageOperations?,
) {
    suspend operator fun invoke(
        patternId: String,
        imagePath: String,
    ): UseCaseResult<Pattern> {
        val pattern =
            patternRepository.getById(patternId)
                ?: return UseCaseResult.Failure(UseCaseError.NotFound("Pattern not found"))

        if (imagePath !in pattern.chartImageUrls) {
            return UseCaseResult.Failure(UseCaseError.NotFound("Image not found in pattern"))
        }

        return try {
            val updatedPattern =
                pattern.copy(
                    chartImageUrls = pattern.chartImageUrls - imagePath,
                    updatedAt = Clock.System.now(),
                )
            patternRepository.update(updatedPattern)
            // Delete from storage after DB update succeeds — a failed storage
            // deletion is recoverable (orphaned file), but an orphaned DB path
            // causes broken image display indefinitely.
            remoteStorage?.delete(listOf(imagePath))
            UseCaseResult.Success(updatedPattern)
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
