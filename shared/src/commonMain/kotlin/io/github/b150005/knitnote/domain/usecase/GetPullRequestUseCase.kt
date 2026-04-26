package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.repository.PullRequestRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/**
 * Phase 38.3 (ADR-014 §6, §8) — read a single pull request for the detail
 * surface.
 *
 * [observe] is derived from the local cache via the appropriate Incoming /
 * Outgoing observe flow filtered by id, so PR-row UPDATEs landing through
 * Realtime (close, merge, etc.) propagate live without a re-fetch.
 *
 * The `ownerScope` parameter on [observe] tells the use case which observer
 * Flow to subscribe to. Both Incoming + Outgoing surface the same row when
 * the user is on either side of the PR; Phase 38.3 picks the scope that
 * matches the user's role on the entry point (target owner → INCOMING,
 * source author → OUTGOING).
 */
class GetPullRequestUseCase(
    private val repository: PullRequestRepository,
) {
    suspend operator fun invoke(prId: String): UseCaseResult<PullRequest> =
        try {
            val pr = repository.getById(prId)
            if (pr == null) {
                UseCaseResult.Failure(UseCaseError.NotFound("Pull request not found"))
            } else {
                UseCaseResult.Success(pr)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }

    /**
     * Flow of the single PR row. Filters whichever owner-scoped Flow the
     * caller indicates, so a status flip landing through Realtime emits a
     * fresh state without a manual refresh.
     */
    fun observe(
        prId: String,
        ownerId: String,
        scope: PullRequestObserveScope,
    ): Flow<PullRequest> {
        val source =
            when (scope) {
                PullRequestObserveScope.INCOMING -> repository.observeIncomingForOwner(ownerId)
                PullRequestObserveScope.OUTGOING -> repository.observeOutgoingForOwner(ownerId)
            }
        return source
            .map { rows -> rows.firstOrNull { it.id == prId } }
            .filterNotNull()
    }
}

/** Which owner-scoped observe stream a PR detail consumer should subscribe to. */
enum class PullRequestObserveScope {
    INCOMING,
    OUTGOING,
}
