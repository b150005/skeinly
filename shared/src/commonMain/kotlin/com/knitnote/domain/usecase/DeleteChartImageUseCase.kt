package com.knitnote.domain.usecase

import com.knitnote.data.remote.StorageOperations
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.repository.PatternRepository
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
            remoteStorage?.delete(listOf(imagePath))
            val updatedPattern =
                pattern.copy(
                    chartImageUrls = pattern.chartImageUrls - imagePath,
                    updatedAt = Clock.System.now(),
                )
            patternRepository.update(updatedPattern)
            UseCaseResult.Success(updatedPattern)
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
