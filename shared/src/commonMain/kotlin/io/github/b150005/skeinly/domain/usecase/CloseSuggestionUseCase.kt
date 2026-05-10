package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.PullRequest
import io.github.b150005.skeinly.domain.model.PullRequestStatus
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.PullRequestRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Phase 38.3 (ADR-014 §6) — close an open pull request without merging.
 *
 * RLS in migration 016 permits UPDATE by either party (target owner OR
 * source author) with `WITH CHECK status IN ('open', 'closed')`. The use
 * case checks PR is still OPEN as defense-in-depth (a stale server-side
 * row already past OPEN should not produce a spurious "closed" UPDATE the
 * server then rejects with a permission error) and enforces caller is
 * signed in.
 *
 * **The "is caller authorized to close" check stays at the UI layer** —
 * `PullRequestDetailViewModel` resolves `targetOwnerId` from the target
 * pattern and gates the close button on `currentUserId == authorId ||
 * currentUserId == targetOwnerId`. Adding the same check here would force
 * the use case to take a `PatternRepository` dependency for a check the
 * server's RLS policy already enforces.
 *
 * **Offline-local-write window** (intentional, codebase-wide pattern). The
 * repository writes the CLOSED status to the local SQLite cache and queues
 * the UPDATE for sync. A signed-in user who bypasses the UI gate (e.g.
 * directly invokes the use case) and is neither the author nor the target
 * owner can briefly observe their own local cache as CLOSED — until the
 * sync attempt fails RLS and either Realtime corrects the cache from a
 * peer write, or the user re-fetches. This matches every other write path
 * in the repo (see `closePullRequest` in `PullRequestRepositoryImpl` for
 * the offline-tolerance baseline). Surfacing this as a server-trip-required
 * use case would forfeit the offline-first contract.
 */
class ClosePullRequestUseCase(
    private val repository: PullRequestRepository,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(pullRequest: PullRequest): UseCaseResult<PullRequest> {
        if (pullRequest.status != PullRequestStatus.OPEN) {
            return UseCaseResult.Failure(
                UseCaseError.OperationNotAllowed,
            )
        }
        if (authRepository.getCurrentUserId() == null) {
            return UseCaseResult.Failure(
                UseCaseError.Authentication(IllegalStateException("Must be signed in to close a pull request")),
            )
        }
        return try {
            UseCaseResult.Success(repository.closePullRequest(pullRequest))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
