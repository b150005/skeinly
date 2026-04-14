package com.knitnote.domain.usecase

import com.knitnote.data.remote.PublicPatternDataSource
import com.knitnote.domain.model.Pattern
import kotlin.coroutines.cancellation.CancellationException

class GetPublicPatternsUseCase(
    private val publicPatternDataSource: PublicPatternDataSource?,
) {
    suspend operator fun invoke(searchQuery: String = ""): UseCaseResult<List<Pattern>> {
        if (publicPatternDataSource == null) {
            return UseCaseResult.Failure(UseCaseError.Validation("Discovery requires cloud connectivity"))
        }

        val sanitized = searchQuery.trim().take(MAX_SEARCH_LENGTH)
        return try {
            val patterns = publicPatternDataSource.getPublic(sanitized)
            UseCaseResult.Success(patterns)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }

    companion object {
        const val MAX_SEARCH_LENGTH = 200
    }
}
