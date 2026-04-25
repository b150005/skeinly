package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.data.remote.PublicPatternDataSource
import io.github.b150005.knitnote.data.remote.PublicPatternsResult
import kotlin.coroutines.cancellation.CancellationException

class GetPublicPatternsUseCase(
    private val publicPatternDataSource: PublicPatternDataSource?,
) {
    /**
     * Fetch public patterns plus the chart-presence companion set per ADR-012
     * §4 / §5 (Phase 36.4). [chartsOnly] = true filters the returned list
     * server-side via INNER JOIN against `chart_documents`.
     */
    suspend operator fun invoke(
        searchQuery: String = "",
        chartsOnly: Boolean = false,
    ): UseCaseResult<PublicPatternsResult> {
        if (publicPatternDataSource == null) {
            return UseCaseResult.Failure(UseCaseError.Validation("Discovery requires cloud connectivity"))
        }

        val sanitized = searchQuery.trim().take(MAX_SEARCH_LENGTH)
        return try {
            val result = publicPatternDataSource.getPublic(sanitized, chartsOnly = chartsOnly)
            UseCaseResult.Success(result)
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
