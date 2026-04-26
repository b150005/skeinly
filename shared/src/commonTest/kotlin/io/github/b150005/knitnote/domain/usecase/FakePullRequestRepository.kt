package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestComment
import io.github.b150005.knitnote.domain.repository.PullRequestRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory fake of [PullRequestRepository] keyed per ownerId. Each
 * `MutableStateFlow` is the live source the observe() use case subscribes to,
 * so test-side calls to [setIncoming] / [setOutgoing] simulate Realtime peer
 * appends landing through the repository's local cache.
 *
 * Comments + open / close / postComment / getById live in [PullRequestRepository]
 * for the broader interface surface; 38.2 only exercises the read-side, so
 * those throw to surface accidental cross-test reach.
 */
class FakePullRequestRepository : PullRequestRepository {
    private val incoming = mutableMapOf<String, MutableStateFlow<List<PullRequest>>>()
    private val outgoing = mutableMapOf<String, MutableStateFlow<List<PullRequest>>>()
    var nextGetIncomingError: Throwable? = null
    var nextGetOutgoingError: Throwable? = null

    fun setIncoming(
        ownerId: String,
        prs: List<PullRequest>,
    ) {
        incoming.getOrPut(ownerId) { MutableStateFlow(emptyList()) }.value = prs
    }

    fun setOutgoing(
        ownerId: String,
        prs: List<PullRequest>,
    ) {
        outgoing.getOrPut(ownerId) { MutableStateFlow(emptyList()) }.value = prs
    }

    override suspend fun getById(id: String): PullRequest? = error("Not used by Phase 38.2 use cases")

    override suspend fun getIncomingForOwner(ownerId: String): List<PullRequest> {
        nextGetIncomingError?.let {
            nextGetIncomingError = null
            throw it
        }
        return incoming[ownerId]?.value.orEmpty()
    }

    override suspend fun getOutgoingForOwner(ownerId: String): List<PullRequest> {
        nextGetOutgoingError?.let {
            nextGetOutgoingError = null
            throw it
        }
        return outgoing[ownerId]?.value.orEmpty()
    }

    override fun observeIncomingForOwner(ownerId: String): Flow<List<PullRequest>> =
        incoming.getOrPut(ownerId) { MutableStateFlow(emptyList()) }

    override fun observeOutgoingForOwner(ownerId: String): Flow<List<PullRequest>> =
        outgoing.getOrPut(ownerId) { MutableStateFlow(emptyList()) }

    override suspend fun getCommentsForPullRequest(pullRequestId: String): List<PullRequestComment> =
        error("Not used by Phase 38.2 use cases")

    override fun observeCommentsForPullRequest(pullRequestId: String): Flow<List<PullRequestComment>> =
        error("Not used by Phase 38.2 use cases")

    override suspend fun openPullRequest(pullRequest: PullRequest): PullRequest = error("Not used by Phase 38.2 use cases")

    override suspend fun closePullRequest(pullRequest: PullRequest): PullRequest = error("Not used by Phase 38.2 use cases")

    override suspend fun postComment(comment: PullRequestComment): PullRequestComment = error("Not used by Phase 38.2 use cases")
}
