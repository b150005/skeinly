package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.PullRequest
import io.github.b150005.skeinly.domain.model.PullRequestComment
import io.github.b150005.skeinly.domain.model.PullRequestStatus
import io.github.b150005.skeinly.domain.repository.PullRequestRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * In-memory fake of [PullRequestRepository] keyed per ownerId. Each
 * `MutableStateFlow` is the live source the observe() use case subscribes to,
 * so test-side calls to [setIncoming] / [setOutgoing] simulate Realtime peer
 * appends landing through the repository's local cache.
 *
 * Phase 38.3 widens this fake from "read-side only" to cover getById /
 * comment Flows / open / close / postComment so the new use cases can be
 * exercised end-to-end. Unused-by-current-tests methods stay as `error()` so
 * accidental cross-test reach surfaces immediately rather than silently
 * passing through a stub.
 */
class FakePullRequestRepository : PullRequestRepository {
    private val incoming = mutableMapOf<String, MutableStateFlow<List<PullRequest>>>()
    private val outgoing = mutableMapOf<String, MutableStateFlow<List<PullRequest>>>()
    private val byId = mutableMapOf<String, PullRequest>()
    private val commentFlows = mutableMapOf<String, MutableStateFlow<List<PullRequestComment>>>()

    var nextGetIncomingError: Throwable? = null
    var nextGetOutgoingError: Throwable? = null
    var nextGetByIdError: Throwable? = null
    var nextOpenError: Throwable? = null
    var nextCloseError: Throwable? = null
    var nextPostCommentError: Throwable? = null
    var nextGetCommentsError: Throwable? = null

    /**
     * Last argument received by [openPullRequest]. Lets tests assert that the
     * use case computed `commonAncestorRevisionId` correctly without reading
     * a return-only field.
     */
    var lastOpened: PullRequest? = null
        private set

    var lastClosed: PullRequest? = null
        private set

    var lastPosted: PullRequestComment? = null
        private set

    var subscribeCount: Int = 0
        private set

    var closeCount: Int = 0
        private set

    var lastSubscribedPrId: String? = null
        private set

    fun setIncoming(
        ownerId: String,
        prs: List<PullRequest>,
    ) {
        incoming.getOrPut(ownerId) { MutableStateFlow(emptyList()) }.value = prs
        prs.forEach { byId[it.id] = it }
    }

    fun setOutgoing(
        ownerId: String,
        prs: List<PullRequest>,
    ) {
        outgoing.getOrPut(ownerId) { MutableStateFlow(emptyList()) }.value = prs
        prs.forEach { byId[it.id] = it }
    }

    fun seedById(pullRequest: PullRequest) {
        byId[pullRequest.id] = pullRequest
    }

    fun setComments(
        prId: String,
        comments: List<PullRequestComment>,
    ) {
        commentFlows.getOrPut(prId) { MutableStateFlow(emptyList()) }.value = comments
    }

    override suspend fun getById(id: String): PullRequest? {
        nextGetByIdError?.let {
            nextGetByIdError = null
            throw it
        }
        return byId[id]
    }

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

    override suspend fun getCommentsForPullRequest(pullRequestId: String): List<PullRequestComment> {
        nextGetCommentsError?.let {
            nextGetCommentsError = null
            throw it
        }
        return commentFlows[pullRequestId]?.value.orEmpty()
    }

    override fun observeCommentsForPullRequest(pullRequestId: String): Flow<List<PullRequestComment>> =
        commentFlows.getOrPut(pullRequestId) { MutableStateFlow(emptyList()) }

    override suspend fun openPullRequest(pullRequest: PullRequest): PullRequest {
        nextOpenError?.let {
            nextOpenError = null
            throw it
        }
        lastOpened = pullRequest
        byId[pullRequest.id] = pullRequest
        return pullRequest
    }

    override suspend fun closePullRequest(pullRequest: PullRequest): PullRequest {
        nextCloseError?.let {
            nextCloseError = null
            throw it
        }
        val now: Instant = Clock.System.now()
        val closed =
            pullRequest.copy(
                status = PullRequestStatus.CLOSED,
                closedAt = pullRequest.closedAt ?: now,
                updatedAt = now,
            )
        lastClosed = closed
        byId[closed.id] = closed
        return closed
    }

    override suspend fun postComment(comment: PullRequestComment): PullRequestComment {
        nextPostCommentError?.let {
            nextPostCommentError = null
            throw it
        }
        lastPosted = comment
        val flow = commentFlows.getOrPut(comment.pullRequestId) { MutableStateFlow(emptyList()) }
        flow.value = flow.value + comment
        return comment
    }

    override suspend fun subscribeToCommentsChannel(pullRequestId: String) {
        subscribeCount += 1
        lastSubscribedPrId = pullRequestId
    }

    override suspend fun closeCommentsChannel() {
        closeCount += 1
    }
}
