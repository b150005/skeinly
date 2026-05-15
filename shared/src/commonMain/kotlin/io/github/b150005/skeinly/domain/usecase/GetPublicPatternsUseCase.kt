package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.data.remote.PublicPatternDataSource
import io.github.b150005.skeinly.data.remote.PublicPatternsResult
import kotlin.coroutines.cancellation.CancellationException

class GetPublicPatternsUseCase(
    private val publicPatternDataSource: PublicPatternDataSource?,
) {
    /**
     * Fetch public patterns plus the chart-presence companion set per ADR-012
     * §4 / §5 (Phase 36.4). [chartsOnly] = true filters the returned list
     * server-side via INNER JOIN against `chart_documents`.
     *
     * [includeFriendsOnly] Phase 25.5 (ADR-024 §(f)): false (default) =
     * public-only Discovery feed; true widens to `visibility IN
     * ('public', 'friends')`. RLS still gates the friends rows on
     * is_friend, so this flag only shapes the request.
     */
    suspend operator fun invoke(
        searchQuery: String = "",
        chartsOnly: Boolean = false,
        includeFriendsOnly: Boolean = false,
    ): UseCaseResult<PublicPatternsResult> {
        if (publicPatternDataSource == null) {
            return UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
        }

        val sanitized = searchQuery.trim().take(MAX_SEARCH_LENGTH)
        return try {
            // `limit` intentionally uses the PublicPatternDataSource
            // interface default (100). Discovery is not yet paginated
            // (pre-existing, pre-Phase-25.5); when pagination lands it
            // becomes an explicit parameter here. Documented so the
            // omission reads as a decision, not an oversight.
            val result =
                publicPatternDataSource.getPublic(
                    sanitized,
                    chartsOnly = chartsOnly,
                    includeFriendsOnly = includeFriendsOnly,
                )
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
